package com.example.slenderman;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * One of the 8 pages as an item. Right click on a block surface (floor, wall, ceiling) sticks it there as a
 * SlenderPageEntity with a small random tilt. The name is written here in code, so no lang file is needed.
 */
public class SlenderPageItem extends Item {
    private final int page;

    public SlenderPageItem(int page, Properties properties) {
        super(properties);
        this.page = page;
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.literal("Page " + this.page);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        Player player = context.getPlayer();

        // only on a full, solid side of a block
        BlockState state = level.getBlockState(clicked);
        if (!state.isFaceSturdy(level, clicked, face)) {
            return InteractionResult.FAIL;
        }

        // centre of the sheet: the middle of the clicked side, a hair in front of it
        double dist = 0.5D + SlenderPageEntity.OFFSET;
        double x = clicked.getX() + 0.5D + face.getStepX() * dist;
        double y = clicked.getY() + 0.5D + face.getStepY() * dist;
        double z = clicked.getZ() + 0.5D + face.getStepZ() * dist;

        // one sheet per spot
        AABB spot = new AABB(x - 0.35D, y - 0.35D, z - 0.35D, x + 0.35D, y + 0.35D, z + 0.35D);
        if (!level.getEntitiesOfClass(SlenderPageEntity.class, spot).isEmpty()) {
            return InteractionResult.FAIL;
        }

        if (!level.isClientSide) {
            float tilt = (level.random.nextFloat() * 2.0F - 1.0F) * SlenderPageEntity.MAX_TILT;
            float yaw = player != null ? player.getYRot() : 0.0F;
            // wall: the top of the text points up. Floor: it points away from the player.
            // Ceiling: it points behind the player (that is how a head tilted back sees it).
            float base = 0.0F;
            if (face == Direction.UP) base = 180.0F - yaw;
            else if (face == Direction.DOWN) base = yaw + 180.0F;

            SlenderPageEntity sheet = new SlenderPageEntity(SlendermanMod.PAGE.get(), level);
            sheet.setup(this.page, face, base + tilt);
            sheet.setPos(x, y, z);
            level.addFreshEntity(sheet);

            if (player == null || !player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
