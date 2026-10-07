package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Slenderman model, "classic blocky" version (as in the reference screenshots).
 *
 * Units: 1 model unit = 1/16 block (normal Minecraft / Blockbench units), so NO extra scale in the renderer.
 * Total height = 64 units = 4 blocks:  legs 28 + torso 26 + square head 10.  There is no neck.
 * The ground is at y = +24 (vanilla convention), the top of the head is at y = -40.
 * Texture: 128 x 128 (1 texel = 1 unit).
 *
 * createBodyLayer() contains only plain numbers on purpose, so Blockbench can import this file
 * (File > Import > Import Java Entity) and export it back after editing.
 */
public class SlendermanModel extends EntityModel<SlendermanEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(new ResourceLocation("slenderman", "slenderman"), "main");

    private static final float DEG = 0.017453292F;

    // ---- attack pose (see SlendermanEntity: LUNGE_TICKS / REACH_TICKS) ----
    /** how far the torso bends forward at the hips while he leans in (radians, about 30 degrees). */
    private static final float LUNGE_LEAN = 0.52F;
    /** extra downward head tilt on top of the torso lean (radians, about 17 degrees). */
    private static final float LUNGE_HEAD_TILT = 0.30F;

    // ---- tentacles: 6 pieces, every one is a chain of 4 thin square segments with angular bends ----
    private static final int TENTACLES = 6;
    private static final int SEGMENTS = 4;
    /** how far each pair of tentacles spreads outwards (rad). Upper pair rises, lower pair is almost level. */
    private static final float[] TENT_SPREAD = {2.45F, 2.05F, 1.70F};
    /** fixed elbow bend of every segment relative to the previous one (rad, mirrored per side). */
    private static final float[] TENT_BEND = {0.0F, 0.95F, -0.85F, 0.75F};

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart leftThigh;
    private final ModelPart rightThigh;
    private final ModelPart leftShin;
    private final ModelPart rightShin;
    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart leftForearm;
    private final ModelPart leftHand;
    private final ModelPart rightArm;
    private final ModelPart rightForearm;
    private final ModelPart rightHand;
    private final ModelPart[][] tentacles = new ModelPart[TENTACLES][SEGMENTS];

    public SlendermanModel(ModelPart root) {
        this.root = root;
        this.leftThigh = root.getChild("left_thigh");
        this.rightThigh = root.getChild("right_thigh");
        this.leftShin = leftThigh.getChild("left_shin");
        this.rightShin = rightThigh.getChild("right_shin");
        this.torso = root.getChild("torso");
        this.head = torso.getChild("head");
        this.leftArm = torso.getChild("left_arm");
        this.leftForearm = leftArm.getChild("left_forearm");
        this.leftHand = leftForearm.getChild("left_hand");
        this.rightArm = torso.getChild("right_arm");
        this.rightForearm = rightArm.getChild("right_forearm");
        this.rightHand = rightForearm.getChild("right_hand");
        for (int i = 0; i < TENTACLES; i++) {
            ModelPart seg = torso.getChild("t" + i);
            tentacles[i][0] = seg;
            for (int j = 1; j < SEGMENTS; j++) {
                seg = seg.getChild("s" + j);
                tentacles[i][j] = seg;
            }
        }
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();

        // ---- torso: 12 wide, 6 deep, 26 tall (+2 below the hip so the joint never shows a gap) ----
        PartDefinition torso = root.addOrReplaceChild("torso",
            CubeListBuilder.create().texOffs(0, 0).addBox(-6.0F, -26.0F, -3.0F, 12.0F, 28.0F, 6.0F),
            PartPose.offset(0.0F, -4.0F, 0.0F));

        // ---- head: a plain 10x10x10 cube sitting directly on the shoulders ----
        PartDefinition head = torso.addOrReplaceChild("head",
            CubeListBuilder.create().texOffs(40, 0).addBox(-5.0F, -10.0F, -5.0F, 10.0F, 10.0F, 10.0F),
            PartPose.offset(0.0F, -26.0F, 0.0F));

        // ---- left arm (x+): upper arm, forearm, square hand. Hands end below the knees ----
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm",
            CubeListBuilder.create().texOffs(0, 40).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 20.0F, 4.0F),
            PartPose.offset(8.0F, -24.0F, 0.0F));
        PartDefinition leftForearm = leftArm.addOrReplaceChild("left_forearm",
            CubeListBuilder.create().texOffs(16, 40).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 20.0F, 4.0F),
            PartPose.offset(0.0F, 18.0F, 0.0F));
        PartDefinition leftHand = leftForearm.addOrReplaceChild("left_hand",
            CubeListBuilder.create().texOffs(32, 40).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 5.0F, 5.0F),
            PartPose.offset(0.0F, 20.0F, 0.0F));

        // ---- right arm (x-) ----
        PartDefinition rightArm = torso.addOrReplaceChild("right_arm",
            CubeListBuilder.create().texOffs(0, 40).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 20.0F, 4.0F),
            PartPose.offset(-8.0F, -24.0F, 0.0F));
        PartDefinition rightForearm = rightArm.addOrReplaceChild("right_forearm",
            CubeListBuilder.create().texOffs(16, 40).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 20.0F, 4.0F),
            PartPose.offset(0.0F, 18.0F, 0.0F));
        PartDefinition rightHand = rightForearm.addOrReplaceChild("right_hand",
            CubeListBuilder.create().texOffs(32, 40).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 5.0F, 5.0F),
            PartPose.offset(0.0F, 20.0F, 0.0F));

        // ---- legs: 5x5 thigh + 5x5 shin, 28 tall in total ----
        PartDefinition leftThigh = root.addOrReplaceChild("left_thigh",
            CubeListBuilder.create().texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 14.0F, 5.0F),
            PartPose.offset(3.0F, -4.0F, 0.0F));
        PartDefinition leftShin = leftThigh.addOrReplaceChild("left_shin",
            CubeListBuilder.create().texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 14.0F, 5.0F),
            PartPose.offset(0.0F, 14.0F, 0.0F));
        PartDefinition rightThigh = root.addOrReplaceChild("right_thigh",
            CubeListBuilder.create().texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 14.0F, 5.0F),
            PartPose.offset(-3.0F, -4.0F, 0.0F));
        PartDefinition rightShin = rightThigh.addOrReplaceChild("right_shin",
            CubeListBuilder.create().texOffs(0, 70).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 14.0F, 5.0F),
            PartPose.offset(0.0F, 14.0F, 0.0F));

        // ---- 6 tentacles on his back (t0..t5), 4 thin segments each (s1..s3 are children of the previous one) ----
        PartDefinition t0 = torso.addOrReplaceChild("t0",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(3.0F, -22.0F, 1.5F));
        PartDefinition t0s1 = t0.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t0s2 = t0s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t0s3 = t0s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t1 = torso.addOrReplaceChild("t1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(-3.0F, -22.0F, 1.5F));
        PartDefinition t1s1 = t1.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t1s2 = t1s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t1s3 = t1s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t2 = torso.addOrReplaceChild("t2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(3.0F, -14.0F, 1.5F));
        PartDefinition t2s1 = t2.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t2s2 = t2s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t2s3 = t2s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t3 = torso.addOrReplaceChild("t3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(-3.0F, -14.0F, 1.5F));
        PartDefinition t3s1 = t3.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t3s2 = t3s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t3s3 = t3s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t4 = torso.addOrReplaceChild("t4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(3.0F, -6.0F, 1.5F));
        PartDefinition t4s1 = t4.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t4s2 = t4s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t4s3 = t4s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t5 = torso.addOrReplaceChild("t5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 14.0F, 3.0F),
            PartPose.offset(-3.0F, -6.0F, 1.5F));
        PartDefinition t5s1 = t5.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 14.0F, 2.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t5s2 = t5s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 14.0F, 2.0F),
            PartPose.offset(0.0F, 13.0F, 0.0F));
        PartDefinition t5s3 = t5s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.75F, 0.0F, -0.75F, 1.5F, 14.0F, 1.5F),
            PartPose.offset(0.0F, 13.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(SlendermanEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float partial = ageInTicks - entity.tickCount;
        int action = entity.getAction();
        // 0..1: how far into the close-up "lean in and reach for your head" pose he is
        float reach = action == SlendermanEntity.ACTION_LUNGE ? entity.getProgress(partial) : 0F;
        // 0..1: slow bow when he peeks from behind the player
        float peek = action == SlendermanEntity.ACTION_PEEK ? entity.getProgress(partial) : 0F;
        float tent = entity.getTentacles(partial);
        // only used to point the right arm at the player's eyes during the lunge
        float aim = Mth.clamp(headPitch, -40F, 40F) * DEG;

        // ---------- head: always looks straight ahead, never turns towards the player ----------
        head.yRot = 0F;
        head.zRot = 0F;
        head.xRot = peek * 0.22F + reach * (LUNGE_HEAD_TILT - LUNGE_LEAN);

        // ---------- legs: he never walks, so they stay dead straight ----------
        leftThigh.xRot = 0F;
        rightThigh.xRot = 0F;
        leftShin.xRot = 0F;
        rightShin.xRot = 0F;

        // ---------- torso: dead still when standing, bends at the hips when he leans in ----------
        torso.xRot = peek * 0.32F + reach * LUNGE_LEAN;
        torso.yRot = 0F;
        torso.zRot = 0F;

        // ---------- arms: straight down, a hair away from the body ----------
        leftArm.xRot = -reach * 0.35F;
        rightArm.xRot = 0F;
        leftArm.zRot = -0.03F;
        rightArm.zRot = 0.03F;
        leftForearm.xRot = -0.03F - reach * 0.10F;
        rightForearm.xRot = -0.03F;
        leftHand.xRot = 0F;
        rightHand.xRot = 0F;

        if (reach > 0F) {
            // Right arm shoots out straight at the player's head: absolute pitch is "horizontal, then down by the
            // angle to the player's eyes". The torso already leans, so subtract its lean from the arm.
            float down = Math.max(aim, 0.15F) * 0.95F;
            float target = -1.5707964F + down - LUNGE_LEAN;
            rightArm.xRot = Mth.lerp(reach, rightArm.xRot, target);
            rightArm.zRot = Mth.lerp(reach, rightArm.zRot, 0F);
            rightForearm.xRot = Mth.lerp(reach, rightForearm.xRot, 0F);
            rightHand.xRot = Mth.lerp(reach, rightHand.xRot, 0F);
        }

        // ---------- tentacles: spread out with angular elbows, slow heavy idle sway ----------
        float grow = Mth.clamp(tent, 0F, 1F);
        float e = grow * grow * (3F - 2F * grow);
        for (int i = 0; i < TENTACLES; i++) {
            float side = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            float seed = i * 1.93F;
            float bendScale = 1.0F + 0.18F * Mth.sin(seed * 2.1F);
            ModelPart base = tentacles[i][0];
            base.visible = tent > 0.02F;

            base.zRot = -side * (TENT_SPREAD[level] * e) + 0.06F * e * Mth.sin(ageInTicks * 0.031F + seed);
            base.xRot = 0.55F * e + 0.05F * e * Mth.sin(ageInTicks * 0.027F + seed * 1.3F);
            base.yRot = side * 0.08F * e * Mth.sin(ageInTicks * 0.022F + seed * 0.7F);

            for (int j = 1; j < SEGMENTS; j++) {
                ModelPart seg = tentacles[i][j];
                float lag = j * 0.62F;
                float wave = 0.06F * (1.0F + 0.5F * j) * Mth.sin(ageInTicks * 0.05F + seed + lag);
                seg.zRot = side * TENT_BEND[j] * bendScale * e + wave * e;
                seg.xRot = 0.05F * j * e * Mth.sin(ageInTicks * 0.04F + seed * 0.8F + lag);
                seg.yRot = 0.04F * j * e * Mth.sin(ageInTicks * 0.035F + seed + lag * 0.5F);
            }
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                               float r, float g, float b, float a) {
        root.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
