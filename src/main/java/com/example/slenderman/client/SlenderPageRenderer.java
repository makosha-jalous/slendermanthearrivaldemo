package com.example.slenderman.client;

import com.example.slenderman.SlenderPageEntity;
import com.example.slenderman.SlenderPages;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Matrix3f;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector3f;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Draws one flat sheet of paper (a single quad). The sheet is 0.54 x 0.75 blocks, so even tilted it stays
 * inside the block face it is stuck to. Textures: assets/slenderman/textures/entity/slender_page_1..8.png
 */
public class SlenderPageRenderer extends EntityRenderer<SlenderPageEntity> {
    private static final ResourceLocation[] TEXTURES = new ResourceLocation[SlenderPages.TOTAL];

    static {
        for (int i = 0; i < TEXTURES.length; i++) {
            TEXTURES[i] = new ResourceLocation(SlendermanMod.ID, "textures/entity/slender_page_" + (i + 1) + ".png");
        }
    }

    private static final float HALF_W = 0.27F;
    private static final float HALF_H = 0.375F;

    public SlenderPageRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(SlenderPageEntity entity) {
        return TEXTURES[Mth.clamp(entity.getPage() - 1, 0, TEXTURES.length - 1)];
    }

    @Override
    public void render(SlenderPageEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        // light of the air block right in front of the sheet (works on floors, walls and ceilings alike)
        BlockPos front = new BlockPos(entity.getX(), entity.getY(), entity.getZ());
        int light = LevelRenderer.getLightColor(entity.level, front);

        poseStack.pushPose();

        // the quad is built facing +Z (south); turn it so that it faces the side of the block it is stuck to
        Direction face = entity.getFace();
        switch (face) {
            case UP -> poseStack.mulPose(Vector3f.XP.rotationDegrees(-90.0F));
            case DOWN -> poseStack.mulPose(Vector3f.XP.rotationDegrees(90.0F));
            default -> poseStack.mulPose(Vector3f.YP.rotationDegrees(-face.toYRot()));
        }
        // the small random tilt of the sheet around its own axis
        poseStack.mulPose(Vector3f.ZP.rotationDegrees(entity.getRoll()));

        PoseStack.Pose last = poseStack.last();
        Matrix4f pose = last.pose();
        Matrix3f normal = last.normal();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutoutNoCull(this.getTextureLocation(entity)));

        vertex(consumer, pose, normal, -HALF_W, -HALF_H, 0.0F, 1.0F, light);
        vertex(consumer, pose, normal, HALF_W, -HALF_H, 1.0F, 1.0F, light);
        vertex(consumer, pose, normal, HALF_W, HALF_H, 1.0F, 0.0F, light);
        vertex(consumer, pose, normal, -HALF_W, HALF_H, 0.0F, 0.0F, light);

        poseStack.popPose();
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
                               float x, float y, float u, float v, int light) {
        consumer.vertex(pose, x, y, 0.0F)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
