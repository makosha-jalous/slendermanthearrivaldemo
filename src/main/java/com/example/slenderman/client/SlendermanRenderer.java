package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;

/** The new model is built in normal 1/16-block units, so there is no extra scaling here any more. */
public class SlendermanRenderer extends MobRenderer<SlendermanEntity, SlendermanModel> {
    private static final ResourceLocation TEXTURE =
            new ResourceLocation("slenderman", "textures/entity/slenderman.png");

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
    public ResourceLocation getTextureLocation(SlendermanEntity entity) {
        return TEXTURE;
    }
}
