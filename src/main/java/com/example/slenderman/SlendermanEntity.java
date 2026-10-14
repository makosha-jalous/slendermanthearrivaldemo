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
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

public class SlendermanEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> DATA_TENT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** which way the head tilts during the bow: +1 or -1. */
    private static final EntityDataAccessor<Integer> DATA_SIDE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** true for a few ticks after every teleport: the client does not draw him, so he cannot "glide" to the new spot. */
    /** aggression level 0..1 (synced, so client side mods such as slendercam can read it with getAggression()). */
    private static final EntityDataAccessor<Float> DATA_AGGRESSION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** which screamer / sight sound to play right now: (counter * 100 + sound id); the client plays it as a plain 2D sound. */
    private static final EntityDataAccessor<Integer> DATA_CUE = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
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
    private static final double CRITICAL_RANGE = 6.0D;
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
    /** after ANY screamer sound no other one can play for 20..25 seconds (the sound itself may come without an animation). */
    private static final int SCARE_COOLDOWN_MIN = 20 * 20;
    private static final int SCARE_COOLDOWN_RANDOM = 20 * 5;
    /** chance that a screamer sound also comes with the reaching animation / the slow bow (the rest: sound only). */
    private static final float REACH_CHANCE = 0.40F;
    private static final float BOW_CHANCE = 0.30F;
    /** after an animation the next one has to wait 40..60 seconds (he tries to "take" the player, not all the time). */
    private static final int ANIM_COOLDOWN_MIN = 20 * 40;
    private static final int ANIM_COOLDOWN_RANDOM = 20 * 20;
    /** while "vanished" he is parked this far away (blocks), so he leaves no phantom next to the player. */
    private static final double PARK_MIN = 60.0D;

    // ---- "sight" sounds: he is seen far away (but not too far) ----
    private static final double SIGHT_MIN = 12.0D;
    private static final double SIGHT_MAX = 40.0D;
    /** all four sight sounds share one cooldown of 15..20 seconds (independent of the screamers). */
    private static final int SIGHT_COOLDOWN_MIN = 20 * 15;
    private static final int SIGHT_COOLDOWN_RANDOM = 20 * 5;

    // ---- chasing ----
    /** he walks slowly (speed modifier on top of the movement speed attribute). */
    private static final double WALK_SPEED = 1.0D;
    /** farther than this he does not walk after the player, he jumps (teleports) closer instead (blocks). */
    private static final double FAR_JUMP_DIST = 24.0D;
    private static final int FAR_JUMP_INTERVAL = 60;
    private static final double FAR_JUMP_STEP = 12.0D;
    /** a far jump never lands closer than this to the player (blocks). */
    private static final double FAR_JUMP_KEEP = 10.0D;
    /** the player is "high" (hiding on a pillar, tree, roof) when he is this much higher than Slenderman (blocks). */
    private static final double HIGH_DY = 3.0D;
    /** when the player hides (no way to walk to him / high up) he teleports to him every 1.5 s ... */
    private static final int HUNT_INTERVAL = 30;
    /** ... unless he is already this close (blocks). */
    private static final double HUNT_MIN_DIST = 5.5D;
    /** anger: the player runs farther than this (blocks) ... */
    private static final double ANGER_DISTANCE = 30.0D;
    /** ... every 5 s there is a 40 % chance that he appears right in front of the player (2-8 blocks). */
    private static final int ANGER_INTERVAL = 100;
    private static final float ANGER_CHANCE = 0.40F;

    // ---- aggression stages: one per collected page (index = pages, 1..8; index 0 is never used) ----
    // 1-2 pages: a watcher on the horizon, never attacks | 3-4: comes closer, ambushes now and then
    // 5-6: a hunter, teleports behind and in front of the player | 7: relentless, blocks the way to the pages
    // 8: the finale (he is gone for 10 s, then stands behind the player, then disappears)
    //                                                     0     1     2     3     4     5     6     7     8
    /** the distance he keeps to the player while stalking (blocks). */
    private static final double[] KEEP_DIST     = {28.0, 28.0, 22.0, 16.0, 11.0,  7.0,  4.5,  2.8,  2.8};
    /** walking speed multiplier (0 = he does not walk, he only appears at a new place). */
    private static final double[] WALK_MOD      = { 0.0,  0.0,  0.0,  0.8,  1.0,  1.15, 1.3,  1.5,  1.5};
    /** ticks between two "ambush" teleports (0 = none) and the chance that such a check really teleports. */
    private static final int[]    AMBUSH_EVERY  = {   0,    0,    0,  400,  320,  240,  160,   80,   80};
    private static final float[]  AMBUSH_CHANCE = { 0F,   0F,   0F,  0.25F, 0.35F, 0.5F, 0.6F, 0.8F, 0.8F};
    /** chance per tick to vanish after a long stare (0 = he never vanishes). */
    private static final float[]  VANISH_BY     = {0.012F, 0.012F, 0.008F, 0.004F, 0.002F, 0.001F, 0.0005F, 0F, 0F};
    /** the aggression circle grows (blocks) and fills faster with every stage. */
    private static final double[] AGG_RANGE_ADD = { 0.0,  0.0,  0.0,  1.0,  3.0,  5.0,  8.0, 11.0, 11.0};
    private static final float[]  AGG_RISE_MUL  = { 1F,   1F,   1F,   1.2F, 1.5F, 1.8F, 2.2F, 2.6F, 2.6F};
    /** screamer / animation waiting times are multiplied by this (smaller = more often). */
    private static final float[]  SCARE_SCALE   = {1.6F, 1.6F, 1.3F, 1.0F, 0.9F, 0.8F, 0.65F, 0.5F, 0.5F};
    /** chance that a screamer sound comes with the reach / the bow (no animations at all in stages 1-2). */
    private static final float[]  REACH_BY      = { 0F,   0F,   0F,  0.25F, 0.35F, 0.40F, 0.45F, 0.50F, 0.50F};
    private static final float[]  BOW_BY        = { 0F,   0F,   0F,  0.20F, 0.25F, 0.30F, 0.30F, 0.30F, 0.30F};
    /** damage (half hearts) every second while he stands this close (blocks); doubled when he is really on top of the player. */
    private static final double   DAMAGE_RANGE  = 2.3D;
    private static final float[]  DAMAGE_BY     = { 0F,   0F,   0F,  0F,   1F,   2F,   3F,   5F,   0F};
    /** stage 1-2: how often (ticks) the watcher repositions and how far he jumps toward the player at most. */
    private static final int[]    WATCH_EVERY   = { 160,  160,  100,  0,    0,    0,    0,    0,    0};
    private static final double   WATCH_STEP    = 8.0D;
    /** finale: he is gone for 10 s, then stands behind the player for 7 s, then disappears for good. */
    private static final int FINALE_NONE = 0;
    private static final int FINALE_HIDE = 1;
    private static final int FINALE_HOLD = 2;
    private static final int FINALE_HIDE_TICKS = 20 * 10;
    private static final int FINALE_HOLD_TICKS = 20 * 7;

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
    private int scareCooldown = 0;
    private int sightCooldown = 0;
    private int reachTimer = 0;
    private boolean hiding = false;
    private int huntTicks = 0;
    private int farJumpTicks = 0;
    private int angerTicks = 0;
    /** aggression stage = pages the nearest player has collected (1..8). */
    private int stage = 1;
    private int ambushTicks = 0;
    private int watchTicks = 0;
    private int damageTicks = 10;
    private int finalePhase = FINALE_NONE;
    private int finaleTicks = 0;
    private int animCooldown = 0;
    private int cueSeq = 0;
    /** client only: the last cue that was already played. */
    private int lastCue = 0;
    private boolean cueInit = false;

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
                .add(Attributes.MOVEMENT_SPEED, 0.12D)
                .add(Attributes.FOLLOW_RANGE, 64.0D); // path search range only (a bigger value makes every path search very slow)
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
            // a new cue from the server = play that sound as a plain 2D sound (not positioned in the world)
            int cue = this.entityData.get(DATA_CUE);
            if (!this.cueInit) {
                this.cueInit = true;
                this.lastCue = cue;          // a sound that was started before we joined is not replayed
            } else if (cue != this.lastCue) {
                this.lastCue = cue;
                final int soundId = cue % 100;
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.example.slenderman.client.SlendermanRoamingSound.playCue(this, soundId));
            }
        } else {
            if (scareCooldown > 0) scareCooldown--;
            if (sightCooldown > 0) sightCooldown--;
            if (animCooldown > 0) animCooldown--;
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

        this.updateStage(player);
        if (this.finalePhase != FINALE_NONE) {
            this.runFinale(player);
            return;
        }

        // ---- he never loses the player: far away (or player teleported) -> he jumps to the player ----
        if (dist > FOLLOW_DISTANCE && !this.vanished) {
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

        // ---- anger: the player runs far away -> sometimes he appears right in front of them ----
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

        // ---- bookkeeping about what the player sees ----
        boolean wasSeen = this.prevSeen;
        double lastDist = this.prevDist;
        int unseenBefore = this.unseenTicks;
        this.prevSeen = seen;
        this.prevDist = dist;
        this.unseenTicks = seen ? 0 : this.unseenTicks + 1;
        this.observedTicks = seen ? this.observedTicks + 1 : 0;

        // ---- aggression: rises while the player is close, falls slowly when not; tentacles follow it ----
        double aggRange = AGGRESSION_RANGE + AGG_RANGE_ADD[this.stage];
        if (dist <= aggRange) this.aggression = Math.min(1.0F, this.aggression + AGGRESSION_RISE * AGG_RISE_MUL[this.stage]);
        else this.aggression = Math.max(0.0F, this.aggression - AGGRESSION_FALL);
        this.entityData.set(DATA_AGGRESSION, this.aggression);
        if (this.aggression >= AGGRESSION_ON) this.tentGoal = true;
        else if (this.tent > 0.02F && dist <= aggRange) this.tentGoal = true; // coming back while they retract: out again
        else if (this.aggression < AGGRESSION_OFF) this.tentGoal = false;
        this.updateTentacles(this.tentGoal);
        this.contactDamage(player, dist);

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

        // ---- far away and in view: one random "sight" sound (plays at the player, not from him) ----
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
                this.reappear(player);
            }
            return;
        }

        if (this.vanishCooldown > 0) this.vanishCooldown--;

        // Rare disappearance after the player has stared at him for a while.
        if (seen && this.observedTicks > 80 && this.vanishCooldown <= 0
                && this.random.nextFloat() < VANISH_BY[this.stage]) {
            this.beginVanish(player);
            return;
        }

        // ---- ambush: from 3 pages on he now and then appears somewhere new (stage 7: even while he is watched) ----
        if (this.action == ACTION_NONE && AMBUSH_EVERY[this.stage] > 0 && autoTeleport && (!seen || this.stage >= 7)) {
            if (++this.ambushTicks >= AMBUSH_EVERY[this.stage]) {
                this.ambushTicks = 0;
                if (dist > 3.5D && this.random.nextFloat() < AMBUSH_CHANCE[this.stage] && this.ambush(player)) {
                    this.approachTicks = 0;
                    return;
                }
            }
        }

        boolean walking = false;
        if (seen) {
            // the player is watching: he stops and stands dead still, head level and straight ahead (does NOT look at the player)
            this.approachTicks = 0;
            this.huntTicks = 0;
            this.farJumpTicks = 0;
            this.setYHeadRot(this.yBodyRot);
        } else {
            // the player looks away: behind their back he turns to face them and comes for them
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
    }

    /** every second: can he walk to the player at all? A player who is high up or shut away "hides". */
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
     * Chasing while nobody looks at him. Returns true while he is walking.
     *  - the player hides (high up / no way to walk to him): he teleports next to the player, again and again;
     *  - the player is far away: he jumps closer instead of walking;
     *  - otherwise he walks (slowly) and climbs up one-block steps after the player.
     */
    private boolean pursue(Player player, double dist) {
        double keep = KEEP_DIST[this.stage];
        if (dist <= keep + 0.3D) {
            this.getNavigation().stop();
            // a watcher (1-2 pages): the player walked up to him -> he backs off and shows up farther away
            if (this.stage <= 2 && autoTeleport && dist < keep * 0.55D && ++this.watchTicks >= 60) {
                this.watchTicks = 0;
                this.teleportNear(player, TeleportKind.WATCH);
            }
            return false;
        }
        // 1-2 pages: he does not walk, every few seconds he just appears a bit closer (never closer than "keep")
        if (this.stage <= 2) {
            if (autoTeleport && ++this.watchTicks >= WATCH_EVERY[this.stage]) {
                this.watchTicks = 0;
                if (!this.creepCloser(player, WATCH_STEP, keep)) {
                    this.watchTicks = WATCH_EVERY[this.stage] - 20;
                }
            }
            return false;
        }
        if (this.hiding && dist > Math.max(HUNT_MIN_DIST, keep)) {
            if (autoTeleport && ++this.huntTicks >= HUNT_INTERVAL) {
                this.huntTicks = 0;
                if (!this.teleportNear(player, TeleportKind.HUNT)) {
                    this.huntTicks = HUNT_INTERVAL - 20; // no free spot found, try again in a second
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

    /**
     * One screamer = exactly one random sound. After it NO screamer sound can play for 20-25 seconds, however often
     * the player turns to him. The animation is tied to the sound: when a sound plays it may come with the reach
     * (40 %) or the slow bow (30 %), but only if the animation cooldown (40-60 s) is over; otherwise sound only.
     */
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

    /** one random sound of the pool that fits the distance; then all of them rest for 15-20 s. */
    private void playSight(Player player, double dist) {
        this.sightCooldown = SIGHT_COOLDOWN_MIN + this.random.nextInt(SIGHT_COOLDOWN_RANDOM + 1);
        // 6-12 blocks: the "middle" sounds (ids 30..), 12-40 blocks: the "far" sounds (ids 10..)
        if (dist < SIGHT_MIN) {
            this.sendCue(30 + this.random.nextInt(SlendermanMod.MID_SIGHTS.size()));
        } else {
            this.sendCue(10 + this.random.nextInt(SlendermanMod.SIGHTS.size()));
        }
    }

    /**
     * Tells the client to play a sound as a plain 2D sound (it is NOT placed in the world, so it does not get
     * quieter or stay behind when the player runs). ids: 1..3 screamers, 10.. far sight, 30.. middle sight.
     */
    private void sendCue(int soundId) {
        this.cueSeq = (this.cueSeq + 1) % 20000 + 1;
        this.entityData.set(DATA_CUE, this.cueSeq * 100 + soundId);
    }

    // ------------------------------------------------------------------ aggression stages

    /** the stage follows the pages of the player he hunts; the 8th page starts the finale. */
    private void updateStage(Player player) {
        int pages = SlenderPages.get(player);
        this.stage = Mth.clamp(pages, 1, SlenderPages.TOTAL);
        if (pages >= SlenderPages.TOTAL) {
            if (this.finalePhase == FINALE_NONE) this.startFinale(player);
        } else if (this.finalePhase != FINALE_NONE) {
            // the pages were reset during the finale: back to normal
            this.finalePhase = FINALE_NONE;
            this.setInvisible(false);
        }
    }

    /** one ambush teleport; the kind depends on the stage. */
    private boolean ambush(Player player) {
        float r = this.random.nextFloat();
        TeleportKind kind;
        if (this.stage <= 3) {
            kind = TeleportKind.MID;
        } else if (this.stage == 4) {
            kind = r < 0.5F ? TeleportKind.MID : TeleportKind.AHEAD;
        } else if (this.stage <= 6) {
            kind = r < 0.45F ? TeleportKind.BEHIND : (r < 0.80F ? TeleportKind.AHEAD : TeleportKind.MID);
        } else {
            if (r >= 0.70F && this.blockPageWay(player)) return true;
            kind = r < 0.40F ? TeleportKind.BEHIND : TeleportKind.AHEAD;
        }
        return this.teleportNear(player, kind);
    }

    /** stage 7: he appears between the player and the nearest page that is hanging in the world. */
    private boolean blockPageWay(Player player) {
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
        if (nearest == null || best < 16.0D) return false;   // no page, or the player is already at it
        double dx = nearest.getX() - player.getX();
        double dz = nearest.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-3D) return false;
        dx /= len;
        dz /= len;
        for (int attempt = 0; attempt < 12; attempt++) {
            double away = Math.min(len - 1.5D, 4.0D + this.random.nextDouble() * 4.0D);
            double side = (this.random.nextDouble() - 0.5D) * 2.0D;
            double x = player.getX() + dx * away - dz * side;
            double z = player.getZ() + dz * away + dx * side;
            double y = this.findGround(x, z, player.getY());
            if (Double.isNaN(y)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.relocate(x, y, z, yaw);
            this.afterTeleport(player);
            return true;
        }
        return false;
    }

    /** from 4 pages on he hurts the player when he stands right next to them (every second). */
    private void contactDamage(Player player, double dist) {
        float damage = DAMAGE_BY[this.stage];
        if (damage <= 0.0F || this.vanished || dist > DAMAGE_RANGE) {
            this.damageTicks = 10;   // half a second of grace when he arrives next to the player
            return;
        }
        if (this.damageTicks > 0) {
            this.damageTicks--;
            return;
        }
        this.damageTicks = 20;
        player.hurt(DamageSource.mobAttack(this), dist < 1.5D ? damage * 2.0F : damage);
    }

    /** 8th page: silence, he is gone for 10 s ... */
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

    /** ... then he teleports right behind the player, stands there with the static at its maximum, and disappears. */
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
            this.aggression = 1.0F;
            this.entityData.set(DATA_AGGRESSION, 1.0F);
            this.tentGoal = true;
            this.updateTentacles(true);
            this.faceToward(player.position(), 20.0F);
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);
            if (--this.finaleTicks <= 0) {
                this.discard();   // the player has won
            }
        }
    }

    // ------------------------------------------------------------------ teleports

    public enum TeleportKind { FAR, MID, CLOSE, VANISH, AHEAD, HUNT, BEHIND, WATCH }

    /** the creeping step: 2 blocks closer along the straight line to the player. */
    private boolean creepCloser(Player player, double maxStep, double keep) {
        double dx = player.getX() - this.getX();
        double dz = player.getZ() - this.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        double step = Math.min(maxStep, len - keep);
        if (len < 1.0E-3D || step < 0.5D) return true; // already as close as he gets
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
                    // anger: right in front of the player, 2-8 blocks away
                    distance = 2.0D + this.random.nextDouble() * 6.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case HUNT -> {
                    // the player hides: anywhere around them, even in a low room (4 -> 3 -> 2 blocks of free height)
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
                    // right behind the player's back, 2.5-5 blocks away
                    distance = 2.5D + this.random.nextDouble() * 2.5D;
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 40.0D;
                }
                case WATCH -> {
                    // the watcher: on the horizon in front of the player, at his usual watching distance
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

    /**
     * He does not just turn invisible on the spot any more (that left a phantom entity next to the player that
     * other mods, like slendercam, still reacted to): he is moved far away and stays invisible there.
     */
    private void beginVanish(Player player) {
        this.vanished = true;
        this.vanishTicks = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
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

    /** he comes back from the vanish with a teleport (far, mid or critically close in front of the player). */
    private void reappear(Player player) {
        float r = this.random.nextFloat();
        TeleportKind kind = r < 0.4F ? TeleportKind.FAR : (r < 0.8F ? TeleportKind.MID : TeleportKind.CLOSE);
        if (!this.teleportNear(player, kind)) {
            this.teleportNear(player, TeleportKind.FAR);
        }
    }

    /** looks for solid ground close to baseY (nearest first), with 4 blocks of free air above it. */
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
    /**
     * Вызывается из SlenderPages, когда ближайший игрок подбирает страницу.
     * Сбрасывает таймеры, чтобы Слендермен быстрее устроил засаду.
     */
    public void onPageTaken(int count) {
        this.stage = Mth.clamp(count, 1, SlenderPages.TOTAL);
        this.ambushTicks = AMBUSH_EVERY[this.stage] - 20;
        this.watchTicks = 0;
        this.farTicks = 0;
        
        if (count >= SlenderPages.TOTAL && this.level instanceof ServerLevel) {
            Player player = this.findTarget();
            if (player != null && this.finalePhase == FINALE_NONE) {
                this.startFinale(player);
            }
        }
    }
}
