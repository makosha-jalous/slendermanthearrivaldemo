package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraftforge.common.world.ForgeChunkManager;

public class SlendermanEntity extends PathfinderMob {
    private static final EntityDataAccessor<Float> DATA_TENT = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_ACTION = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_PROGRESS = SynchedEntityData.defineId(SlendermanEntity.class, EntityDataSerializers.FLOAT);

    public static boolean autoTeleport = true;

    private static final double SEEN_COS = 0.55D;
    private static final int STARE_TO_TENTACLES = 110;

    public static final int ACTION_NONE = 0;
    public static final int ACTION_LUNGE = 1;
    public static final int ACTION_PEEK = 2;
    private static final double LUNGE_RANGE = 3.4D;
    private static final double PENDING_LUNGE_RANGE = 3.8D;
    public static final int LUNGE_TICKS = 40;
    public static final int REACH_TICKS = 3;
    private static final int PEEK_TICKS = 70;

    /** he never walks; if the player is farther than this he jumps back to the player (any distance, no limit). */
    private static final double FOLLOW_DISTANCE = 48.0D;
    /** ticks the player has to stay far away before he catches up (2 seconds). */
    private static final int FOLLOW_DELAY = 40;

    private float tentPrev, tentNow, progPrev, progNow;
    private int stare, action, actionTimer, actionDuration;
    private int lungeCooldown = 60;
    private int peekCooldown = 200;
    private int pendingLunge;
    private int tpCooldown = 20 * 10 + this.random.nextInt(20 * 21); // 10-30 sec
    private int vanishCooldown = 20 * 15 + this.random.nextInt(20 * 16); // 15-30 sec
    private int vanishTicks = 0;
    private boolean vanished = false;
    private int observedTicks;
    private float tent, closeness;
    private boolean tentGoal;
    private BlockPos lastPlayerPos;
    private int stillTicks = 0;
    private int farTicks = 0;
    private ChunkPos forcedChunk;

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
    }

    public int getAction() { return this.entityData.get(DATA_ACTION); }

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
                    this.tpCooldown = 20 * 10 + this.random.nextInt(20 * 21);
                } else {
                    this.farTicks = FOLLOW_DELAY - 20; // no free spot found, try again in a second
                }
                return;
            }
        } else {
            this.farTicks = 0;
        }

        BlockPos currentPlayerPos = player.blockPosition();
        if (lastPlayerPos != null && currentPlayerPos.equals(lastPlayerPos)) {
            this.stillTicks++;
        } else {
            this.stillTicks = 0;
            this.lastPlayerPos = currentPlayerPos;
        }
        if (this.stillTicks > 300 && this.action == ACTION_NONE && this.tpCooldown <= 0) {
            this.stillTicks = 0;
            boolean ok = this.teleportNear(player, TeleportKind.CLOSE);
            if (ok) {
                this.tpCooldown = 20 * 10 + this.random.nextInt(20 * 21); // 10-30 sec
                return;
            }
        }

        if (seen && dist < 45.0D) {
            this.stare = Math.min(this.stare + 1, 600);
        } else {
            this.stare = Math.max(0, this.stare - 3);
        }
        if (this.stare > STARE_TO_TENTACLES) this.tentGoal = true;
        else if (this.stare < 30) this.tentGoal = false;
        this.updateTentacles(this.tentGoal);

        this.observedTicks = seen ? this.observedTicks + 1 : 0;
        if (seen) this.closeness = Math.max(0.0F, this.closeness - 0.0005F);
        else this.closeness = Math.min(1.0F, this.closeness + 0.002F);

        if (this.lungeCooldown > 0) this.lungeCooldown--;
        if (this.peekCooldown > 0) this.peekCooldown--;

        if (this.action != ACTION_NONE) {
            this.actionTimer++;
            float progress;
            if (this.action == ACTION_LUNGE) {
                if (this.actionTimer > LUNGE_TICKS) {
                    this.endAction();
                    return;
                }
                float x = Math.min(1.0F, (float) this.actionTimer / (float) REACH_TICKS);
                float inv = 1.0F - x;
                progress = 1.0F - inv * inv * inv;
            } else {
                float frac = (float) this.actionTimer / (float) this.actionDuration;
                float x = Math.min(1.0F, Math.min(frac / 0.25F, (1.0F - frac) / 0.25F));
                progress = x * x * (3.0F - 2.0F * x);
            }
            this.entityData.set(DATA_PROGRESS, progress);
            this.standStill();
            this.faceToward(player.position(), this.action == ACTION_LUNGE ? 28.0F : 12.0F);
            // only the pitch matters here: the model uses it to aim the arm at the player's eyes (the head itself stays straight)
            Vec3 eye = player.getEyePosition();
            this.getLookControl().setLookAt(eye.x, eye.y, eye.z, 30.0F, 40.0F);
            if (this.action == ACTION_PEEK && this.actionTimer >= this.actionDuration) this.endAction();
            return;
        }

        if (this.vanished) {
            this.standStill();
            this.vanishTicks--;
            if (this.vanishTicks <= 0) {
                this.vanished = false;
                this.setInvisible(false);
                this.tpCooldown = 20 * 10 + this.random.nextInt(20 * 21);
            }
            return;
        }

        if (this.tpCooldown > 0) this.tpCooldown--;
        if (this.vanishCooldown > 0) this.vanishCooldown--;

        // Teleport only after the long cooldown expires.
        if (autoTeleport && this.tpCooldown <= 0) {
            this.autoTeleport(player, seen, dist);
            return;
        }

        // Rare disappearance after the player has stared at him for a while.
        if (seen && this.observedTicks > 80 && this.vanishCooldown <= 0
                && this.random.nextFloat() < 0.004F) {
            this.beginVanish();
            return;
        }
        if (this.pendingLunge > 0 && --this.pendingLunge == 0 && dist < PENDING_LUNGE_RANGE) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
            this.lungeCooldown = 140;
            return;
        }
        if (dist < LUNGE_RANGE && this.lungeCooldown == 0 && this.random.nextFloat() < 0.012F * (0.5F + this.closeness)) {
            this.startAction(ACTION_LUNGE, LUNGE_TICKS);
            this.lungeCooldown = 140 + this.random.nextInt(120);
            return;
        }
        if (!seen && this.isBehind(player) && dist > 3.0D && dist < 9.0D && this.peekCooldown == 0 && this.random.nextFloat() < 0.004F) {
            this.startAction(ACTION_PEEK, PEEK_TICKS);
            this.peekCooldown = 400 + this.random.nextInt(300);
            return;
        }

        // ---- idle: dead still, no walking, head straight ahead (does NOT look at the player) ----
        this.standStill();
        this.setYHeadRot(this.yBodyRot);
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
        this.tent += Mth.clamp((growing ? 1.0F : 0.0F) - this.tent, -0.05F, 0.02F);
        this.entityData.set(DATA_TENT, this.tent);
    }

    public enum TeleportKind { FAR, MID, CLOSE, VANISH }

    private void autoTeleport(Player player, boolean seen, double dist) {
        float r = this.random.nextFloat();
        TeleportKind kind;
        if (dist > 70.0D) {
            kind = TeleportKind.MID;
        } else if (seen) {
            if (this.observedTicks > 40 && r < 0.40F) kind = TeleportKind.VANISH;
            else if (this.observedTicks > 40 && r < 0.70F) kind = TeleportKind.CLOSE;
            else if (r < 0.85F) kind = TeleportKind.MID;
            else kind = TeleportKind.FAR;
        } else {
            if (r < 0.3F) kind = TeleportKind.CLOSE;
            else if (r < 0.7F) kind = TeleportKind.MID;
            else kind = TeleportKind.FAR;
        }
        boolean ok = this.teleportNear(player, kind);
        this.tpCooldown = ok ? 20 * 10 + this.random.nextInt(20 * 21) : 20 * 3;
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
            if (kind == TeleportKind.CLOSE) {
                this.lungeCooldown = 0;
                this.pendingLunge = 5 + this.random.nextInt(10);
            }
            this.keepChunkLoaded();
            return true;
        }
        return false;
    }

    private double findGround(double x, double z, double py) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(py);
        for (int dy = -8; dy <= 8; dy++) {
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

    private boolean isBehind(Player player) {
        Vec3 look = player.getLookAngle();
        Vec3 flatLook = new Vec3(look.x, 0.0D, look.z);
        Vec3 toMe = new Vec3(this.getX() - player.getX(), 0.0D, this.getZ() - player.getZ());
        if (flatLook.lengthSqr() < 1.0E-4D || toMe.lengthSqr() < 1.0E-4D) return false;
        return flatLook.normalize().dot(toMe.normalize()) < -0.25D;
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
