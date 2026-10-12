package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkHooks;

/**
 * One sheet of paper stuck to a block surface (floor, wall or ceiling).
 *
 * The entity stands exactly on the surface (its position is the CENTRE of the sheet, a hair in front of the
 * block face). Three values are synced to the client:
 *  - page: which of the 8 handwritten pages it shows (1..8);
 *  - face: which way the sheet looks (the side of the block it is stuck to);
 *  - roll: the tilt of the sheet around its own axis, in degrees (the small random angle).
 *
 * Right click = the player collects it (see SlenderPages.collect). Creative players can also break it with a left click.
 */
public class SlenderPageEntity extends Entity {
    private static final EntityDataAccessor<Integer> DATA_PAGE = SynchedEntityData.defineId(SlenderPageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_FACE = SynchedEntityData.defineId(SlenderPageEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_ROLL = SynchedEntityData.defineId(SlenderPageEntity.class, EntityDataSerializers.FLOAT);

    /** how far the centre of the sheet floats in front of the block face (blocks): no flickering with the block. */
    public static final double OFFSET = 0.015D;
    /** the random tilt of a placed sheet is between -MAX_TILT and +MAX_TILT degrees. */
    public static final float MAX_TILT = 20.0F;
    /** half of the size of the clickable area along the surface (blocks). */
    private static final double HALF_PICK = 0.30D;
    /** half of the thickness of the clickable area (blocks). */
    private static final double HALF_THICK = 0.03D;

    private int supportCheck;
    /** the side used for the click box. It is null while the Entity constructor runs (then SOUTH is used). */
    private Direction boxFace;

    public SlenderPageEntity(EntityType<? extends SlenderPageEntity> type, Level level) {
        super(type, level);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(DATA_PAGE, 1);
        this.entityData.define(DATA_FACE, Direction.SOUTH.get3DDataValue());
        this.entityData.define(DATA_ROLL, 0.0F);
    }

    /** sets page number, side of the block and tilt. Call it BEFORE setPos(), so the box is built for the right side. */
    public void setup(int page, Direction face, float roll) {
        this.entityData.set(DATA_PAGE, Mth.clamp(page, 1, SlenderPages.TOTAL));
        this.entityData.set(DATA_FACE, face.get3DDataValue());
        this.entityData.set(DATA_ROLL, roll);
        this.boxFace = face;
        this.setBoundingBox(this.makeBox());
    }

    public int getPage() { return this.entityData.get(DATA_PAGE); }

    public Direction getFace() { return Direction.from3DDataValue(this.entityData.get(DATA_FACE)); }

    public float getRoll() { return this.entityData.get(DATA_ROLL); }

    /** a thin flat box around the centre of the sheet: only the pointing / clicking uses it. */
    private AABB makeBox() {
        double hx = HALF_PICK;
        double hy = HALF_PICK;
        double hz = HALF_PICK;
        Direction side = this.boxFace == null ? Direction.SOUTH : this.boxFace;
        switch (side.getAxis()) {
            case X -> hx = HALF_THICK;
            case Y -> hy = HALF_THICK;
            default -> hz = HALF_THICK;
        }
        return new AABB(this.getX() - hx, this.getY() - hy, this.getZ() - hz,
                this.getX() + hx, this.getY() + hy, this.getZ() + hz);
    }

    @Override
    public void setPos(double x, double y, double z) {
        super.setPos(x, y, z);
        this.setBoundingBox(this.makeBox());
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (DATA_FACE.equals(key)) {
            this.boxFace = this.getFace();
            this.setBoundingBox(this.makeBox());
        }
    }

    /** a page can be seen from far away (up to 64 blocks), the way you spot it with the flashlight. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) { return distance < 64.0D * 64.0D; }

    @Override
    public boolean isPickable() { return !this.isRemoved(); }

    private boolean isSupported() {
        Direction face = this.getFace();
        BlockPos support = new BlockPos(this.getX() - face.getStepX() * 0.5D,
                this.getY() - face.getStepY() * 0.5D,
                this.getZ() - face.getStepZ() * 0.5D);
        return !this.level.getBlockState(support).getCollisionShape(this.level, support).isEmpty();
    }

    @Override
    public void tick() {
        // once a second: if the block behind the sheet is gone, the sheet falls off and drops as an item
        if (!this.level.isClientSide && ++this.supportCheck >= 20) {
            this.supportCheck = 0;
            if (!this.isSupported()) {
                this.spawnAtLocation(SlendermanMod.PAGE_ITEMS.get(this.getPage() - 1).get());
                this.discard();
            }
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level.isClientSide && player instanceof ServerPlayer serverPlayer && !this.isRemoved()) {
            SlenderPages.collect(serverPlayer);
            this.discard();
        }
        return InteractionResult.sidedSuccess(this.level.isClientSide);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isRemoved()) return false;
        // creative mode: a left click simply takes the sheet away (no counting), handy while building the map
        if (!this.level.isClientSide && source.getEntity() instanceof Player player && player.getAbilities().instabuild) {
            this.discard();
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_PAGE, Mth.clamp(tag.getInt("Page"), 1, SlenderPages.TOTAL));
        this.entityData.set(DATA_FACE, tag.getInt("Face"));
        this.entityData.set(DATA_ROLL, tag.getFloat("Roll"));
        this.boxFace = this.getFace();
        this.setBoundingBox(this.makeBox());
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Page", this.getPage());
        tag.putInt("Face", this.getFace().get3DDataValue());
        tag.putFloat("Roll", this.getRoll());
    }

    @Override
    public Packet<?> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
