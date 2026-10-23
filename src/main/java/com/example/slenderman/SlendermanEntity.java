package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.EntityDamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
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
import net.minecraft.world.level.levelgen.Heightmap;
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
import java.util.UUID;

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
    /** sanity LOSS of the hunted player, 0..100 (synced, so camerahud can read it with getSanity()). */
    private static final EntityDataAccessor<Float> DATA_SANITY = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    /** teleport cue for the camera: counter * 4 + flags (flags: 1 = he appeared in the view of the player, 2 = in front of him). */
    private static final EntityDataAccessor<Integer> DATA_TELEPORT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    /** death scene for the client: 0 = none, 1 = the view turns to his face (3 s), 2 = black screen (2 s), 3 = portrait (3 s), 4 = last black screen. */
    /** watcher mode: changes when he vanishes in front of the player (the camera answers with a very short static). */
    private static final EntityDataAccessor<Integer> DATA_BLIP = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_DEATH = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);

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
    private static final double FAR_JUMP_DIST = 45.0D;
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
    // 1-2 pages: a watcher 20-25 blocks away, never attacks | 3-4: stalks 13-17 blocks away, steps into the way when the player runs
    // 5-6: hunter 8-10 blocks away, appears in front of the player | 7: open hunt 5-7 blocks away, steps into the way (50 %)
    // 8: the finale (he is gone for 10 s, then stands behind the player, then disappears)
    //                                                     0     1     2     3     4     5     6     7     8
    /** the distance he keeps to the player while stalking (blocks). */
    private static final double[] KEEP_DIST     = {22.5, 22.5, 22.5, 15.0, 15.0,  9.0,  9.0,  6.0,  6.0};
    /** walking speed multiplier (0 = he does not walk, he only appears at a new place). */
    private static final double[] WALK_MOD      = { 0.0,  0.7,  0.7,  0.8,  1.0,  1.15, 1.3,  1.5,  1.5};
    /** ticks between two "ambush" teleports (0 = none) and the chance that such a check really teleports. */
    private static final int[]    AMBUSH_EVERY  = {   0,    0,    0,    0,    0,  200,  160,  100,  100};
    private static final float[]  AMBUSH_CHANCE = { 0F,   0F,   0F,   0F,    0F,  0.40F, 0.40F, 0.50F, 0.50F};
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
    private static final double   DAMAGE_RANGE  = 2.0D;
    private static final float[]  DAMAGE_BY     = { 0F,   0F,   0F,  0F,   1F,   2F,   3F,   5F,   0F};
    /** stage 1-2: how often (ticks) the watcher repositions and how far he jumps toward the player at most. */
    private static final int[]    WATCH_EVERY   = { 160,  160,  100,  0,    0,    0,    0,    0,    0};
    private static final double   WATCH_STEP    = 8.0D;

    // ---- teleport ability ----
    /** the chance of one roll (index = pages): 1-2 pages always 20 %, 3-4: 30 %, 5-6: 40 %, 7: 50 %. */
    private static final float[] TELEPORT_CHANCE = { 0F, 0.20F, 0.20F, 0.30F, 0.30F, 0.40F, 0.40F, 0.50F, 0.50F};
    /** ticks between two rolls = CHECK_MIN + random(CHECK_RAND): 1-4 pages 30-50 s, 5-6 pages 15-20 s, 7 pages 5-10 s. */
    private static final int[] CHECK_MIN  = {0, 600, 600, 600, 600, 300, 300, 100, 100};
    private static final int[] CHECK_RAND = {0, 400, 400, 400, 400, 100, 100, 100, 100};
    /** a successful roll waits at most this long (10 s) for a moment when he may teleport (he is not looked at). */
    private static final int ARMED_MAX_TICKS = 20 * 10;
    /** pages 5-7: the player stands still for 15 s (and again every 15 s) -> he may punish him. */
    private static final int PUNISH_TICKS = 20 * 15;
    /** inside a structure / without a route to the player he appears at a new spot every 5 s until he is in his band. */
    private static final int HOP_TICKS = 100;
    /** the player runs and is farther than the band by this much -> he appears closer (every 3 s at most). */
    private static final double FLEE_GAP = 6.0D;
    private static final int CHASE_TICKS = 60;
    /** the tentacles come out when this much of the sanity (0..100) is lost. */
    private static final float TENTACLE_SANITY = 60.0F;
    /** stages 1-2: stared at for this long (ticks) -> he may appear in another place. */
    private static final int STARE_TICKS = 120;
    /** stages 1-2: the player stands still for 30 s -> he may appear in front of him. */
    private static final int IDLE_TICKS = 20 * 30;
    /** pages 3-7: the player stands still and does not look at him for this long (6 s) -> he walks right up behind his back. */
    private static final int STILL_TICKS = 20 * 6;
    /** how close he stands "right behind" the player (blocks, centre to centre). Touching him (hitboxes, about 0.7) kills; he never comes closer than about 2 blocks himself. */
    private static final double CONTACT_DIST = 1.6D;
    /** no teleport ever puts him closer than this to the player (blocks, horizontal): the hitboxes must never touch by a teleport. */
    private static final double TELEPORT_MIN_DIST = 1.1D;
    /** the distance band he keeps while stalking (blocks), index = pages collected: 1-2 pages 20-25, 3-4: 13-17, 5-6: 8-10, 7: 5-7. */
    private static final double[] BAND_MIN = {20.0, 20.0, 20.0, 13.0, 13.0,  8.0,  8.0,  5.0,  5.0};
    private static final double[] BAND_MAX = {25.0, 25.0, 25.0, 17.0, 17.0, 10.0, 10.0,  7.0,  7.0};
    /** "far behind" = farther than the band by this much. After 4 s of that (or of being stuck) he steps closer silently. */
    private static final double LAG_MARGIN = 14.0D;
    private static final int REPOSITION_TICKS = 80;
    private static final double REPOSITION_STEP = 12.0D;
    /** false = he never shows a walking animation: while nobody looks he appears a few blocks closer (silently) and stands still. */
    private static final boolean WALK = false;
    /** death scene after the black screen: the portrait (3 s), then a last black screen (about 1.2 s), then the death. */
    public static final int DEATH_PORTRAIT_TICKS = 60;
    public static final int DEATH_END_TICKS = 24;
    /** watcher mode: how far he stands (blocks), and when he vanishes because the player comes closer. */
    private static final double WATCH_MIN = 50.0D;
    private static final double WATCH_RANDOM = 12.0D;
    private static final double WATCH_FAR_CHANCE = 0.20D;
    private static final double WATCH_VANISH_DIST = 15.0D;
    private static final double WATCH_LOST_DIST = 100.0D;
    /** death scene: the view turns to his face for exactly 2 s (only when he was in view), then a sharp black screen for 2 s, then the portrait, then the death. */
    public static final int DEATH_TURN_TICKS = 40;
    public static final int DEATH_BLACK_TICKS = 40;
    /** blocks per tick (walking = 0.215, sprinting = 0.28). */
    private static final double RUN_SPEED = 0.24D;
    /** closer than this and behind the player's back (roaming.ogg plays) he takes the sanity without being looked at. */
    private static final double BEHIND_RANGE = 3.0D;

    // ---- sanity ----
    /** farther than this he is not "looked at" any more (blocks). */
    private static final double SANITY_MAX_DIST = 64.0D;
    /** ticks after the last look before the sanity starts to come back. */
    private static final int SANITY_RECOVER_DELAY = 5;
    /** at 100 he is in "kill" mode until the sanity has fallen below this. */
    private static final float SANITY_RUSH_OFF = 50.0F;
    private static final double SANITY_HIT_RANGE = 3.2D;
    private static final float SANITY_KILL_DAMAGE = 10000.0F;
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
    /** how many times the player has turned to him (a new sighting after he was unseen); the 4th makes him angry. */
    private int seenCount = 0;
    /** angry: the next teleport puts him very close (a fraction of the usual distance), like in the original game. */
    private boolean angry = false;
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
    private int idleTicks = 0;
    private Vec3 lastPlayerPos = null;
    private double playerSpeed = 0.0D;
    private double moveX = 0.0D, moveZ = 0.0D;
    private int blockTicks = 0;
    private int sanityDelay = 0;
    private boolean sanityRush = false;
    private int rushTicks = 0;
    private int rushGrace = 10;
    private int tpSeq = 0;
    private boolean suppressTeleportCue = false;
    private int teleportCheckTicks = 20 * 30 + this.random.nextInt(20 * 20 + 1);
    private boolean teleportArmed = false;
    private boolean armedCorner = false;
    private int armedLeft = 0;
    private int hopTicks = 0;
    private int chaseTicks = 0;
    private float deathSanityStart = 0.0F;
    private float recoverRate = 0.0F;
    private Vec3 cornerGoal = null;
    private int cornerTicks = 0;
    private int pathRetry = 0;
    private boolean pathOk = false;
    private int lagTicks = 0;
    private int stuckProbe = 0;
    private int stuckSeconds = 0;
    private Vec3 stuckPos = null;
    private int deathPhase = 0;
    private int hopStepTicks = 0;
    private boolean watchPlaced = false;
    private int watchRepos = 0;
    private int watchWait = 0;
    private int blipSeq = 0;
    private int deathTicks = 0;
    private UUID deathVictim = null;
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
        this.entityData.define(DATA_SANITY, 0.0F);
        this.entityData.define(DATA_TELEPORT, 0);
        this.entityData.define(DATA_DEATH, 0);
        this.entityData.define(DATA_BLIP, 0);
    }

    public int getAction() { return this.entityData.get(DATA_ACTION); }

    /** 0..1: how aggressive he is right now (rises while the player is close, falls slowly afterwards). */
    public float getAggression() { return this.entityData.get(DATA_AGGRESSION); }

    /** 0..100: how much sanity the hunted player has LOST (100 = he kills with one hit). Readable on the client. */
    public float getSanity() { return this.entityData.get(DATA_SANITY); }

    /** changes with every real teleport (counter * 4 + flags); the camera plays its jumpscare when it changes. */
    public int getTeleportCue() { return this.entityData.get(DATA_TELEPORT); }

    /** 0 = nothing, 1 = the view of the victim turns to his face, 2 = black screen before the death. */
    public int getDeathPhase() { return this.entityData.get(DATA_DEATH); }

    /** changes when the watcher vanishes in front of the player (camera: a ~60 ms static). */
    public int getBlipCue() { return this.entityData.get(DATA_BLIP); }

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

        // ---- death scene running: nothing else matters ----
        if (this.deathPhase != 0) {
            this.runDeath();
            return;
        }

        Player player = this.findTarget();
        if (player == null) {
            this.standStill();
            return;
        }
        boolean seen = this.isSeenBy(player);
        double dist = this.distanceTo(player);

        // watcher mode: he only watches from far away, nothing else
        if (SlenderModes.isWatch(player)) {
            this.runWatcher(player, seen, dist);
            return;
        }
        this.watchPlaced = false;
        final boolean god = SlenderModes.isGod(player);

        this.updateStage(player);
        if (this.finalePhase != FINALE_NONE) {
            this.runFinale(player);
            return;
        }

        // ---- sanity: rises while the player looks at him, falls when he does not; at 100 he kills ----
        this.trackPlayerMovement(player);
        if (god) {
            // God Mode: the sanity is never lost, nothing can kill
            this.calmSanity(player);
        } else {
            this.updateSanity(player, seen, dist);
            // the player walks into him: instant death (the view turns to his face first)
            // the view turns to his face (exactly 2 s) ONLY when the player ran into him while he was in view; from behind: no turn
            if (this.touchesPlayer(player) && this.beginDeath(player, seen)) return;
            if (this.sanityRush) {
                this.sanityKill(player, dist);
                return;
            }
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

        // ---- bookkeeping about what the player sees ----
        boolean wasSeen = this.prevSeen;
        double lastDist = this.prevDist;
        int unseenBefore = this.unseenTicks;
        this.prevSeen = seen;
        this.prevDist = dist;
        this.unseenTicks = seen ? 0 : this.unseenTicks + 1;
        this.observedTicks = seen ? this.observedTicks + 1 : 0;
        if (seen && !wasSeen && !this.vanished && !god && this.tickCount - this.lastTeleportTick > 10) {
            // the original's "seencount": after 4 sightings the next jump is very close
            if (++this.seenCount >= 4) {
                this.seenCount = 0;
                this.angry = true;
            }
        }

        // ---- aggression: rises while the player is close, falls slowly when not; tentacles follow it ----
        double aggRange = AGGRESSION_RANGE + AGG_RANGE_ADD[this.stage];
        if (dist <= aggRange) this.aggression = Math.min(1.0F, this.aggression + AGGRESSION_RISE * AGG_RISE_MUL[this.stage]);
        else this.aggression = Math.max(0.0F, this.aggression - AGGRESSION_FALL);
        this.entityData.set(DATA_AGGRESSION, this.aggression);
        // the tentacles come out when 60 of 100 sanity is lost and go back when it has fallen below 55
        float lost = this.entityData.get(DATA_SANITY);
        if (lost >= TENTACLE_SANITY) this.tentGoal = true;
        else if (lost < TENTACLE_SANITY - 5.0F) this.tentGoal = false;
        this.updateTentacles(this.tentGoal);
        if (!god) this.contactDamage(player, dist);

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

        // ---- teleport ability: checked every 30-50 s with the chance of the current page ----
        if (this.tryTeleportAbility(player, seen, dist)) {
            this.approachTicks = 0;
            return;
        }

        boolean walking = false;
        if (seen) {
            // the player is watching: he stops and stands dead still, head level and straight ahead (does NOT look at the player)
            this.approachTicks = 0;
            this.huntTicks = 0;
            this.farJumpTicks = 0;
            this.lagTicks = 0;
            this.stuckSeconds = 0;
            this.stuckProbe = 0;
            this.stuckPos = null;
            this.hopStepTicks = 0;
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
     * Chasing while nobody looks at him (on every page). Returns true while he is walking.
     *  - a corner he has chosen: he walks there first;
     *  - he walks (only when there is a route to the player) until he is inside the distance band of his stage
     *    (1-2 pages: 20-25 blocks, 3-4: 13-17, 5-6: 8-10, 7: 5-7) and walks faster the farther behind he is;
     *  - inside a structure, or without a route to the player, he does not walk: he works out a spot and appears there;
     *  - the player runs away: he appears closer instead of running after him;
     *  - pages 3-7: the player stands still and does not look: he walks right up behind his back;
     *  - no path (a 4 block tall mob needs a lot of free room) or stuck: he walks straight at the goal, and if that
     *    fails too he steps closer silently (he is not in the view of the player while this runs).
     */
    private boolean pursue(Player player, double dist) {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        boolean close = st >= 3 && this.idleTicks >= STILL_TICKS;
        double wantMax = close ? CONTACT_DIST : BAND_MAX[st];
        double tol = close ? 0.1D : 0.3D;   // right behind the player he stops at about 2.2 blocks, never closer
        double baseSpeed = WALK_SPEED * WALK_MOD[st];

        // inside a structure, or no route to the player: he does not walk, he works out a spot and appears there
        boolean indoors = this.playerIndoors(player);
        if (indoors || this.hiding) {
            this.cornerGoal = null;
            this.getNavigation().stop();
            if (dist > wantMax + tol) {
                if (autoTeleport && ++this.hopTicks >= HOP_TICKS) {
                    this.hopTicks = 0;
                    boolean ok = indoors ? this.hopInside(player, close) : this.teleportNear(player, TeleportKind.HUNT);
                    if (!ok) this.hopTicks = HOP_TICKS - 20;      // no spot found: try again in a second
                }
            } else {
                this.hopTicks = 0;
            }
            return false;
        }
        this.hopTicks = 0;

        if (this.cornerGoal != null) {
            if (--this.cornerTicks <= 0 || this.position().distanceTo(this.cornerGoal) < 1.5D) {
                this.cornerGoal = null;
            } else if (WALK) {
                this.walkToward(this.cornerGoal.x, this.cornerGoal.y, this.cornerGoal.z, baseSpeed);
                return true;
            } else {
                // no walking: he simply appears at the corner spot (it is hidden from the player by definition)
                Vec3 spot = this.cornerGoal;
                this.cornerGoal = null;
                float yaw = (float) (Mth.atan2(player.getZ() - spot.z, player.getX() - spot.x) * 57.29577951308232D) - 90.0F;
                this.suppressTeleportCue = true;
                this.relocate(spot.x, spot.y, spot.z, yaw);
                this.suppressTeleportCue = false;
                return false;
            }
        }
        if (dist <= wantMax + tol) {
            this.getNavigation().stop();
            this.lagTicks = 0;
            this.stuckSeconds = 0;
            return false;
        }
        // the player runs away: he does not run after him, he appears closer (behind / beside the runner)
        if (this.playerSpeed > RUN_SPEED && dist > wantMax + FLEE_GAP) {
            if (autoTeleport && ++this.chaseTicks >= CHASE_TICKS) {
                this.chaseTicks = 0;
                if (this.teleportNear(player, TeleportKind.CHASE)) return false;
            }
        } else {
            this.chaseTicks = 0;
        }
        double catchUp = 1.0D + Mth.clamp((dist - wantMax) / 12.0D, 0.0D, 1.5D);   // up to 2.5 times faster when far behind
        Vec3 goal = player.position();
        if (close) {
            // the spot right behind the player's back
            Vec3 look = player.getLookAngle();
            double len = Math.sqrt(look.x * look.x + look.z * look.z);
            if (len > 1.0E-3D) goal = goal.subtract(look.x / len * CONTACT_DIST, 0.0D, look.z / len * CONTACT_DIST);
        }
        if (WALK) {
            this.walkToward(goal.x, goal.y, goal.z, baseSpeed * catchUp);
            this.watchProgress(player, dist, wantMax);
            return true;
        }
        // no walking animation: while nobody looks he appears a few blocks closer (silently), faces the player
        // and stands still again - the faster the more pages there are
        int interval = Math.max(10, Math.round((float) (24.0D / Math.max(0.5D, WALK_MOD[st]))));
        if (autoTeleport && ++this.hopStepTicks >= interval) {
            this.hopStepTicks = 0;
            double step = Mth.clamp(3.5D * WALK_MOD[st] * catchUp, 2.0D, 12.0D);
            this.suppressTeleportCue = true;
            this.creepCloser(player, step, close ? CONTACT_DIST : wantMax - 1.0D);
            this.suppressTeleportCue = false;
        }
        return false;
    }

    /** walks to a point: along a path when the path finder finds one, otherwise (and when the path ends) straight at it. */
    private void walkToward(double x, double y, double z, double speed) {
        if (--this.pathRetry <= 0) {
            this.pathRetry = 10;
            this.pathOk = this.getNavigation().moveTo(x, y, z, speed);
        }
        if (!this.pathOk || this.getNavigation().isDone()) {
            this.getMoveControl().setWantedPosition(x, y, z, speed);
        }
    }

    /** a random distance inside the band of the current stage. */
    private double bandDistance() {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        return BAND_MIN[st] + this.random.nextDouble() * (BAND_MAX[st] - BAND_MIN[st]);
    }

    /** far behind the band, or not moving although he wants to: after 4 s he steps closer (silently, no teleport sound / glitch). */
    private void watchProgress(Player player, double dist, double wantMax) {
        if (++this.stuckProbe >= 20) {
            this.stuckProbe = 0;
            Vec3 now = this.position();
            if (this.stuckPos != null && now.distanceToSqr(this.stuckPos) < 0.36D) this.stuckSeconds++;
            else this.stuckSeconds = 0;
            this.stuckPos = now;
        }
        boolean lagging = dist > wantMax + LAG_MARGIN;
        if (!lagging && this.stuckSeconds < 3) {
            this.lagTicks = 0;
            return;
        }
        if (++this.lagTicks < REPOSITION_TICKS) return;
        this.lagTicks = 0;
        this.stuckSeconds = 0;
        if (!autoTeleport) return;
        this.suppressTeleportCue = true;
        this.creepCloser(player, REPOSITION_STEP, wantMax + 3.0D);
        this.suppressTeleportCue = false;
    }

    private void standStill() {
        this.getNavigation().stop();
        this.pathRetry = 0;
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
        // like the original: a short glitch of the camera together with the sight sound
        this.blipSeq = (this.blipSeq + 1) % 100000;
        this.entityData.set(DATA_BLIP, this.blipSeq);
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
        return this.teleportNear(player, TeleportKind.AHEAD);
    }

    /** stage 7: he appears between the player and the nearest page that is hanging in the world. */
    private boolean blockPageWay(Player player) {
        SlenderPageEntity nearest = this.findNearestPage(player);
        if (nearest == null || nearest.distanceToSqr(player) < 16.0D) return false;   // no page, or the player is already at it
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
            if (this.tooCloseToPlayer(player, x, z)) continue;
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
        if (damage <= 0.0F || this.vanished || this.isInvisible() || this.entityData.get(DATA_HIDDEN)
                || dist > DAMAGE_RANGE || !this.hasClearLine(player)) {
            this.damageTicks = 10;   // half a second of grace when he arrives next to the player
            return;
        }
        if (this.damageTicks > 0) {
            this.damageTicks--;
            return;
        }
        this.damageTicks = 20;
        player.hurt(this.attackSource(), dist < 1.2D ? damage * 2.0F : damage);
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
                SlenderSanity.reset(player);
                this.discard();   // the player has won
            }
        }
    }

    // ------------------------------------------------------------------ teleport ability

    /**
     * The teleport rolls. Time between two rolls and chance by pages: 1-4 pages every 30-50 s (1-2 pages 20 %, 3-4 pages
     * 30 %), 5-6 pages every 15-20 s (40 %), 7 pages every 5-10 s (50 %). A successful roll teleports him at once while
     * the player does not look at him (7 pages: also while he is looked at; 1-2 pages: also after a long stare).
     * From 5 pages he also punishes a player who stands still for 15 s. Returns true if he teleported.
     */
    private boolean tryTeleportAbility(Player player, boolean seen, double dist) {
        if (!autoTeleport || this.action != ACTION_NONE || this.vanished) return false;
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);

        // punishment: the player stands still for 15 s (and again every 15 s). A hundred-sided die decides, and the
        // chance of "yes" is different every time. He appears 3-4 blocks in front of the player.
        if (st >= 5 && this.idleTicks > 0 && this.idleTicks % PUNISH_TICKS == 0) {
            int chance = 1 + this.random.nextInt(100);
            if (this.random.nextInt(100) < chance && this.teleportNear(player, TeleportKind.PUNISH)) return true;
        }

        int cap = CHECK_MIN[st] + CHECK_RAND[st];
        if (this.teleportCheckTicks > cap) this.teleportCheckTicks = cap;   // a new page: the shorter waiting time counts at once
        if (--this.teleportCheckTicks <= 0) {
            this.teleportCheckTicks = CHECK_MIN[st] + this.random.nextInt(CHECK_RAND[st] + 1);
            if (this.random.nextFloat() < TELEPORT_CHANCE[st]) {
                this.teleportArmed = true;
                this.armedLeft = ARMED_MAX_TICKS;
            }
        }
        if (!this.teleportArmed) return false;
        if (--this.armedLeft <= 0) {
            this.teleportArmed = false;
            return false;
        }
        boolean mayNow = !seen || st >= 7 || (st <= 2 && this.observedTicks >= STARE_TICKS);
        if (!mayNow) return false;
        this.teleportArmed = false;
        return this.abilityTeleport(player, seen, dist);
    }

    /** a successful roll: where does he appear? Inside a structure he works out a spot there; outside it depends on the situation. */
    private boolean abilityTeleport(Player player, boolean seen, double dist) {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        if (this.playerIndoors(player)) {
            return this.hopInside(player, false) || this.teleportNear(player, TeleportKind.AHEAD);
        }
        // the player runs: he steps into the way to the page (or in front of the runner)
        if (st >= 3 && this.playerSpeed > RUN_SPEED) {
            if (this.runsTowardPage(player) && this.blockPageWay(player)) return true;
            if (this.teleportNear(player, TeleportKind.AHEAD)) return true;
        }
        if (st <= 2) {
            if (this.idleTicks >= IDLE_TICKS && this.teleportNear(player, TeleportKind.AHEAD)) return true;
            return this.teleportNear(player, TeleportKind.WATCH);
        }
        if (this.hiding && !seen && dist > HUNT_MIN_DIST && this.teleportNear(player, TeleportKind.HUNT)) return true;
        // sometimes he walks to a spot behind a corner and waits there for the player to turn
        if (!seen && this.random.nextFloat() < 0.25F) {
            Vec3 spot = this.findCornerSpot(player);
            if (spot != null) {
                this.cornerGoal = spot;
                this.cornerTicks = 240;
                return false;
            }
        }
        float r = this.random.nextFloat();
        TeleportKind kind;
        if (st <= 4) kind = r < 0.5F ? TeleportKind.MID : TeleportKind.AHEAD;
        else kind = r < 0.40F ? TeleportKind.BEHIND : TeleportKind.AHEAD;   // 5-7 pages: behind and in front
        return this.teleportNear(player, kind);
    }

    /**
     * The player is inside a structure / a building / a cave: there is a roof (not just leaves) within 12 blocks above his head.
     * Inside he does not walk, he teleports to spots he has worked out.
     */
    private boolean playerIndoors(Player player) {
        return this.hasRoof(player.getX(), player.getEyeY(), player.getZ());
    }

    private boolean hasRoof(double x, double fromY, double z) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int y0 = Mth.floor(fromY) + 1;
        for (int dy = 0; dy < 12; dy++) {
            BlockPos pos = new BlockPos(ix, y0 + dy, iz);
            if (!this.level.hasChunkAt(pos)) return false;
            BlockState state = this.level.getBlockState(pos);
            if (state.isAir() || state.is(BlockTags.LEAVES)) continue;
            if (!state.getCollisionShape(this.level, pos).isEmpty()) return true;
        }
        return false;
    }

    /** a teleport target that would be closer than TELEPORT_MIN_DIST to the player (horizontal distance). */
    private boolean tooCloseToPlayer(Player player, double x, double z) {
        double dx = x - player.getX();
        double dz = z - player.getZ();
        return dx * dx + dz * dz < TELEPORT_MIN_DIST * TELEPORT_MIN_DIST;
    }

    /** a random distance a little closer than the band up to its far end (for "in front of / behind the player" teleports). */
    private double aheadDistance() {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        double lo = Math.max(2.5D, BAND_MIN[st] - 2.0D);
        return lo + this.random.nextDouble() * (BAND_MAX[st] - lo);
    }

    /**
     * A spot inside the structure of the player: it has a roof above it, free room for him (4 blocks high; 3 blocks is
     * enough too), it lies in the distance band of his stage (a small room: closer and farther), and it is hidden from
     * the eyes of the player when possible (a corner, behind a wall).
     */
    private Vec3 findStructureSpot(Player player) {
        int st = Mth.clamp(this.stage, 1, SlenderPages.TOTAL);
        Vec3 eye = player.getEyePosition();
        for (int attempt = 0; attempt < 90; attempt++) {
            double lo = attempt < 45 ? BAND_MIN[st] : 2.5D;
            double hi = attempt < 45 ? BAND_MAX[st] : BAND_MAX[st] + 6.0D;
            double distance = lo + this.random.nextDouble() * (hi - lo);
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double y = this.findGround(x, z, player.getY(), (attempt % 2 == 0) ? 4.0D : 3.0D);
            if (Double.isNaN(y)) continue;
            if (!this.hasRoof(x, y + 1.0D, z)) continue;               // it has to be inside, not on the lawn
            if (attempt < 70 && this.visibleFrom(eye, x, y, z)) continue;   // hidden spots first
            return new Vec3(x, y, z);
        }
        return null;
    }

    /** the spot right behind the back of the player (2.6-3.8 blocks), 4 blocks high or 3. */
    private Vec3 findBehindSpot(Player player) {
        Vec3 look = player.getLookAngle();
        double base = Math.atan2(look.z, look.x) + Math.PI;
        for (int attempt = 0; attempt < 24; attempt++) {
            double distance = CONTACT_DIST + this.random.nextDouble() * 0.5D;     // 1.6-2.1 blocks
            double angle = base + Math.toRadians((this.random.nextDouble() - 0.5D) * 70.0D);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double y = this.findGround(x, z, player.getY(), attempt < 12 ? 4.0D : 3.0D);
            if (!Double.isNaN(y)) return new Vec3(x, y, z);
        }
        return null;
    }

    /** inside a structure he appears at a computed spot (close = right behind the back of the player). */
    private boolean hopInside(Player player, boolean close) {
        Vec3 spot = close ? this.findBehindSpot(player) : null;
        if (spot == null) spot = this.findStructureSpot(player);
        if (spot == null) return false;
        float yaw = (float) (Mth.atan2(player.getZ() - spot.z, player.getX() - spot.x) * 57.29577951308232D) - 90.0F;
        this.relocate(spot.x, spot.y, spot.z, yaw);
        this.afterTeleport(player);
        return true;
    }

    /** a spot 4-12 blocks from the player, mostly ahead of him, that he cannot see from where he stands (behind a corner or a wall). */
    private Vec3 findCornerSpot(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 40; attempt++) {
            double distance = 4.0D + this.random.nextDouble() * 8.0D;
            double angle = base + Math.toRadians((this.random.nextDouble() - 0.5D) * 160.0D);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double y = this.findGround(x, z, player.getY(), attempt < 20 ? 4.0D : 3.0D);
            if (Double.isNaN(y)) continue;
            if (this.visibleFrom(eye, x, y, z)) continue;
            return new Vec3(x, y, z);
        }
        return null;
    }

    private boolean visibleFrom(Vec3 eye, double x, double y, double z) {
        for (double h : new double[]{3.6D, 2.0D, 0.6D}) {
            Vec3 p = new Vec3(x, y + h, z);
            BlockHitResult hit = this.level.clip(new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) return true;
        }
        return false;
    }

    /** he is in the half of the world behind the player's back. */
    private boolean isBehind(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) return false;
        return flatLook.normalize().dot(toMe.normalize()) < -0.2D;
    }

    // ------------------------------------------------------------------ sanity and helpers

    /** a hit that is not scaled by the difficulty (a normal mob hit does 0 damage on peaceful). */
    private DamageSource attackSource() {
        return new EntityDamageSource("mob", this) {
            @Override
            public boolean scalesWithDifficulty() { return false; }
        };
    }

    /** nothing solid between his chest and the player's body: no damage through walls. */
    private boolean hasClearLine(Player player) {
        Vec3 a = new Vec3(this.getX(), this.getY() + 1.4D, this.getZ());
        Vec3 b = new Vec3(player.getX(), player.getY() + 1.0D, player.getZ());
        return this.level.clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this)).getType() == HitResult.Type.MISS;
    }

    /** how long the player stands still and how fast / in which direction he runs (every tick). */
    private void trackPlayerMovement(Player player) {
        Vec3 pos = player.position();
        if (this.lastPlayerPos == null) this.lastPlayerPos = pos;
        double dx = pos.x - this.lastPlayerPos.x;
        double dz = pos.z - this.lastPlayerPos.z;
        double inst = Math.sqrt(dx * dx + dz * dz);
        if (inst > 2.0D) {          // the player was teleported: not running
            inst = 0.0D;
            dx = 0.0D;
            dz = 0.0D;
        }
        this.playerSpeed = this.playerSpeed * 0.7D + inst * 0.3D;
        if (inst > 0.05D) {
            this.moveX = dx / inst;
            this.moveZ = dz / inst;
        }
        this.idleTicks = pos.distanceToSqr(this.lastPlayerPos) < 0.0004D ? this.idleTicks + 1 : 0;
        this.lastPlayerPos = pos;
    }

    private SlenderPageEntity findNearestPage(Player player) {
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
        return nearest;
    }

    /** the player runs in the direction of the nearest page that hangs in the world. */
    private boolean runsTowardPage(Player player) {
        SlenderPageEntity page = this.findNearestPage(player);
        if (page == null) return false;
        double dx = page.getX() - player.getX();
        double dz = page.getZ() - player.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 4.0D) return false;
        return (dx / len) * this.moveX + (dz / len) * this.moveZ > 0.7D;
    }

    /** sanity loss (0..100) of the player: rises while he looks at Slenderman, falls when he does not. */
    private void updateSanity(Player player, boolean seen, double dist) {
        float value = SlenderSanity.get(player);
        // only a model that is really there counts: no phantom while he is vanished, invisible or just teleported
        boolean modelVisible = !this.vanished && !this.isInvisible() && !this.entityData.get(DATA_HIDDEN);
        boolean looking = seen && modelVisible && dist <= SANITY_MAX_DIST;
        // right behind the player's back (roaming.ogg plays): the sanity goes down even without looking at him
        boolean behind = modelVisible && dist <= BEHIND_RANGE && this.isBehind(player) && this.hasClearLine(player);
        if (looking || behind) {
            value += SlenderSanity.lossPerTick(dist);
            this.sanityDelay = SANITY_RECOVER_DELAY;
            this.recoverRate = 0.0F;
        } else if (this.sanityDelay > 0) {
            this.sanityDelay--;
        } else {
            // the speed is fixed when the recovery starts: 15 s from a loss near 100, about 10 s from a small one
            if (this.recoverRate <= 0.0F) this.recoverRate = SlenderSanity.recoverPerTick(value);
            value -= this.recoverRate;
        }
        value = Mth.clamp(value, 0.0F, SlenderSanity.MAX);
        if (value <= 0.0F) this.recoverRate = 0.0F;
        SlenderSanity.set(player, value);
        this.entityData.set(DATA_SANITY, value);
        if (value >= SlenderSanity.MAX) this.sanityRush = true;
        else if (value <= SANITY_RUSH_OFF) this.sanityRush = false;
    }

    /** sanity is at 100: he appears right next to the player and kills him with one hit. */
    private void sanityKill(Player player, double dist) {
        this.vanished = false;
        this.setInvisible(false);
        this.standStill();
        if (dist > SANITY_HIT_RANGE) {
            this.rushGrace = 10;
            if (++this.rushTicks >= 10) {
                this.rushTicks = 0;
                if (!this.teleportNear(player, TeleportKind.CLOSE)) {
                    this.teleportNear(player, TeleportKind.HUNT);
                }
            }
            return;
        }
        this.faceToward(player.position(), 90.0F);
        if (!this.hasClearLine(player)) return;
        if (this.rushGrace > 0) {
            this.rushGrace--;
            return;
        }
        this.beginDeath(player, false);   // 2 s of black screen, then the hit
    }

    // ------------------------------------------------------------------ death scene

    /** the model is really there for the player: not "vanished", not invisible, not in the first ticks after a teleport. */
    private boolean isModelShown() {
        return !this.vanished && !this.isInvisible() && !this.entityData.get(DATA_HIDDEN);
    }

    /** the player walked into him (the boxes touch). */
    private boolean touchesPlayer(Player player) {
        if (!this.isModelShown() || player.isSpectator() || !player.isAlive()) return false;
        return this.getBoundingBox().inflate(0.05D, 0.0D, 0.05D).intersects(player.getBoundingBox());
    }

    /** the killing hit: survival players are killed even through a totem, creative players are not hurt. */
    private DamageSource killSource(Player victim) {
        final boolean bypass = !victim.isCreative() && !victim.isSpectator();
        return new EntityDamageSource("mob", this) {
            {
                if (bypass) this.bypassInvul();
            }

            @Override
            public boolean scalesWithDifficulty() { return false; }
        };
    }

    /**
     * Starts the death scene. turn = true (the player walked into him): the view turns to his face for exactly 3 s, then
     * 2 s of black screen, then the death. turn = false (sanity loss): only the 2 s of black screen, then the death.
     */
    private boolean beginDeath(Player player, boolean turn) {
        if (this.deathPhase != 0 || !player.isAlive()) return false;
        if (SlenderModes.isGod(player)) return false;     // God Mode: no death scene at all
        this.deathVictim = player.getUUID();
        this.deathPhase = turn ? 1 : 2;
        this.deathTicks = 0;
        this.deathSanityStart = SlenderSanity.get(player);
        this.entityData.set(DATA_DEATH, this.deathPhase);
        this.vanished = false;
        this.setInvisible(false);
        if (this.action != ACTION_NONE) this.endAction();
        this.standStill();
        // the player cannot walk away during the scene
        int total = (turn ? DEATH_TURN_TICKS : 0) + DEATH_BLACK_TICKS + DEATH_PORTRAIT_TICKS + DEATH_END_TICKS + 20;
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, total, 6, false, false, false));
        return true;
    }

    /** God Mode / watcher: the loss is zero all the time. */
    private void calmSanity(Player player) {
        SlenderSanity.set(player, 0.0F);
        this.entityData.set(DATA_SANITY, 0.0F);
        this.sanityRush = false;
        this.recoverRate = 0.0F;
    }

    // ------------------------------------------------------------------ watcher mode

    /**
     * Watcher mode (/slender watch): he stands very far away (50-62 blocks, sometimes up to 80), on hills, trees and
     * roofs, in the view of the player, and only watches. He does not move, nothing is lost. When the player comes
     * closer than 15 blocks he vanishes (a very short static in the camera), stays away for a while and then watches
     * from a new place.
     */
    private void runWatcher(Player player, boolean seen, double dist) {
        this.calmSanity(player);
        this.aggression = 0.0F;
        this.entityData.set(DATA_AGGRESSION, 0.0F);
        this.tentGoal = false;
        this.updateTentacles(false);
        if (this.action != ACTION_NONE) this.endAction();
        this.standStill();

        if (this.vanished) {
            if (--this.vanishTicks <= 0) {
                this.vanished = false;
                this.setInvisible(false);
                this.watchPlaced = false;
                this.watchWait = 0;
            }
            return;
        }
        if (!this.watchPlaced) {
            if (--this.watchWait > 0) return;
            if (this.placeWatchSpot(player)) {
                this.watchPlaced = true;
                this.watchRepos = 20 * 40 + this.random.nextInt(20 * 40);   // he changes the place now and then
            } else {
                this.watchWait = 20;
            }
            return;
        }
        if (dist < WATCH_VANISH_DIST) {
            // the player came too close: he is gone, the camera gets a very short static
            this.blipSeq = (this.blipSeq + 1) % 100000;
            this.entityData.set(DATA_BLIP, this.blipSeq);
            this.vanished = true;
            this.vanishTicks = 20 * 6 + this.random.nextInt(20 * 10);
            this.parkFarAway(player);
            this.setInvisible(true);
            return;
        }
        if (dist > WATCH_LOST_DIST || (!seen && --this.watchRepos <= 0)) {
            this.watchPlaced = false;       // the player went far away / a long time unseen: a new place
            this.watchWait = 0;
            return;
        }
        // he just stands and looks at the player
        this.faceToward(player.position(), 6.0F);
        Vec3 eye = player.getEyePosition();
        this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 30.0F);
    }

    /** a far spot (on top of the highest block: hills, treetops, roofs) in front of the player where the player can see him. */
    private boolean placeWatchSpot(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double base = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 90; attempt++) {
            double distance;
            if (attempt < 60) distance = this.random.nextDouble() < WATCH_FAR_CHANCE ? WATCH_MIN + WATCH_RANDOM + this.random.nextDouble() * 18.0D
                    : WATCH_MIN + this.random.nextDouble() * WATCH_RANDOM;
            else distance = 30.0D + this.random.nextDouble() * 20.0D;       // nothing found: a bit closer
            double angle = attempt < 60 ? base + Math.toRadians((this.random.nextDouble() - 0.5D) * 100.0D)
                    : this.random.nextDouble() * Math.PI * 2.0D;
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            int ix = Mth.floor(x);
            int iz = Mth.floor(z);
            if (!this.level.hasChunkAt(new BlockPos(ix, 64, iz))) continue;
            int top = this.level.getHeight(Heightmap.Types.MOTION_BLOCKING, ix, iz);
            BlockPos pos = new BlockPos(ix, top, iz);
            BlockState floor = this.level.getBlockState(pos.below());
            if (floor.getCollisionShape(this.level, pos.below()).isEmpty()) continue;
            AABB box = new AABB(x - 0.4D, top, z - 0.4D, x + 0.4D, top + 4.0D, z + 0.4D);
            if (!this.level.noCollision(this, box) || this.level.containsAnyLiquid(box)) continue;
            if (attempt < 60 && !this.visibleFrom(eye, x, top, z)) continue;
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.suppressTeleportCue = true;
            this.relocate(x, top, z, yaw);
            this.suppressTeleportCue = false;
            return true;
        }
        return false;
    }

    private void runDeath() {
        Player victim = this.deathVictim == null ? null : this.level.getPlayerByUUID(this.deathVictim);
        if (victim == null || !victim.isAlive()) {
            this.endDeath(null);
            return;
        }
        if (SlenderModes.isGod(victim)) {      // God Mode was switched on during the scene
            this.endDeath(victim);
            return;
        }
        this.standStill();
        this.faceToward(victim.position(), 60.0F);
        Vec3 eye = victim.getEyePosition();
        this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 60.0F, 60.0F);
        // during the turn the sanity rises evenly to 100 (the camera follows it), afterwards it stays at 100
        float lostNow = SlenderSanity.MAX;
        if (this.deathPhase == 1) {
            lostNow = Mth.lerp(Mth.clamp((float) this.deathTicks / (float) DEATH_TURN_TICKS, 0.0F, 1.0F),
                    this.deathSanityStart, SlenderSanity.MAX);
        }
        SlenderSanity.set(victim, lostNow);
        this.entityData.set(DATA_SANITY, lostNow);
        int limit = this.deathPhase == 1 ? DEATH_TURN_TICKS
                : this.deathPhase == 2 ? DEATH_BLACK_TICKS
                : this.deathPhase == 3 ? DEATH_PORTRAIT_TICKS : DEATH_END_TICKS;
        if (++this.deathTicks < limit) return;
        if (this.deathPhase < 4) {
            // 1 turn -> 2 black screen -> 3 portrait -> 4 last black screen -> the death
            this.deathPhase++;
            this.deathTicks = 0;
            this.entityData.set(DATA_DEATH, this.deathPhase);
            return;
        }
        victim.hurt(this.killSource(victim), SANITY_KILL_DAMAGE);   // -10000
        this.endDeath(victim);
    }

    private void endDeath(Player victim) {
        this.deathPhase = 0;
        this.deathTicks = 0;
        this.deathVictim = null;
        this.entityData.set(DATA_DEATH, 0);
        this.sanityRush = false;
        this.rushGrace = 10;
        this.damageTicks = 10;
        if (victim != null) {
            SlenderSanity.set(victim, 0.0F);
            this.entityData.set(DATA_SANITY, 0.0F);
            if (victim.isAlive()) victim.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);
        }
    }

    // ------------------------------------------------------------------ teleports

    public enum TeleportKind { FAR, MID, CLOSE, VANISH, AHEAD, HUNT, BEHIND, WATCH, PUNISH, CHASE }

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
            if (this.tooCloseToPlayer(player, x, z)) continue;
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
        // in front of / behind / next to the player he may also appear in low rooms and structures (4 -> 3 -> 2 blocks of free height)
        boolean tight = kind == TeleportKind.HUNT || kind == TeleportKind.AHEAD || kind == TeleportKind.BEHIND || kind == TeleportKind.CLOSE
                || kind == TeleportKind.MID || kind == TeleportKind.PUNISH || kind == TeleportKind.CHASE;
        int attempts = tight ? 48 : 32;
        for (int attempt = 0; attempt < attempts; attempt++) {
            double distance, offsetDeg;
            switch (kind) {
                case AHEAD -> {
                    // in front of the player, at the distance of his stage
                    distance = this.aheadDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case HUNT -> {
                    // the player hides: anywhere around them, even in a low room (4 -> 3 -> 2 blocks of free height)
                    distance = this.aheadDistance();
                    offsetDeg = this.random.nextDouble() * 360.0D;
                }
                case PUNISH -> {
                    // the punishment: 3-4 blocks in front of the player (later attempts: any side, a small room may have no spot in front)
                    distance = 3.0D + this.random.nextDouble();
                    offsetDeg = attempt < 24 ? (this.random.nextDouble() - 0.5D) * 50.0D : this.random.nextDouble() * 360.0D;
                }
                case CHASE -> {
                    // the player runs away: he appears behind / beside the runner, at the distance of his stage
                    distance = this.bandDistance();
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 140.0D;
                }
                case FAR -> {
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case MID -> {
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 60.0D;
                }
                case BEHIND -> {
                    // behind the player's back, at the distance of his stage
                    distance = this.aheadDistance();
                    offsetDeg = 180.0D + (this.random.nextDouble() - 0.5D) * 40.0D;
                }
                case WATCH -> {
                    // the watcher: on the horizon in front of the player, at his usual watching distance
                    distance = this.bandDistance();
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 110.0D;
                }
                case CLOSE -> {
                    distance = 2.2D + this.random.nextDouble() * 0.8D;      // 2.2-3.0 blocks, never closer
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 14.0D;
                }
                default -> {
                    distance = 20.0D + this.random.nextDouble() * 15.0D;
                    offsetDeg = (this.random.nextBoolean() ? 1 : -1) * (110.0D + this.random.nextDouble() * 70.0D);
                }
            }
            if (this.angry && kind != TeleportKind.WATCH && kind != TeleportKind.PUNISH && kind != TeleportKind.CLOSE) {
                // angry: an eighth of the usual distance, but never closer than 3 blocks
                distance = Math.max(3.0D, distance / 8.0D) + this.random.nextDouble() * 0.8D;
            }
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            if (this.tooCloseToPlayer(player, x, z)) continue;
            double need = tight ? (attempt < 16 ? 4.0D : (attempt < 32 ? 3.0D : 2.0D)) : 4.0D;
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
        boolean longJump = this.position().distanceTo(new Vec3(x, y, z)) > 4.0D;   // creeping steps are not "teleports"
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
        if (longJump && !this.suppressTeleportCue) this.signalTeleport();
    }

    /** tells the camera that he jumped, and whether that happened in the view of the player / in front of him. */
    private void signalTeleport() {
        int flags = 0;
        Player p = this.findTarget();
        if (p != null) {
            double d = this.distanceTo(p);
            if (d < 80.0D && this.isSeenBy(p)) flags |= 1;
            if (d < 30.0D && this.isInFront(p)) flags |= 2;
        }
        this.tpSeq = (this.tpSeq + 1) % 100000;
        this.entityData.set(DATA_TELEPORT, this.tpSeq * 4 + flags);
    }

    /** 3) he teleported right in front of the player in dangerous closeness -> screamer. */
    private void afterTeleport(Player player) {
        this.angry = false;
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
                this.suppressTeleportCue = true;
                this.relocate(x, y, z, this.getYRot());
                this.suppressTeleportCue = false;
                return;
            }
        }
    }

    /** he comes back from the vanish with a teleport (far, mid or critically close in front of the player). */
    private void reappear(Player player) {
        if (!this.teleportNear(player, TeleportKind.FAR)) {
            this.teleportNear(player, TeleportKind.WATCH);
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
            // he stands on the ground, never on leaves or on tree trunks
            if (floor.is(net.minecraft.tags.BlockTags.LEAVES) || floor.is(net.minecraft.tags.BlockTags.LOGS)) continue;
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
        this.teleportCheckTicks = Math.min(this.teleportCheckTicks, CHECK_MIN[this.stage] + this.random.nextInt(CHECK_RAND[this.stage] + 1));
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