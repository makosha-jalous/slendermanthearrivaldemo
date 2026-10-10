package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fml.DistExecutor;

public class SlendermanEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> DATA_TENT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** which way the head tilts during the bow: +1 or -1. */
    private static final EntityDataAccessor<Integer> DATA_SIDE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** true for a few ticks after every teleport: the client does not draw him, so he cannot "glide" to the new spot. */
    /** aggression level 0..1 (synced, so client side mods such as slendercam can read it with getAggression()). */
    private static final EntityDataAccessor<Float> DATA_AGGRESSION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> DATA_HIDDEN = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.BOOLEAN);

    /** switches the creeping teleports on/off (kept public: other classes may use it). */
    public static boolean autoTeleport = true;

    private static final double SEEN_COS = 0.55D;

    // ---- creeping: while nobody looks at him he jumps closer ----
    /** blocks he jumps closer per teleport. */
    private static final double APPROACH_STEP = 2.0D;
    /** ticks between two creeping teleports (2 seconds). */
    private static final int APPROACH_INTERVAL = 40;
    /** he never creeps closer than this (blocks). */
    private static final double APPROACH_MIN = 2.5D;
    /** ticks the client hides him after a teleport (disappear -> appear, no flying). */
    private static final int TELEPORT_HIDE_TICKS = 5;

    // ---- "critical" closeness ----
    /** closer than this = screamer zone (blocks). */
    private static final double CRITICAL_RANGE = 4.0D;
    /** closer than this his aggression level rises (blocks). */
    private static final double AGGRESSION_RANGE = 5.0D;
    /** aggression 0..1: full after 3.5 s close to the player, falls back slowly (10 s from full to zero). */
    private static final float AGGRESSION_RISE = 1.0F / 70.0F;
    private static final float AGGRESSION_FALL = 1.0F / 200.0F;
    /** tentacles come out when aggression reaches this value ... */
    private static final float AGGRESSION_ON = 0.99F;
    /** ... and only start to go back when it has fallen below this one (so a quick return keeps them out). */
    private static final float AGGRESSION_OFF = 0.30F;

    // ---- screamers ----
    /** after one screamer no other one can start for this long (so one event cannot fire two sounds). */
    private static final int SCARE_LOCK = 60;
    /** every single sound has its own cooldown of 15..20 seconds. */
    private static final int SOUND_COOLDOWN_MIN = 20 * 15;
    private static final int SOUND_COOLDOWN_RANDOM = 20 * 5;
    /** chance that a screamer also plays the reaching animation / the slow bow (the rest: no animation). */
    private static final float REACH_CHANCE = 0.40F;
    private static final float BOW_CHANCE = 0.30F;
    /** when the player TURNS to him, he has to be out of view at least this long (3 s) before an animation is possible. */
    private static final int ANIM_MIN_UNSEEN = 60;

    // ---- animations ----
    public static final int ACTION_NONE = 0;
    /** quick reach with the right arm over his head (1 s). */
    public static final int ACTION_LUNGE = 1;
    /** slow bow, played in front of the player like the reach (3 s). */
    public static final int ACTION_PEEK = 2;
    public static final int LUNGE_TICKS = 20;
    public static final int REACH_TICKS = 2;
    private static final int PEEK_TICKS = 60;

    /** he never walks; if the player is farther than this he jumps back to the player (any distance, no limit). */
    private static final double FOLLOW_DISTANCE = 48.0D;
    /** ticks the player has to stay far away before he catches up (2 seconds). */
    private static final int FOLLOW_DELAY = 40;

    private float tentPrev, tentNow, progPrev, progNow;
    private int action, actionTimer, actionDuration;
    private int vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
    private int vanishTicks = 0;
    private boolean vanished = false;
    private int observedTicks;
    private float tent;
    private boolean tentGoal;
    private float aggression;
    private int farTicks = 0;
    private ChunkPos forcedChunk;

    private int approachTicks = 0;
    private int tpHideTicks = 0;
    private int lastTeleportTick = -100;
    private boolean prevSeen = false;
    private double prevDist = -1.0D;
    private int unseenTicks = 0;
    private int scareLock = 0;
    /** animation of the previous screamer (NONE if it had none): two animations never follow each other. */
    private int lastAnim = ACTION_NONE;
    private final int[] soundCooldown = new int[SlendermanMod.SCREAMS.size()];

    /** client only: the running roaming sound (typed as Object so the server never loads client classes). */
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
                .add(Attributes.MOVEMENT_SPEED, 0.26D)
                .add(Attributes.FOLLOW_RANGE, 512.0D);
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
    }

    public int getAction() { return this.entityData.get(DATA_ACTION); }

    /** 0..1: how aggressive he is right now (rises while the player is close, falls slowly afterwards). */
    public float getAggression() { return this.entityData.get(DATA_AGGRESSION); }

    public int getActionSide() { return this.entityData.get(DATA_SIDE); }

    /** true right after a teleport: the renderer skips him for a few ticks. */
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
        } else {
            for (int i = 0; i < soundCooldown.length; i++) {
                if (soundCooldown[i] > 0) soundCooldown[i]--;
            }
            if (scareLock > 0) scareLock--;
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

    /** the chunk he stands in is kept loaded and ticking, so he never freezes when the player is far away or teleports. */
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

    /** nearest player in this dimension, at ANY distance (no 512 block limit any more). */
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

        // ---- he never loses the player: far away (or player teleported) -> he jumps to the player ----
        if (dist > FOLLOW_DISTANCE) {
            if (++this.farTicks >= FOLLOW_DELAY) {
                this.vanished = false;
                this.vanishTicks = 0;
                boolean ok = this.teleportNear(player, TeleportKind.FAR);
                if (ok) {
                    this.farTicks = 0;
                    this.approachTicks = 0;
                } else {
                    this.farTicks = FOLLOW_DELAY - 20; // no free spot found, try again in a second
                }
                return;
            }
        } else {
            this.farTicks = 0;
        }

        // ---- bookkeeping about what the player sees ----
        boolean wasSeen = this.prevSeen;
        double lastDist = this.prevDist;
        int unseenBefore = this.unseenTicks;
        this.prevSeen = seen;
        this.prevDist = dist;
        this.unseenTicks = seen ? 0 : this.unseenTicks + 1;
        this.observedTicks = seen ? this.observedTicks + 1 : 0;

        // ---- aggression: rises while the player is close, falls slowly when not; tentacles follow it ----
        if (dist <= AGGRESSION_RANGE) this.aggression = Math.min(1.0F, this.aggression + AGGRESSION_RISE);
        else this.aggression = Math.max(0.0F, this.aggression - AGGRESSION_FALL);
        this.entityData.set(DATA_AGGRESSION, this.aggression);
        if (this.aggression >= AGGRESSION_ON) this.tentGoal = true;
        else if (this.aggression < AGGRESSION_OFF) this.tentGoal = false;
        this.updateTentacles(this.tentGoal);

        // ---- screamers ----
        if (!this.vanished && this.action == ACTION_NONE) {
            if (seen && !wasSeen && dist <= CRITICAL_RANGE) {
                // 1) the player turned around to him and he stands critically close
                this.scare(true, unseenBefore);
            } else if (seen && lastDist > CRITICAL_RANGE && dist <= CRITICAL_RANGE
                    && this.tickCount - this.lastTeleportTick > 5) {
                // 2) the player walked up to him and reached the critical distance
                this.scare(false, unseenBefore);
            }
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
                // very sharp shoot-up (about 2 ticks), hold, then an equally sharp drop at the end
                float x = Math.min(1.0F, (float) this.actionTimer / (float) REACH_TICKS);
                float inv = 1.0F - x;
                float rise = 1.0F - inv * inv * inv;
                float fall = Mth.clamp((float) (this.actionDuration - this.actionTimer) / 2.0F, 0.0F, 1.0F);
                progress = rise * fall;
                this.faceToward(player.position(), 40.0F);
                // only the pitch matters: the model keeps the head looking at the player's eyes
                Vec3 eye = player.getEyePosition();
                this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 40.0F, 40.0F);
            } else {
                if (this.actionTimer >= this.actionDuration) {
                    this.endAction(); // snaps back to the normal pose at once
                    return;
                }
                // slow, smooth bow that keeps growing until the very end
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
            }
            return;
        }

        if (this.vanishCooldown > 0) this.vanishCooldown--;

        // Rare disappearance after the player has stared at him for a while.
        if (seen && this.observedTicks > 80 && this.vanishCooldown <= 0
                && this.random.nextFloat() < 0.004F) {
            this.beginVanish();
            return;
        }

        if (seen) {
            // the player is watching: he stands dead still, head level and straight ahead (does NOT look at the player)
            this.approachTicks = 0;
            this.setYHeadRot(this.yBodyRot);
        } else {
            // the player looks away: behind their back he turns to face them (so they find him looking at them)
            this.faceToward(player.position(), 60.0F);
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 60.0F, 60.0F);

            // creeping: every 2 seconds he jumps 2 blocks closer
            if (autoTeleport && ++this.approachTicks >= APPROACH_INTERVAL) {
                this.approachTicks = 0;
                this.creepCloser(player);
            }
        }
        this.standStill();
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

    /** random sound that is not on cooldown, or -1 if all of them are cooling down. */
    private int pickScream() {
        int free = 0;
        for (int c : soundCooldown) if (c <= 0) free++;
        if (free == 0) return -1;
        int n = this.random.nextInt(free);
        for (int i = 0; i < soundCooldown.length; i++) {
            if (soundCooldown[i] > 0) continue;
            if (n-- == 0) return i;
        }
        return -1;
    }

    /**
     * One screamer = exactly one sound (chosen at random). If every sound is still cooling down, nothing happens.
     * He is in the player's view and close, so the screamer may also come with an animation: the sharp reach
     * (40 %) or the slow bow (30 %), otherwise none. Two animations never follow each other. When the player
     * just TURNED to him, he must have been out of view for a while first.
     */
    private void scare(boolean turned, int unseenBefore) {
        if (this.scareLock > 0 || this.vanished) return;
        int idx = this.pickScream();
        if (idx < 0) return;
        this.scareLock = SCARE_LOCK;
        this.soundCooldown[idx] = SOUND_COOLDOWN_MIN + this.random.nextInt(SOUND_COOLDOWN_RANDOM + 1);
        // volume 2.0 = audible in full even when the player is not right next to him
        this.level.playSound(null, this.getX(), this.getY() + 2.0D, this.getZ(),
                SlendermanMod.SCREAMS.get(idx).get(), SoundSource.HOSTILE, 2.0F, 1.0F);

        int anim = ACTION_NONE;
        boolean eligible = this.lastAnim == ACTION_NONE && (!turned || unseenBefore >= ANIM_MIN_UNSEEN);
        if (eligible) {
            float r = this.random.nextFloat();
            if (r < REACH_CHANCE) anim = ACTION_LUNGE;
            else if (r < REACH_CHANCE + BOW_CHANCE) anim = ACTION_PEEK;
        }
        this.lastAnim = anim;
        if (anim == ACTION_LUNGE) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
        } else if (anim == ACTION_PEEK) {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.entityData.set(DATA_SIDE, this.random.nextBoolean() ? 1 : -1);
        }
    }

    // ------------------------------------------------------------------ teleports

    public enum TeleportKind { FAR, MID, CLOSE, VANISH }

    /** the creeping step: 2 blocks closer along the straight line to the player. */
    private void creepCloser(Player player) {
        double dx = player.getX() - this.getX();
        double dz = player.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(APPROACH_STEP, len - APPROACH_MIN);
        if (len < 1.0E-3D || step < 0.5D) return; // already as close as he gets
        dx /= len;
        dz /= len;
        // straight line first, then slightly to the sides if the spot is blocked
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
            return;
        }
        this.approachTicks = APPROACH_INTERVAL - 20; // blocked, try again in a second
    }

    public boolean teleportNear(Player player, TeleportKind kind) {
        Vec3 look = player.getLookAngle();
        double baseAngle = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 32; attempt++) {
            double distance, offsetDeg;
            switch (kind) {
                case FAR -> {
                    distance = 10.0D + this.random.nextDouble() * 15.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case MID -> {
                    distance = 5.0D + this.random.nextDouble() * 5.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 60.0D;
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
            double y = this.findGround(x, z, player.getY());
            if (Double.isNaN(y)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    /** the actual jump: he vanishes from the old spot and appears at the new one (the client hides him in between). */
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

    /** 3) he teleported right in front of the player in dangerous closeness -> screamer. */
    private void afterTeleport(Player player) {
        if (this.distanceTo(player) <= CRITICAL_RANGE && this.isInFront(player)) {
            this.scare(false, this.unseenTicks);
        }
    }

    private void beginVanish() {
        this.vanished = true;
        this.vanishTicks = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
        this.vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16);
        this.setInvisible(true);
        this.standStill();
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    /** looks for solid ground close to baseY (nearest first), with 4 blocks of free air above it. */
    private double findGround(double x, double z, double baseY) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(baseY);
        for (int i = 0; i <= 16; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -((i + 1) / 2);
            BlockPos pos = new BlockPos(ix, iy + dy, iz);
            if (!this.level.hasChunkAt(pos)) continue;
            BlockState floor = this.level.getBlockState(pos.below());
            if (!floor.getCollisionShape(this.level, pos.below()).isEmpty()) {
                AABB box = new AABB(x - 0.4D, pos.getY(), z - 0.4D, x + 0.4D, pos.getY() + 4.0D, z + 0.4D);
                if (this.level.noCollision(this, box) && !this.level.containsAnyLiquid(box)) return pos.getY();
            }
        }
        return Double.NaN;
    }

    /** he is in the half of the world the player is facing (a wide cone, wider than "seen"). */
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
}
