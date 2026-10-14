package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;

public class SlendermanEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> DATA_TENT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_SIDE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** aggression 0..100 (synced; the camera mod reads it via getAggression()). */
    private static final EntityDataAccessor<Float> DATA_AGGRESSION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_CUE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Boolean> DATA_HIDDEN = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.BOOLEAN);

    public static boolean autoTeleport = true;

    private static final double SEEN_COS = 0.55D;

    // ---- creeping ----
    private static final int APPROACH_INTERVAL = 40;
    private static final int TELEPORT_HIDE_TICKS = 5;

    // ---- critical closeness ----
    private static final double CRITICAL_RANGE = 6.0D;
    /** aggression rises while the player is closer than this (base, before per-stage bonus). */
    private static final double AGGRESSION_RANGE = 6.0D;
    /** aggression 0..100: full in ~10 s at base rate while watched, falls in ~30 s when ignored. */
    private static final float AGGRESSION_RISE = 0.5F;   // per tick
    private static final float AGGRESSION_FALL = 0.15F;  // per tick
    /** tentacles come out near this aggression ... */
    private static final float AGGRESSION_ON = 95.0F;
    /** ... and retract once it drops below this one. */
    private static final float AGGRESSION_OFF = 30.0F;
    /** after how many ticks of being watched does aggression start to rise. */
    private static final int OBSERVED_THRESHOLD = 20;

    // ---- screamers ----
    private static final int SCARE_COOLDOWN_MIN = 20 * 20;
    private static final int SCARE_COOLDOWN_RANDOM = 20 * 5;
    private static final int ANIM_COOLDOWN_MIN = 20 * 40;
    private static final int ANIM_COOLDOWN_RANDOM = 20 * 20;
    private static final double PARK_MIN = 60.0D;

    // ---- sight sounds ----
    private static final double SIGHT_MIN = 12.0D;
    private static final double SIGHT_MAX = 40.0D;
    private static final int SIGHT_COOLDOWN_MIN = 20 * 15;
    private static final int SIGHT_COOLDOWN_RANDOM = 20 * 5;

    // ---- chasing ----
    private static final double WALK_SPEED = 1.0D;
    private static final double FAR_JUMP_DIST = 24.0D;
    private static final int FAR_JUMP_INTERVAL = 60;
    private static final double FAR_JUMP_STEP = 12.0D;
    private static final double FAR_JUMP_KEEP = 10.0D;
    private static final double HIGH_DY = 3.0D;
    private static final int HUNT_INTERVAL = 30;
    private static final double HUNT_MIN_DIST = 5.5D;
    private static final double ANGER_DISTANCE = 30.0D;
    private static final int ANGER_INTERVAL = 100;
    private static final float ANGER_CHANCE = 0.40F;

    // ---- per-stage tuning (index = pages, 1..8) ----
    // 1-2: watcher on the horizon (35-40 blocks), never walks, watching raises aggression 0->100
    // 3-4: closer (25-30), starts walking; 20 % front teleport + 40 % page-side teleport 10-15 blocks
    // 5-6: 10-15 blocks; 40 % front if falling behind + 40 % page-side 8-10 blocks
    // 7  : 5-10 blocks; 60 % on the path to the page at 5 blocks in front
    // 8  : finale
    //                                                  0      1      2      3      4      5      6      7      8
    private static final double[] KEEP_DIST     = { 35.0,  40.0,  37.0,  28.0,  25.0,  15.0,  12.0,   7.0,   7.0};
    private static final double[] WALK_MOD      = {  0.0,   0.0,   0.0,   0.8,   1.0,   1.15,  1.3,   1.5,   1.5};
    private static final int[]    AMBUSH_EVERY  = {    0,     0,     0,   400,   320,   240,   160,    80,    80};
    private static final float[]  AMBUSH_CHANCE = {  0F,    0F,    0F,  0.45F, 0.55F, 0.65F, 0.75F, 0.85F, 0.85F};
    private static final float[]  VANISH_BY     = {0.012F,0.012F,0.008F,0.004F,0.002F,0.001F,0.0005F,0F,   0F};
    private static final double[] AGG_RANGE_ADD = {  0.0,   0.0,   0.0,   1.0,   3.0,   5.0,   8.0,  11.0,  11.0};
    private static final float[]  AGG_RISE_MUL  = {  1F,    1F,    1F,   1.2F,  1.5F,  1.8F,  2.2F,  2.6F,  2.6F};
    private static final float[]  SCARE_SCALE   = { 1.6F,  1.6F,  1.3F,  1.0F,  0.9F,  0.8F,  0.65F, 0.5F,  0.5F};
    private static final float[]  REACH_BY      = {  0F,    0F,    0F,   0.25F, 0.35F, 0.40F, 0.45F, 0.50F, 0.50F};
    private static final float[]  BOW_BY        = {  0F,    0F,    0F,   0.20F, 0.25F, 0.30F, 0.30F, 0.30F, 0.30F};
    private static final double   DAMAGE_RANGE  = 2.3D;
    private static final float[]  DAMAGE_BY     = {  0F,    0F,    0F,    0F,    1F,    2F,    3F,    5F,    0F};

    // ---- sound cue ids (client side plays them as 2D sounds) ----
    /** roaming cue id. */
    private static final int CUE_ROAMING = 50;

    // ---- finale ----
    private static final int FINALE_NONE = 0;
    private static final int FINALE_HIDE = 1;
    private static final int FINALE_HOLD = 2;
    private static final int FINALE_HIDE_TICKS = 20 * 10;
    private static final int FINALE_HOLD_TICKS = 20 * 7;

    // ---- animations ----
    public static final int ACTION_NONE = 0;
    public static final int ACTION_LUNGE = 1;
    public static final int ACTION_PEEK = 2;
    public static final int LUNGE_TICKS = 20;
    public static final int REACH_TICKS = 2;
    private static final int PEEK_TICKS = 60;

    private static final double FOLLOW_DISTANCE = 48.0D;
    private static final int FOLLOW_DELAY = 40;

    private float tentPrev, tentNow, progPrev, progNow;
    private int action, actionTimer, actionDuration;
    private int vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16);
    private int vanishTicks = 0;
    private boolean vanished = false;
    private int observedTicks;
    private float tent;
    private boolean tentGoal;
    /** 0..100 aggression. */
    private float aggression;
    private int farTicks = 0;
    private ChunkPos forcedChunk;

    private int approachTicks = 0;
    private int tpHideTicks = 0;
    private int lastTeleportTick = -100;
    private boolean prevSeen = false;
    private double prevDist = -1.0D;
    private int unseenTicks = 0;
    private int scareCooldown = 0;
    private int sightCooldown = 0;
    private int reachTimer = 0;
    private boolean hiding = false;
    private int huntTicks = 0;
    private int farJumpTicks = 0;
    private int angerTicks = 0;
    private int stage = 1;
    private int ambushTicks = 0;
    private int watchTicks = 0;
    private int damageTicks = 10;
    private int finalePhase = FINALE_NONE;
    private int finaleTicks = 0;
    private int animCooldown = 0;
    private int cueSeq = 0;
    private int roamingCooldown = 0;
    private int lastCue = 0;
    private boolean cueInit = false;

    public Object roamingSound;

    public SlendermanEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.maxUpStep = 1.5F;
        this.xpReward = 0;
        this.setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 100.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.12D)
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_TENT, 0.0F);
        this.entityData.define(DATA_ACTION, 0);
        this.entityData.define(DATA_PROGRESS, 0.0F);
        this.entityData.define(DATA_SIDE, 1);
        this.entityData.define(DATA_HIDDEN, false);
        this.entityData.define(DATA_AGGRESSION, 0.0F);
        this.entityData.define(DATA_CUE, 0);
    }

    public int getAction() { return this.entityData.get(DATA_ACTION); }

    /** 0..100 — how aggressive he is right now (camera mod reads this). */
    public float getAggression() { return this.entityData.get(DATA_AGGRESSION); }

    public int getActionSide() { return this.entityData.get(DATA_SIDE); }

    public boolean isTeleportHidden() { return this.entityData.get(DATA_HIDDEN); }

    public float getProgress(float partial) {
        if (progNow <= 0.0F) return 0.0F;
        return Mth.lerp(partial, progPrev, progNow);
    }

    public float getTentacles(float partial) { return Mth.lerp(partial, tentPrev, tentNow); }

    @Override
    public void tick() {
        super.tick();
        if (this.level.isClientSide) {
            tentPrev = tentNow;
            tentNow = this.entityData.get(DATA_TENT);
            progPrev = progNow;
            progNow = this.entityData.get(DATA_PROGRESS);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.slenderman.client.SlendermanRoamingSound.clientTick(this));
            int cue = this.entityData.get(DATA_CUE);
            if (!this.cueInit) {
                this.cueInit = true;
                this.lastCue = cue;
            } else if (cue != this.lastCue) {
                this.lastCue = cue;
                final int soundId = cue % 100;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.slenderman.client.SlendermanRoamingSound.playCue(this, soundId));
            }
        } else {
            if (scareCooldown > 0) scareCooldown--;
            if (sightCooldown > 0) sightCooldown--;
            if (animCooldown > 0) animCooldown--;
            if (roamingCooldown > 0) roamingCooldown--;
            if (tpHideTicks > 0 && --tpHideTicks == 0) {
                this.entityData.set(DATA_HIDDEN, false);
            }
        }
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) { return 3.7F; }

    @Override
    public boolean isPushable() { return false; }

    @Override
    public boolean isInvulnerableTo(DamageSource source) { return !source.isBypassInvul(); }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {}

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    @Override
    public boolean canBeLeashed(Player player) { return false; }

    @Override
    public AABB getBoundingBoxForCulling() { return super.getBoundingBoxForCulling().inflate(8.0D, 4.0D, 8.0D); }

    private void keepChunkLoaded() {
        if (!(this.level instanceof ServerLevel serverLevel)) return;
        ChunkPos now = this.chunkPosition();
        if (now.equals(this.forcedChunk)) return;
        if (this.forcedChunk != null) {
            ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, this.forcedChunk.x, this.forcedChunk.z, false, true);
        }
        ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, now.x, now.z, true, true);
        this.forcedChunk = now;
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (!this.level.isClientSide && this.forcedChunk != null && reason.shouldDestroy()
                && this.level instanceof ServerLevel serverLevel) {
            ForgeChunkManager.forceChunk(serverLevel, SlendermanMod.ID, this, this.forcedChunk.x, this.forcedChunk.z, false, true);
            this.forcedChunk = null;
        }
        super.remove(reason);
    }

    private Player findTarget() {
        Player best = null;
        double bestDist = Double.MAX_VALUE;
        for (Player p : this.level.players()) {
            if (p.isSpectator() || !p.isAlive()) continue;
            double d = this.distanceToSqr(p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        this.keepChunkLoaded();

        Player player = this.findTarget();
        if (player == null) {
            this.standStill();
            return;
        }
        boolean seen = this.isSeenBy(player);
        double dist = this.distanceTo(player);

        this.updateStage(player);
        if (this.finalePhase != FINALE_NONE) {
            this.runFinale(player);
            return;
        }

        // ---- never lose the player: far away -> he jumps to the player ----
        if (dist > FOLLOW_DISTANCE && !this.vanished) {
            if (++this.farTicks >= FOLLOW_DELAY) {
                this.vanished = false;
                this.vanishTicks = 0;
                boolean ok = this.teleportNear(player, TeleportKind.FAR);
                if (ok) {
                    this.farTicks = 0;
                    this.approachTicks = 0;
                } else {
                    this.farTicks = FOLLOW_DELAY - 20;
                }
                return;
            }
        } else {
            this.farTicks = 0;
        }

        // ---- anger: player runs far away -> sometimes he appears right in front ----
        if (!this.vanished && this.stage >= 3 && dist > ANGER_DISTANCE) {
            if (++this.angerTicks >= ANGER_INTERVAL) {
                this.angerTicks = 0;
                if (this.action == ACTION_NONE && this.random.nextFloat() < ANGER_CHANCE
                        && this.teleportNear(player, TeleportKind.AHEAD)) {
                    this.approachTicks = 0;
                    return;
                }
            }
        } else {
            this.angerTicks = 0;
        }

        // ---- bookkeeping ----
        boolean wasSeen = this.prevSeen;
        double lastDist = this.prevDist;
        int unseenBefore = this.unseenTicks;
        this.prevSeen = seen;
        this.prevDist = dist;
        this.unseenTicks = seen ? 0 : this.unseenTicks + 1;
        this.observedTicks = seen ? this.observedTicks + 1 : 0;

        // ---- aggression 0..100: rise while watched (even at 35-40 blocks), fall when ignored ----
        double aggRange = AGGRESSION_RANGE + AGG_RANGE_ADD[this.stage];
        if (seen && this.observedTicks > OBSERVED_THRESHOLD) {
            float mul = AGG_RISE_MUL[this.stage];
            // at long distance it fills slower (so 40 blocks away you need ~2x the time)
            if (dist > aggRange) mul *= 0.4F;
            this.aggression = Math.min(100.0F, this.aggression + AGGRESSION_RISE * mul);
        } else {
            this.aggression = Math.max(0.0F, this.aggression - AGGRESSION_FALL);
        }
        this.entityData.set(DATA_AGGRESSION, this.aggression);
        // tentacles only when he is actually near — otherwise the model would sprout them across the map
        boolean tentaclesAllowed = dist <= Math.max(aggRange, 6.0D);
        if (this.aggression >= AGGRESSION_ON && tentaclesAllowed) this.tentGoal = true;
        else if (this.tent > 0.02F && dist <= aggRange) this.tentGoal = true;
        else if (this.aggression < AGGRESSION_OFF) this.tentGoal = false;
        this.updateTentacles(this.tentGoal);
        this.contactDamage(player, dist);

        // ---- roaming: at 1-2 blocks behind the player, play roaming.ogg; aggression already counted above ----
        if (!this.vanished && dist <= 2.5D && !this.isInFront(player) && this.roamingCooldown <= 0) {
            this.sendCue(CUE_ROAMING);
            this.roamingCooldown = 20 * 6;
        }

        // ---- screamers ----
        if (!this.vanished && this.action == ACTION_NONE) {
            if (seen && !wasSeen && dist <= CRITICAL_RANGE) {
                this.scare(true, unseenBefore);
            } else if (seen && lastDist > CRITICAL_RANGE && dist <= CRITICAL_RANGE
                    && this.tickCount - this.lastTeleportTick > 5) {
                this.scare(false, unseenBefore);
            }
        }

        // ---- sight sounds (far away, in view) ----
        if (!this.vanished && seen && this.sightCooldown <= 0 && dist > CRITICAL_RANGE && dist <= SIGHT_MAX) {
            this.playSight(player, dist);
        }

        // ---- running animation ----
        if (this.action != ACTION_NONE) {
            this.actionTimer++;
            float progress;
            if (this.action == ACTION_LUNGE) {
                if (this.actionTimer >= this.actionDuration) {
                    this.endAction();
                    return;
                }
                float x = Math.min(1.0F, (float) this.actionTimer / (float) REACH_TICKS);
                float inv = 1.0F - x;
                float rise = 1.0F - inv * inv * inv;
                float fall = Mth.clamp((float) (this.actionDuration - this.actionTimer) / 2.0F, 0.0F, 1.0F);
                progress = rise * fall;
                this.faceToward(player.position(), 40.0F);
                Vec3 eye = player.getEyePosition();
                this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 40.0F, 40.0F);
            } else {
                if (this.actionTimer >= this.actionDuration) {
                    this.endAction();
                    return;
                }
                float frac = (float) this.actionTimer / (float) this.actionDuration;
                progress = frac * frac * (3.0F - 2.0F * frac);
                this.faceToward(player.position(), 30.0F);
            }
            this.entityData.set(DATA_PROGRESS, progress);
            this.standStill();
            return;
        }

        if (this.vanished) {
            this.standStill();
            this.vanishTicks--;
            if (this.vanishTicks <= 0) {
                this.vanished = false;
                this.setInvisible(false);
                this.approachTicks = 0;
                this.reappear(player);
            }
            return;
        }

        if (this.vanishCooldown > 0) this.vanishCooldown--;

        if (seen && this.observedTicks > 80 && this.vanishCooldown <= 0
                && this.random.nextFloat() < VANISH_BY[this.stage]) {
            this.beginVanish(player);
            return;
        }

        // ---- watched-too-long teleport (stages 1-2): 10% chance per 2 s to jump to 20 blocks ----
        if (seen && this.stage <= 2 && this.observedTicks > 80 && autoTeleport && this.approachTicks <= 0) {
            if (this.random.nextFloat() < 0.10F) {
                if (this.teleportToDistance(player, 20.0D)) {
                    this.approachTicks = 20 * 5;
                    return;
                }
            }
            this.approachTicks = 40;
        }

        // ---- ambush (stages 3+): chance-scaled, but reduced in dense areas ----
        if (this.action == ACTION_NONE && AMBUSH_EVERY[this.stage] > 0 && autoTeleport && (!seen || this.stage >= 7)) {
            if (++this.ambushTicks >= AMBUSH_EVERY[this.stage]) {
                this.ambushTicks = 0;
                float chance = AMBUSH_CHANCE[this.stage];
                // "he does not always teleport when there are a lot of trees / buildings around"
                if (this.areaIsDense(player)) chance *= 0.4F;
                if (dist > 3.5D && this.random.nextFloat() < chance && this.ambush(player)) {
                    this.approachTicks = 0;
                    return;
                }
            }
        }

        boolean walking = false;
        if (seen) {
            this.approachTicks = Math.max(0, this.approachTicks);
            this.huntTicks = 0;
            this.farJumpTicks = 0;
            this.setYHeadRot(this.yBodyRot);
        } else {
            this.updateReachability(player, dist);
            walking = this.pursue(player, dist);
            if (!walking) {
                this.faceToward(player.position(), 60.0F);
            }
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 60.0F, 60.0F);
        }
        if (!walking) {
            this.standStill();
        }
        if (this.approachTicks > 0) this.approachTicks--;
    }

    /** true if there are so many solid blocks around the player that Slenderman should not teleport on top of him. */
    private boolean areaIsDense(Player player) {
        BlockPos p = player.blockPosition();
        int solid = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 4; dy++) {
                    BlockPos check = p.offset(dx, dy, dz);
                    if (!this.level.getBlockState(check).getCollisionShape(this.level, check).isEmpty()) {
                        if (++solid > 45) return true;
                    }
                }
            }
        }
        return false;
    }

    private void updateReachability(Player player, double dist) {
        if (--this.reachTimer > 0) return;
        this.reachTimer = 20;
        boolean high = player.getY() - this.getY() >= HIGH_DY;
        boolean blocked = false;
        if (!high && dist <= 40.0D) {
            Path path = this.getNavigation().createPath(player, 0);
            blocked = path == null || !path.canReach();
        }
        this.hiding = high || blocked;
    }

    /**
     * Chasing while nobody looks at him.
     * Stage 1-2: никогда не ходит пешком — только наблюдает с горизонта и изредка телепортируется.
     */
    private boolean pursue(Player player, double dist) {
        double keep = KEEP_DIST[this.stage];
        if (dist <= keep + 0.3D) {
            this.getNavigation().stop();
            if (this.stage <= 2 && autoTeleport && dist < keep * 0.55D && ++this.watchTicks >= 60) {
                this.watchTicks = 0;
                this.teleportNear(player, TeleportKind.WATCH);
            }
            return false;
        }
        // stages 1-2: никакой ходьбы и никакого подкрадывания. Только 10%-й телепорт при долгом наблюдении
        // (он обрабатывается в customServerAiStep, а не здесь).
        if (this.stage <= 2) {
            return false;
        }
        if (this.hiding && dist > Math.max(HUNT_MIN_DIST, keep)) {
            if (autoTeleport && ++this.huntTicks >= HUNT_INTERVAL) {
                this.huntTicks = 0;
                if (!this.teleportNear(player, TeleportKind.HUNT)) {
                    this.huntTicks = HUNT_INTERVAL - 20;
                }
            }
            return false;
        }
        this.huntTicks = 0;
        if (dist > FAR_JUMP_DIST) {
            if (autoTeleport && ++this.farJumpTicks >= FAR_JUMP_INTERVAL) {
                this.farJumpTicks = 0;
                if (!this.creepCloser(player, FAR_JUMP_STEP, Math.max(FAR_JUMP_KEEP, keep))) {
                    this.farJumpTicks = FAR_JUMP_INTERVAL - 20;
                }
            }
            return false;
        }
        this.farJumpTicks = 0;
        if (this.tickCount % 10 == 0 || this.getNavigation().isDone()) {
            this.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), WALK_SPEED * WALK_MOD[this.stage]);
        }
        return true;
    }

    private void standStill() {
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
    }

    private void startAction(int newAction, int duration) {
        this.action = newAction;
        this.actionTimer = 0;
        this.actionDuration = duration;
        this.entityData.set(DATA_ACTION, newAction);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    private void endAction() {
        this.action = ACTION_NONE;
        this.actionTimer = 0;
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
        this.standStill();
    }

    private void updateTentacles(boolean growing) {
        this.tent += Mth.clamp((growing ? 1.0F : 0.0F) - this.tent, -0.01F, 0.02F);
        this.entityData.set(DATA_TENT, this.tent);
    }

    // ------------------------------------------------------------------ screamers

    private void scare(boolean turned, int unseenBefore) {
        if (this.scareCooldown > 0 || this.vanished) return;
        this.scareCooldown = (int) ((SCARE_COOLDOWN_MIN + this.random.nextInt(SCARE_COOLDOWN_RANDOM + 1)) * SCARE_SCALE[this.stage]);
        int idx = this.random.nextInt(SlendermanMod.SCREAMS.size());
        this.sendCue(1 + idx);

        if (this.animCooldown > 0) return;
        float r = this.random.nextFloat();
        int anim = ACTION_NONE;
        if (r < REACH_BY[this.stage]) anim = ACTION_LUNGE;
        else if (r < REACH_BY[this.stage] + BOW_BY[this.stage]) anim = ACTION_PEEK;
        if (anim == ACTION_NONE) return;
        this.animCooldown = (int) ((ANIM_COOLDOWN_MIN + this.random.nextInt(ANIM_COOLDOWN_RANDOM + 1)) * SCARE_SCALE[this.stage]);
        if (anim == ACTION_LUNGE) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
        } else {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.entityData.set(DATA_SIDE, this.random.nextBoolean() ? 1 : -1);
        }
    }

    private void playSight(Player player, double dist) {
        this.sightCooldown = SIGHT_COOLDOWN_MIN + this.random.nextInt(SIGHT_COOLDOWN_RANDOM + 1);
        if (dist < SIGHT_MIN) {
            this.sendCue(30 + this.random.nextInt(SlendermanMod.MID_SIGHTS.size()));
        } else {
            this.sendCue(10 + this.random.nextInt(SlendermanMod.SIGHTS.size()));
        }
    }

    private void sendCue(int soundId) {
        this.cueSeq = (this.cueSeq + 1) % 20000 + 1;
        this.entityData.set(DATA_CUE, this.cueSeq * 100 + soundId);
    }

    // ------------------------------------------------------------------ aggression stages

    private void updateStage(Player player) {
        int pages = SlenderPages.get(player);
        this.stage = Mth.clamp(pages, 1, SlenderPages.TOTAL);
        if (pages >= SlenderPages.TOTAL) {
            if (this.finalePhase == FINALE_NONE) this.startFinale(player);
        } else if (this.finalePhase != FINALE_NONE) {
            this.finalePhase = FINALE_NONE;
            this.setInvisible(false);
        }
    }

    /**
     * One ambush teleport with per-stage probabilities:
     *   3-4 pages: 20 % front, 40 % page-side (10-15 blocks), rest mid
     *   5-6 pages: 40 % front (10-15 blocks), 40 % page-side (8-10 blocks), rest behind
     *   7   pages: 60 % on the path to the page (5 blocks in front), rest behind/ahead
     */
    private boolean ambush(Player player) {
        float r = this.random.nextFloat();
        if (this.stage <= 2) {
            return false;
        } else if (this.stage <= 4) {
            if (r < 0.20F) return this.teleportNear(player, TeleportKind.AHEAD);
            if (r < 0.60F) return this.blockPageWay(player, 10.0D, 15.0D);
            return this.teleportNear(player, TeleportKind.MID);
        } else if (this.stage <= 6) {
            if (r < 0.40F) return this.teleportNear(player, TeleportKind.AHEAD);
            if (r < 0.80F) return this.blockPageWay(player, 8.0D, 10.0D);
            return this.teleportNear(player, TeleportKind.BEHIND);
        } else {
            if (r < 0.60F) {
                if (this.blockPageWay(player, 5.0D, 5.0D)) return true;
            }
            return this.teleportNear(player, r < 0.80F ? TeleportKind.BEHIND : TeleportKind.AHEAD);
        }
    }

    /** appears between the player and the nearest page in the world, at minDist..maxDist from the player. */
    private boolean blockPageWay(Player player, double minDist, double maxDist) {
        List<SlenderPageEntity> pages = this.level.getEntitiesOfClass(SlenderPageEntity.class,
                player.getBoundingBox().inflate(48.0D));
        SlenderPageEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (SlenderPageEntity page : pages) {
            double d = page.distanceToSqr(player);
            if (d < best) {
                best = d;
                nearest = page;
            }
        }
        if (nearest == null || best < 16.0D) return false;
        double dx = nearest.getX() - player.getX();
        double dz = nearest.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return false;
        dx /= len;
        dz /= len;
        for (int attempt = 0; attempt < 12; attempt++) {
            double away = minDist + this.random.nextDouble() * (maxDist - minDist);
            away = Math.min(away, len - 1.5D);
            double side = (this.random.nextDouble() - 0.5D) * 2.0D;
            double x = player.getX() + dx * away - dz * side;
            double z = player.getZ() + dz * away + dx * side;
            double y = this.findGround(x, z, player.getY());
            if (Double.isNaN(y)) continue;
            if (this.areaIsDense(player) && this.random.nextFloat() < 0.6F) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    private void contactDamage(Player player, double dist) {
        float damage = DAMAGE_BY[this.stage];
        if (damage <= 0.0F || this.vanished || dist > DAMAGE_RANGE) {
            this.damageTicks = 10;
            return;
        }
        if (this.damageTicks > 0) {
            this.damageTicks--;
            return;
        }
        this.damageTicks = 20;
        player.hurt(DamageSource.mobAttack(this), dist < 1.5D ? damage * 2.0F : damage);
    }

    private void startFinale(Player player) {
        this.finalePhase = FINALE_HIDE;
        this.finaleTicks = FINALE_HIDE_TICKS;
        this.vanished = false;
        this.vanishTicks = 0;
        this.endAction();
        this.aggression = 0.0F;
        this.tentGoal = false;
        this.parkFarAway(player);
        this.setInvisible(true);
    }

    private void runFinale(Player player) {
        this.standStill();
        if (this.finalePhase == FINALE_HIDE) {
            this.aggression = 0.0F;
            this.entityData.set(DATA_AGGRESSION, 0.0F);
            this.updateTentacles(false);
            if (--this.finaleTicks > 0) return;
            this.setInvisible(false);
            if (!this.teleportNear(player, TeleportKind.BEHIND)) {
                this.teleportNear(player, TeleportKind.CLOSE);
            }
            this.finalePhase = FINALE_HOLD;
            this.finaleTicks = FINALE_HOLD_TICKS;
            this.sendCue(1 + this.random.nextInt(SlendermanMod.SCREAMS.size()));
        } else {
            this.aggression = 100.0F;
            this.entityData.set(DATA_AGGRESSION, 100.0F);
            this.tentGoal = true;
            this.updateTentacles(true);
            this.faceToward(player.position(), 20.0F);
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);
            if (--this.finaleTicks <= 0) {
                this.discard();
            }
        }
    }

    // ------------------------------------------------------------------ teleports

    public enum TeleportKind { FAR, MID, CLOSE, VANISH, AHEAD, HUNT, BEHIND, WATCH }

    private boolean creepCloser(Player player, double maxStep, double keep) {
        double dx = player.getX() - this.getX();
        double dz = player.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(maxStep, len - keep);
        if (len < 1.0E-3D || step < 0.5D) return true;
        dx /= len;
        dz /= len;
        double[] turns = {0.0D, 0.35D, -0.35D, 0.7D, -0.7D};
        for (double turn : turns) {
            double cos = Math.cos(turn);
            double sin = Math.sin(turn);
            double sx = dx * cos - dz * sin;
            double sz = dx * sin + dz * cos;
            double x = this.getX() + sx * step;
            double z = this.getZ() + sz * step;
            double y = this.findGround(x, z, this.getY());
            if (Double.isNaN(y)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    /** teleports him to a fixed distance from the player, random direction (stages 1-2 use this at 20 blocks). */
    public boolean teleportToDistance(Player player, double distance) {
        Vec3 look = player.getLookAngle();
        double baseAngle = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 24; attempt++) {
            double offsetDeg = this.random.nextDouble() * 360.0D;
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double y = this.findGround(x, z, player.getY());
            if (Double.isNaN(y)) continue;
            if (this.areaIsDense(player) && this.random.nextFloat() < 0.5F) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    public boolean teleportNear(Player player, TeleportKind kind) {
        Vec3 look = player.getLookAngle();
        double baseAngle = Math.atan2(look.z, look.x);
        int attempts = kind == TeleportKind.HUNT ? 48 : 32;
        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance, offsetDeg;
            switch (kind) {
                case AHEAD -> {
                    // stage-dependent: 5-6 pages = 10-15 blocks, 7 pages = 4-6 blocks, else 2-8
                    double minD = this.stage >= 7 ? 4.0D : (this.stage >= 5 ? 10.0D : 2.0D);
                    double maxD = this.stage >= 7 ? 6.0D : (this.stage >= 5 ? 15.0D : 8.0D);
                    distance = minD + this.random.nextDouble() * (maxD - minD);
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case HUNT -> {
                    distance = 2.5D + this.random.nextDouble() * 2.5D;
                    offsetDeg = this.random.nextDouble() * 360.0D;
                }
                case FAR -> {
                    distance = 10.0D + this.random.nextDouble() * 15.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case MID -> {
                    distance = 5.0D + this.random.nextDouble() * 5.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 60.0D;
                }
                case BEHIND -> {
                    distance = 2.5D + this.random.nextDouble() * 2.5D;
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 40.0D;
                }
                case WATCH -> {
                    distance = KEEP_DIST[this.stage] + (this.random.nextDouble() - 0.5D) * 8.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 110.0D;
                }
                case CLOSE -> {
                    distance = 2.0D + this.random.nextDouble() * 1.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 14.0D;
                }
                default -> {
                    distance = 20.0D + this.random.nextDouble() * 15.0D;
                    offsetDeg = (this.random.nextBoolean() ? 1 : -1) * (110.0D + this.random.nextDouble() * 70.0D);
                }
            }
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double need = (kind == TeleportKind.HUNT) ? (attempt < 16 ? 4.0D : (attempt < 32 ? 3.0D : 2.0D)) : 4.0D;
            double y = this.findGround(x, z, player.getY(), need);
            if (Double.isNaN(y)) continue;
            // in dense areas skip some destinations (he "does not always try")
            if (kind == TeleportKind.AHEAD && this.areaIsDense(player) && this.random.nextFloat() < 0.5F) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    private void relocate(double x, double y, double z, float yaw) {
        this.moveTo(x, y, z, yaw, 0.0F);
        this.setInvisible(false);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.fallDistance = 0.0F;
        this.action = ACTION_NONE;
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
        this.tpHideTicks = TELEPORT_HIDE_TICKS;
        this.entityData.set(DATA_HIDDEN, true);
        this.lastTeleportTick = this.tickCount;
        this.keepChunkLoaded();
    }

    private void afterTeleport(Player player) {
        if (this.distanceTo(player) <= CRITICAL_RANGE && this.isInFront(player)) {
            this.scare(false, this.unseenTicks);
        }
    }

    private void beginVanish(Player player) {
        this.vanished = true;
        this.vanishTicks = 20 * 15 + this.random.nextInt(20 * 16);
        this.vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16);
        this.standStill();
        this.parkFarAway(player);
        this.setInvisible(true);
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    private void parkFarAway(Player player) {
        double[] bands = {PARK_MIN, PARK_MIN * 0.7D, PARK_MIN * 0.45D};
        for (double band : bands) {
            for (int attempt = 0; attempt < 12; attempt++) {
                double angle = this.random.nextDouble() * Math.PI * 2.0D;
                double distance = band + this.random.nextDouble() * 25.0D;
                double x = player.getX() + Math.cos(angle) * distance;
                double z = player.getZ() + Math.sin(angle) * distance;
                double y = this.findGround(x, z, player.getY());
                if (Double.isNaN(y)) continue;
                this.relocate(x, y, z, this.getYRot());
                return;
            }
        }
    }

    /** reappear from a vanish: sometimes far (horizon), sometimes mid, occasionally close. */
    private void reappear(Player player) {
        float r = this.random.nextFloat();
        if (this.stage <= 2) {
            // stages 1-2: reappear on the horizon (35-40 blocks) — not necessarily behind
            this.teleportToDistance(player, KEEP_DIST[this.stage] + (this.random.nextDouble() - 0.5D) * 6.0D);
            return;
        }
        TeleportKind kind = r < 0.4F ? TeleportKind.FAR : (r < 0.8F ? TeleportKind.MID : TeleportKind.CLOSE);
        if (!this.teleportNear(player, kind)) {
            this.teleportNear(player, TeleportKind.FAR);
        }
    }

    private double findGround(double x, double z, double baseY) {
        return this.findGround(x, z, baseY, 4.0D);
    }

    private double findGround(double x, double z, double baseY, double height) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(baseY);
        for (int i = 0; i <= 16; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -((i + 1) / 2);
            BlockPos pos = new BlockPos(ix, iy + dy, iz);
            if (!this.level.hasChunkAt(pos)) continue;
            BlockState floor = this.level.getBlockState(pos.below());
            if (!floor.getCollisionShape(this.level, pos.below()).isEmpty()) {
                AABB box = new AABB(x - 0.4D, pos.getY(), z - 0.4D, x + 0.4D, pos.getY() + height, z + 0.4D);
                if (this.level.noCollision(this, box) && !this.level.containsAnyLiquid(box)) return pos.getY();
            }
        }
        return Double.NaN;
    }

    private boolean isInFront(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) return false;
        return flatLook.normalize().dot(toMe.normalize()) > 0.3D;
    }

    private boolean isSeenBy(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F).normalize();
        for (double h : new double[]{3.6D, 2.6D, 1.4D, 0.4D}) {
            Vec3 p = new Vec3(this.getX(), this.getY() + h, this.getZ());
            Vec3 to = p.subtract(eye);
            double len = to.length();
            if (len < 0.5D) return true;
            if (view.dot(to.scale(1.0D / len)) < SEEN_COS) continue;
            BlockHitResult hit = this.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) return true;
        }
        return false;
    }

    private void faceToward(Vec3 target, float maxStep) {
        double dx = target.x - this.getX();
        double dz = target.z - this.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float newYaw = Mth.approachDegrees(this.getYRot(), yaw, maxStep);
        this.setYRot(newYaw);
        this.yBodyRot = newYaw;
        this.setYHeadRot(newYaw);
    }

    /** Вызывается из SlenderPages, когда ближайший игрок подбирает страницу. */
    public void onPageTaken(int count) {
        this.stage = Mth.clamp(count, 1, SlenderPages.TOTAL);
        this.ambushTicks = AMBUSH_EVERY[this.stage] - 20;
        this.watchTicks = 0;
        this.farTicks = 0;
        this.approachTicks = 0;

        if (count >= SlenderPages.TOTAL && this.level instanceof ServerLevel) {
            Player player = this.findTarget();
            if (player != null && this.finalePhase == FINALE_NONE) {
                this.startFinale(player);
            }
        }
    }
}