package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Slenderman model.
 *
 * Units: 1 model unit = 1/128 block (the renderer scales the model by 0.125 on top of the vanilla 1/16).
 * Total height is exactly 512 units = 4 blocks:
 *   legs 200 + jacket 196 (above the hip) + neck 40 + cubic head 76 = 512.
 * The ground is at y = +24, the top of the head is at y = -488.
 */
public class SlendermanModel extends EntityModel<SlendermanEntity> {
    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(new ResourceLocation("slenderman", "slenderman"), "main");

    private static final float DEG = 0.017453292F;

    // ---- resting pose ----
    /** elbows are slightly bent when he just stands (radians, about 16 degrees). */
    private static final float REST_ELBOW = -0.28F;

    // ---- reach (the sharp screamer pose, 1 s) ----
    /** how far the torso bends forward at the belly/waist while he reaches (radians, about 17 degrees). */
    private static final float REACH_LEAN = 0.30F;
    /** the right arm reaches forward over the PLAYER's head: absolute angle, 0 = hanging, -PI/2 = horizontal forward. */
    private static final float REACH_ARM = -1.5707964F;
    /** the elbow stays slightly bent while reaching. */
    private static final float REACH_ELBOW = -0.20F;
    /** the arm turns a bit inwards (towards the player in front of him). */
    private static final float REACH_AIM_IN = -0.15F;
    /** the head tilts sideways towards the reaching (right) arm; negative = right shoulder side goes down. */
    private static final float REACH_HEAD_TILT = -0.30F;

    // ---- bow (slow, 3 s) ----
    private static final float BOW_LEAN = 0.50F;
    private static final float BOW_HEAD_TILT = 0.28F;

    // ---- tentacles ----
    private static final int TENTACLES = 6;
    private static final int SEGMENTS = 8;
    /** square cross-section of every segment, thick at the root and tapering to the tip. */
    private static final float[] TENT_WIDTH = {30F, 28F, 26F, 24F, 21F, 19F, 16F, 13F};
    private static final float TENT_LEN = 46F;
    /** direction of the root segment per level: > PI/2 = up and back, PI/2 = straight back, < PI/2 = down and back. */
    private static final float[] TENT_PITCH = {1.95F, 1.57F, 1.20F};
    /** how far each level spreads sideways (rad). */
    private static final float[] TENT_YAW = {0.50F, 0.70F, 0.55F};

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart leftThigh;
    private final ModelPart rightThigh;
    private final ModelPart leftShin;
    private final ModelPart rightShin;
    private final ModelPart neck;
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
        this.neck = torso.getChild("neck");
        this.head = neck.getChild("head");
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

        // The arms are made wider with CubeDeformation (grows x/z only, NOT the length) so the texture
        // mapping stays exactly the same as before.  Upper arm 20 -> 27, forearm 18 -> 24.
        CubeDeformation upperGrow = new CubeDeformation(3.5F, 0.0F, 3.5F);
        CubeDeformation foreGrow = new CubeDeformation(3.0F, 0.0F, 3.0F);

        PartDefinition torso = root.addOrReplaceChild("torso",
            CubeListBuilder.create()
                .texOffs(0, 0).addBox(-48.0F, -196.0F, -24.0F, 96.0F, 210.0F, 48.0F)
                .texOffs(121, 259).addBox(-52.0F, -196.0F, -26.0F, 104.0F, 22.0F, 52.0F)
                .texOffs(968, 259).addBox(-5.0F, -190.0F, -27.0F, 10.0F, 16.0F, 3.0F)
                .texOffs(844, 259).addBox(-5.5F, -174.0F, -26.5F, 11.0F, 40.0F, 2.5F)
                .texOffs(995, 259).addBox(-3.0F, -96.0F, -25.0F, 6.0F, 6.0F, 1.5F)
                .texOffs(995, 259).addBox(-3.0F, -56.0F, -25.0F, 6.0F, 6.0F, 1.5F),
            PartPose.offset(0.0F, -176.0F, 0.0F));
        PartDefinition neck = torso.addOrReplaceChild("neck",
            CubeListBuilder.create()
                .texOffs(531, 259).addBox(-20.0F, -14.0F, -18.0F, 40.0F, 14.0F, 36.0F)
                .texOffs(434, 259).addBox(-12.0F, -40.0F, -12.0F, 24.0F, 28.0F, 24.0F),
            PartPose.offset(0.0F, -196.0F, 0.0F));
        PartDefinition head = neck.addOrReplaceChild("head",
            CubeListBuilder.create()
                .texOffs(362, 0).addBox(-38.0F, -76.0F, -38.0F, 76.0F, 76.0F, 76.0F),
            PartPose.offset(0.0F, -40.0F, 0.0F));
        // shoulders sit 4 units further out than before so the wider arm keeps the same gap to the jacket
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm",
            CubeListBuilder.create()
                .texOffs(667, 0).addBox(-10.0F, 0.0F, -10.0F, 20.0F, 125.0F, 20.0F, upperGrow),
            PartPose.offset(66.0F, -186.0F, 0.0F));
        PartDefinition leftForearm = leftArm.addOrReplaceChild("left_forearm",
            CubeListBuilder.create()
                .texOffs(289, 0).addBox(-9.0F, 0.0F, -9.0F, 18.0F, 140.0F, 18.0F, foreGrow),
            PartPose.offset(0.0F, 125.0F, 0.0F));
        PartDefinition leftHand = leftForearm.addOrReplaceChild("left_hand",
            CubeListBuilder.create()
                .texOffs(903, 259).addBox(-8.0F, 0.0F, -8.0F, 16.0F, 22.0F, 16.0F)
                .texOffs(872, 259).addBox(-8.0F, 22.0F, -5.0F, 5.0F, 30.0F, 10.0F)
                .texOffs(813, 259).addBox(-2.5F, 22.0F, -5.0F, 5.0F, 34.0F, 10.0F)
                .texOffs(872, 259).addBox(3.0F, 22.0F, -5.0F, 5.0F, 30.0F, 10.0F),
            PartPose.offset(0.0F, 140.0F, 0.0F));
        PartDefinition rightArm = torso.addOrReplaceChild("right_arm",
            CubeListBuilder.create()
                .texOffs(667, 0).addBox(-10.0F, 0.0F, -10.0F, 20.0F, 125.0F, 20.0F, upperGrow),
            PartPose.offset(-66.0F, -186.0F, 0.0F));
        PartDefinition rightForearm = rightArm.addOrReplaceChild("right_forearm",
            CubeListBuilder.create()
                .texOffs(289, 0).addBox(-9.0F, 0.0F, -9.0F, 18.0F, 140.0F, 18.0F, foreGrow),
            PartPose.offset(0.0F, 125.0F, 0.0F));
        PartDefinition rightHand = rightForearm.addOrReplaceChild("right_hand",
            CubeListBuilder.create()
                .texOffs(903, 259).addBox(-8.0F, 0.0F, -8.0F, 16.0F, 22.0F, 16.0F)
                .texOffs(872, 259).addBox(-8.0F, 22.0F, -5.0F, 5.0F, 30.0F, 10.0F)
                .texOffs(813, 259).addBox(-2.5F, 22.0F, -5.0F, 5.0F, 34.0F, 10.0F)
                .texOffs(872, 259).addBox(3.0F, 22.0F, -5.0F, 5.0F, 30.0F, 10.0F),
            PartPose.offset(0.0F, 140.0F, 0.0F));
        PartDefinition leftThigh = root.addOrReplaceChild("left_thigh",
            CubeListBuilder.create()
                .texOffs(748, 0).addBox(-13.0F, 0.0F, -13.0F, 26.0F, 100.0F, 26.0F),
            PartPose.offset(26.0F, -176.0F, 0.0F));
        PartDefinition leftShin = leftThigh.addOrReplaceChild("left_shin",
            CubeListBuilder.create()
                .texOffs(853, 0).addBox(-12.0F, 0.0F, -12.0F, 24.0F, 92.0F, 24.0F)
                .texOffs(684, 259).addBox(-12.0F, 90.0F, -28.0F, 24.0F, 10.0F, 40.0F),
            PartPose.offset(0.0F, 100.0F, 0.0F));
        PartDefinition rightThigh = root.addOrReplaceChild("right_thigh",
            CubeListBuilder.create()
                .texOffs(748, 0).addBox(-13.0F, 0.0F, -13.0F, 26.0F, 100.0F, 26.0F),
            PartPose.offset(-26.0F, -176.0F, 0.0F));
        PartDefinition rightShin = rightThigh.addOrReplaceChild("right_shin",
            CubeListBuilder.create()
                .texOffs(853, 0).addBox(-12.0F, 0.0F, -12.0F, 24.0F, 92.0F, 24.0F)
                .texOffs(684, 259).addBox(-12.0F, 90.0F, -28.0F, 24.0F, 10.0F, 40.0F),
            PartPose.offset(0.0F, 100.0F, 0.0F));

        addTentacles(torso);
        return LayerDefinition.create(mesh, 1024, 512);
    }

    /**
     * 6 tentacles (3 per side) growing out of the CENTRE of his back (two columns right next to the spine);
     * every one is a chain of 8 thick, tapering segments. The roots sit deep inside the torso, so they come out
     * of the jacket instead of hanging from below.
     */
    private static void addTentacles(PartDefinition torso) {
        for (int i = 0; i < TENTACLES; i++) {
            float side = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            PartPose rootPose = PartPose.offset(side * 7F, -150F + level * 45F, 8F);
            PartDefinition seg = torso.addOrReplaceChild("t" + i, segmentCubes(0), rootPose);
            for (int j = 1; j < SEGMENTS; j++) {
                seg = seg.addOrReplaceChild("s" + j, segmentCubes(j), PartPose.offset(0F, TENT_LEN, 0F));
            }
        }
    }

    private static CubeListBuilder segmentCubes(int j) {
        float w = TENT_WIDTH[j];
        // 4 units longer than the step so the joints overlap and never show gaps while bending
        return CubeListBuilder.create().texOffs(0, 259)
                .addBox(-w / 2F, 0F, -w / 2F, w, TENT_LEN + 4F, w);
    }

    @Override
    public void setupAnim(SlendermanEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float partial = ageInTicks - entity.tickCount;
        int action = entity.getAction();
        // 0..1: the sharp "arm up over the head" screamer pose
        float reach = action == SlendermanEntity.ACTION_LUNGE ? entity.getProgress(partial) : 0F;
        // 0..1: slow bow with a sideways head tilt
        float bow = action == SlendermanEntity.ACTION_PEEK ? entity.getProgress(partial) : 0F;
        float side = entity.getActionSide();
        float tent = entity.getTentacles(partial);

        // ---------- torso: dead still when standing, bends at the waist for reach / bow ----------
        torso.xRot = reach * REACH_LEAN + bow * BOW_LEAN;
        torso.zRot = Mth.cos(limbSwing * 0.55F) * 0.02F * limbSwingAmount;
        torso.yRot = 0F;

        // ---------- head ----------
        float lookPitch = Mth.clamp(headPitch, -40F, 40F) * DEG;
        head.yRot = Mth.clamp(netHeadYaw, -75F, 75F) * DEG;
        // the head cancels the lean of the torso, so it keeps looking where it looked (at the player / level ahead)
        head.xRot = lookPitch - torso.xRot;
        head.zRot = reach * REACH_HEAD_TILT + bow * side * BOW_HEAD_TILT;

        // ---------- legs (only move while he really walks, otherwise limbSwingAmount is 0) ----------
        float swing = Mth.cos(limbSwing * 0.55F) * 0.38F * limbSwingAmount;
        leftThigh.xRot = swing;
        rightThigh.xRot = -swing;
        leftShin.xRot = Math.max(0F, swing) * 0.8F;
        rightShin.xRot = Math.max(0F, -swing) * 0.8F;

        // ---------- arms: relaxed, elbows slightly bent like a person's ----------
        float armSwing = swing * 0.3F;
        leftArm.xRot = -armSwing;
        rightArm.xRot = armSwing;
        leftArm.zRot = -0.04F;
        rightArm.zRot = 0.04F;
        leftForearm.xRot = REST_ELBOW;
        rightForearm.xRot = REST_ELBOW;
        leftHand.xRot = 0.04F;
        rightHand.xRot = 0.04F;

        if (bow > 0F) {
            // arms keep hanging straight down while the torso bows (they cancel the lean)
            leftArm.xRot -= torso.xRot * 0.9F;
            rightArm.xRot -= torso.xRot * 0.9F;
        }

        if (reach > 0F) {
            // The right arm shoots forward and a little down, so the hand ends up above the PLAYER's head
            // (about half the angle he would need to point at the player's eyes). The torso already leans,
            // so the lean is subtracted to get the absolute angle.
            float down = Math.max(lookPitch * 0.5F, 0.05F);
            float target = REACH_ARM + down - REACH_LEAN;
            rightArm.xRot = Mth.lerp(reach, rightArm.xRot, target);
            rightArm.yRot = reach * REACH_AIM_IN;
            rightArm.zRot = Mth.lerp(reach, rightArm.zRot, 0F);
            rightForearm.xRot = Mth.lerp(reach, rightForearm.xRot, REACH_ELBOW);
            rightHand.xRot = Mth.lerp(reach, rightHand.xRot, 0F);
            leftArm.xRot -= torso.xRot * 0.9F;
        } else {
            rightArm.yRot = 0F;
        }

        // ---------- tentacles: they grow out of the middle of his back and writhe slowly in waves ----------
        float grow = Mth.clamp(tent, 0F, 1F);
        float e = grow * grow * (3F - 2F * grow);
        for (int i = 0; i < TENTACLES; i++) {
            float tside = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            float seed = i * 1.93F;

            ModelPart base = tentacles[i][0];
            base.visible = tent > 0.02F;
            // root: swings out of the torso (hanging inside -> pointing back and out) with a slow sway
            base.xRot = TENT_PITCH[level] * e + 0.10F * e * Mth.sin(ageInTicks * 0.050F + seed);
            base.yRot = tside * TENT_YAW[level] * e + 0.08F * e * Mth.sin(ageInTicks * 0.041F + seed * 0.7F);
            base.zRot = 0F;

            for (int j = 1; j < SEGMENTS; j++) {
                ModelPart seg = tentacles[i][j];
                // the chain comes out segment by segment (a hidden segment hides everything after it)
                seg.visible = e > (float) j / (float) SEGMENTS;
                // a travelling wave: every segment follows the one before it a little later
                float wave = Mth.sin(ageInTicks * 0.070F - j * 0.65F + seed);
                float sway = Mth.sin(ageInTicks * 0.055F - j * 0.55F + seed * 1.7F + 1.3F);
                float droop = -(0.06F + 0.015F * j);          // gravity: the chain sags towards the tip
                float curl = (1F - e) * 0.9F;                  // freshly grown segments are still curled up
                seg.xRot = e * (droop + 0.16F * wave) + curl;   // up/down
                seg.zRot = e * 0.16F * sway;                    // sideways
                seg.yRot = 0F;
            }
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                               float r, float g, float b, float a) {
        root.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
