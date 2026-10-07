package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
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

    @Override
    public ResourceLocation getTextureLocation(SlendermanEntity entity) {
        return TEXTURE;
    }
}
