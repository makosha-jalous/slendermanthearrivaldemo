package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

public class SlendermanRenderer extends MobRenderer<SlendermanEntity, SlendermanModel> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("slenderman", "textures/entity/slenderman.png");
    /** the model is built in 1/128-block units, so it is scaled by 1/8 on top of vanilla's 1/16. */
    private static final float MODEL_SCALE = 0.125F;

    public SlendermanRenderer(EntityRendererProvider.Context context) {
        super(context, new SlendermanModel(context.bakeLayer(SlendermanModel.LAYER)), 0.6F);
    }

    /** for a few ticks after a teleport he is not drawn at all: he disappears here and appears there, he never glides. */
    @Override
    public void render(SlendermanEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        if (entity.isTeleportHidden()) return;
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }

    @Override
    protected void scale(SlendermanEntity entity, PoseStack poseStack, float partialTick) {
        poseStack.scale(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE);
    }

    @Override
    public ResourceLocation getTextureLocation(SlendermanEntity entity) {
        return TEXTURE;
    }
}
