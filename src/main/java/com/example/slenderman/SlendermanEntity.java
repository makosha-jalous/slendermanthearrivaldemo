package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class SlendermanEntity extends PathfinderMob {

    // ------------------------------------------------------------------ synced data
    private static final EntityDataAccessor<Float> DATA_TENT =
            SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION =
            SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS =
            SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);

    /** /slenderteleport auto on|off */
    public static boolean autoTeleport = true;

    // ------------------------------------------------------------------ tuning
    /** dot product of the view vector and the direction to him above which he counts as "in the field of view". */
    private static final double SEEN_COS = 0.55D;
    private static final int STARE_TO_TENTACLES = 110;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_LUNGE = 1;
    public static final int ACTION_PEEK = 2;

    // ---- close-up jump-scare ("lean in and reach for the head") ----
    /** he starts the jump-scare only when almost face to face with the player (blocks). */
    private static final double LUNGE_RANGE = 3.4D;
    /** the planned lunge after a CLOSE teleport still fires if the player has not backed off further than this. */
    private static final double PENDING_LUNGE_RANGE = 3.8D;
    /** the whole pose is held for exactly 2 seconds = 40 game ticks, then he snaps back upright instantly. */
    public static final int LUNGE_TICKS = 40;
    /** the arm shoots out and the torso bends in this many ticks (sudden, aggressive). */
    public static final int REACH_TICKS = 3;
    private static final int PEEK_TICKS = 70;

    // ---- walking / stalking ----
    /** he only walks in short bursts, and only while he is not in the player's field of view. */
    private static final int WALK_BURST_MIN = 30;
    private static final int WALK_BURST_RANDOM = 50;
    private static final int REST_MIN = 60;
    private static final int REST_RANDOM = 100;

    // ------------------------------------------------------------------ state
    private float tentPrev;
    private float tentNow;
    private float progPrev;
    private float progNow;

    private int stare;
    private int action;
    private int actionTimer;
    private int actionDuration;
    private int lungeCooldown = 60;
    private int peekCooldown = 200;
    private int pendingLunge;
    private int tpCooldown = 400;
    private int observedTicks;
    private int walkTicks;
    private int restTicks = 40;
    private float tent;
    private float closeness;
    private boolean tentGoal;
    private Vec3 remembered;

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
                .add(Attributes.FOLLOW_RANGE, 64.0D);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_TENT, 0.0F);
        this.entityData.define(DATA_ACTION, 0);
        this.entityData.define(DATA_PROGRESS, 0.0F);
    }

    // ------------------------------------------------------------------ client accessors (used by the model)
    public int getAction() {
        return this.entityData.get(DATA_ACTION);
    }

    /**
     * 0..1 progress of the current action. The end of an action is a hard cut: as soon as the progress is 0
     * the pose is NOT interpolated, so the snap back to the upright pose is instant.
     */
    public float getProgress(float partial) {
        if (progNow <= 0.0F) {
            return 0.0F;
        }
        return Mth.lerp(partial, progPrev, progNow);
    }

    public float getTentacles(float partial) {
        return Mth.lerp(partial, tentPrev, tentNow);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level.isClientSide) {
            tentPrev = tentNow;
            tentNow = this.entityData.get(DATA_TENT);
            progPrev = progNow;
            progNow = this.entityData.get(DATA_PROGRESS);
        }
    }

    @Override
    protected float getStandingEyeHeight(Pose pose, EntityDimensions dimensions) {
        return 3.7F;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return !source.isBypassInvul();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        // silent steps
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(2.5D, 0.5D, 2.5D);
    }

    // ------------------------------------------------------------------ brain
    @Override
    protected void customServerAiStep() {
        super.customServerAiStep();
        Player player = this.level.getNearestPlayer(this, 64.0D);
        if (player == null) {
            this.getNavigation().stop();
            this.action = ACTION_NONE;
            this.stare = 0;
            this.walkTicks = 0;
            this.updateTentacles(false);
            this.entityData.set(DATA_ACTION, ACTION_NONE);
            this.entityData.set(DATA_PROGRESS, 0.0F);
            return;
        }

        boolean seen = this.isSeenBy(player);
        double dist = this.distanceTo(player);

        // staring at him makes the tentacles grow, looking away makes them fold back
        if (seen && dist < 45.0D) {
            this.stare = Math.min(this.stare + 1, 600);
        } else {
            this.stare = Math.max(0, this.stare - 3);
        }
        if (this.stare > STARE_TO_TENTACLES) {
            this.tentGoal = true;
        } else if (this.stare < 30) {
            this.tentGoal = false;
        }
        this.updateTentacles(this.tentGoal);

        this.observedTicks = seen ? this.observedTicks + 1 : 0;
        if (seen) {
            this.closeness = Math.max(0.0F, this.closeness - 0.0005F);
        } else {
            this.closeness = Math.min(1.0F, this.closeness + 0.002F);
        }

        // he remembers the last spot where the player stood while NOT being looked at
        if (this.remembered == null || (!seen && this.action == ACTION_NONE)) {
            this.remembered = player.getEyePosition();
        }

        if (this.lungeCooldown > 0) {
            this.lungeCooldown--;
        }
        if (this.peekCooldown > 0) {
            this.peekCooldown--;
        }

        // ---------------------------------------------- running action (jump-scare lunge / peek)
        if (this.action != ACTION_NONE) {
            this.actionTimer++;
            float progress;
            if (this.action == ACTION_LUNGE) {
                if (this.actionTimer > LUNGE_TICKS) {
                    this.endAction();
                    return;
                }
                // sudden, aggressive: full pose after REACH_TICKS (ease-out), then held until the 40th tick
                float x = Math.min(1.0F, (float) this.actionTimer / (float) REACH_TICKS);
                float inv = 1.0F - x;
                progress = 1.0F - inv * inv * inv;
            } else {
                float frac = (float) this.actionTimer / (float) this.actionDuration;
                float x = Math.min(1.0F, Math.min(frac / 0.25F, (1.0F - frac) / 0.25F));
                progress = x * x * (3.0F - 2.0F * x);
            }
            this.entityData.set(DATA_PROGRESS, progress);

            // completely still while the action plays
            this.getNavigation().stop();
            this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
            this.faceToward(player.position(), this.action == ACTION_LUNGE ? 28.0F : 12.0F);
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 40.0F);

            if (this.action == ACTION_PEEK && this.actionTimer >= this.actionDuration) {
                this.endAction();
            }
            return;
        }

        // ---------------------------------------------- teleports
        if (autoTeleport && --this.tpCooldown <= 0) {
            this.autoTeleport(player, seen, dist);
            return;
        }

        // ---------------------------------------------- close-up jump-scare
        if (this.pendingLunge > 0 && --this.pendingLunge == 0 && dist < PENDING_LUNGE_RANGE) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
            this.lungeCooldown = 140;
            return;
        }
        if (dist < LUNGE_RANGE && this.lungeCooldown == 0
                && this.random.nextFloat() < 0.012F * (0.5F + this.closeness)) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
            this.lungeCooldown = 140 + this.random.nextInt(120);
            return;
        }

        // ---------------------------------------------- slow bow behind the player
        if (!seen && this.isBehind(player) && dist > 3.0D && dist < 9.0D
                && this.peekCooldown == 0 && this.random.nextFloat() < 0.004F) {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.peekCooldown = 400 + this.random.nextInt(300);
            return;
        }

        // ---------------------------------------------- idle: stand still, or stalk when unseen
        this.getLookControl().setLookAt(this.remembered.x, this.remembered.y, this.remembered.z, 18.0F, 40.0F);

        if (seen) {
            // in the player's field of view: perfectly static, no pathfinding at all
            this.standStill();
            this.walkTicks = 0;
            return;
        }

        // Not in the field of view: he walks only in short bursts and stands dead still in between.
        if (this.walkTicks > 0) {
            this.walkTicks--;
            this.approach(player, dist);
            if (this.walkTicks == 0) {
                this.restTicks = REST_MIN + this.random.nextInt(REST_RANDOM);
                this.standStill();
            }
        } else if (this.restTicks > 0) {
            this.restTicks--;
            this.standStill();
        } else {
            this.walkTicks = WALK_BURST_MIN + this.random.nextInt(WALK_BURST_RANDOM);
        }
    }

    private void standStill() {
        this.getNavigation().stop();
        this.setDeltaMovement(0.0D, this.getDeltaMovement().y, 0.0D);
    }

    private void startAction(int newAction, int duration) {
        this.action = newAction;
        this.actionTimer = 0;
        this.actionDuration = duration;
        this.walkTicks = 0;
        this.entityData.set(DATA_ACTION, newAction);
        this.entityData.set(DATA_PROGRESS, 0.0F);
    }

    /** Back to the upright, dead-still pose. The pose is not blended: progress 0 is applied instantly. */
    private void endAction() {
        this.action = ACTION_NONE;
        this.actionTimer = 0;
        this.entityData.set(DATA_ACTION, ACTION_NONE);
        this.entityData.set(DATA_PROGRESS, 0.0F);
        this.standStill();
        // after the scare he rests before he moves again
        this.restTicks = REST_MIN + this.random.nextInt(REST_RANDOM);
    }

    private void updateTentacles(boolean growing) {
        this.tent += Mth.clamp((growing ? 1.0F : 0.0F) - this.tent, -0.05F, 0.02F);
        this.entityData.set(DATA_TENT, this.tent);
    }

    private void approach(Player player, double dist) {
        float stopDistance = Mth.lerp((float) Math.pow(this.closeness, 1.2D), 14.0F, 1.3F);
        if (dist <= stopDistance + 0.3D) {
            this.getNavigation().stop();
            return;
        }
        double speed = 1.0D + 0.6D * this.closeness;
        if (this.tickCount % 8 == 0 || this.getNavigation().isDone()) {
            boolean ok = this.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), speed);
            if (!ok) {
                this.getMoveControl().setWantedPosition(player.getX(), player.getY(), player.getZ(), speed);
            }
        }
    }

    // ------------------------------------------------------------------ teleports
    public enum TeleportKind {
        FAR, MID, CLOSE, VANISH
    }

    private void autoTeleport(Player player, boolean seen, double dist) {
        float r = this.random.nextFloat();
        TeleportKind kind;
        if (seen) {
            if (this.observedTicks > 60 && r < 0.35F) {
                kind = TeleportKind.VANISH;
            } else if (this.observedTicks > 60 && r < 0.55F) {
                kind = TeleportKind.CLOSE;
            } else if (r < 0.8F) {
                kind = TeleportKind.MID;
            } else {
                kind = TeleportKind.FAR;
            }
        } else if (dist > 28.0D) {
            kind = r < 0.7F ? TeleportKind.MID : TeleportKind.FAR;
        } else {
            kind = r < 0.5F ? TeleportKind.FAR : TeleportKind.MID;
        }
        boolean ok = this.teleportNear(player, kind);
        this.tpCooldown = ok ? 400 + this.random.nextInt(800) : 60;
    }

    public boolean teleportNear(Player player, TeleportKind kind) {
        Vec3 look = player.getLookAngle();
        double baseAngle = Math.atan2(look.z, look.x);
        for (int attempt = 0; attempt < 16; attempt++) {
            double distance;
            double offsetDeg;
            switch (kind) {
                case FAR -> {
                    distance = 28.0D + this.random.nextDouble() * 17.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 70.0D;
                }
                case MID -> {
                    distance = 9.0D + this.random.nextDouble() * 7.0D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 60.0D;
                }
                case CLOSE -> {
                    distance = 1.9D + this.random.nextDouble() * 0.7D;
                    offsetDeg = (this.random.nextDouble() - 0.5D) * 14.0D;
                }
                default -> { // VANISH: far to the side / behind
                    distance = 25.0D + this.random.nextDouble() * 25.0D;
                    offsetDeg = (this.random.nextBoolean() ? 1 : -1) * (110.0D + this.random.nextDouble() * 70.0D);
                }
            }
            double angle = baseAngle + Math.toRadians(offsetDeg);
            double x = player.getX() + Math.cos(angle) * distance;
            double z = player.getZ() + Math.sin(angle) * distance;
            double y = this.findGround(x, z, player.getY());
            if (Double.isNaN(y)) {
                continue;
            }
            float yaw = (float) (Mth.atan2(player.getZ() - z, player.getX() - x) * 57.29577951308232D) - 90.0F;
            this.moveTo(x, y, z, yaw, 0.0F);
            this.yBodyRot = yaw;
            this.yHeadRot = yaw;
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
            this.fallDistance = 0.0F;
            this.remembered = player.getEyePosition();
            this.action = ACTION_NONE;
            this.walkTicks = 0;
            this.entityData.set(DATA_ACTION, ACTION_NONE);
            this.entityData.set(DATA_PROGRESS, 0.0F);
            if (kind == TeleportKind.CLOSE) {
                this.lungeCooldown = 0;
                this.pendingLunge = 12 + this.random.nextInt(28);
            }
            return true;
        }
        return false;
    }

    /** Finds a standing spot (4 blocks of free space, solid floor, no liquid) near the player's height. */
    private double findGround(double x, double z, double py) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(py);
        for (int i = 0; i <= 16; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -((i + 1) / 2);
            BlockPos pos = new BlockPos(ix, iy + dy, iz);
            if (!this.level.hasChunkAt(pos)) {
                return Double.NaN;
            }
            BlockPos below = pos.below();
            BlockState floor = this.level.getBlockState(below);
            if (floor.getCollisionShape(this.level, below).isEmpty()) {
                continue;
            }
            AABB box = new AABB(x - 0.45D, pos.getY(), z - 0.45D, x + 0.45D, pos.getY() + 4.0D, z + 0.45D);
            if (this.level.noCollision(this, box) && !this.level.containsAnyLiquid(box)) {
                return pos.getY();
            }
        }
        return Double.NaN;
    }

    // ------------------------------------------------------------------ perception
    private boolean isBehind(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) {
            return false;
        }
        return flatLook.normalize().dot(toMe.normalize()) < -0.25D;
    }

    /** True when any of 4 points along his body is inside the player's view cone and not hidden behind blocks. */
    private boolean isSeenBy(Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 view = player.getViewVector(1.0F).normalize();
        for (double h : new double[]{3.6D, 2.6D, 1.4D, 0.4D}) {
            Vec3 p = new Vec3(this.getX(), this.getY() + h, this.getZ());
            Vec3 to = p.subtract(eye);
            double len = to.length();
            if (len < 0.5D) {
                return true;
            }
            if (view.dot(to.scale(1.0D / len)) < SEEN_COS) {
                continue;
            }
            BlockHitResult hit = this.level.clip(
                    new ClipContext(eye, p, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(p) < 0.25D) {
                return true;
            }
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
    }
}
