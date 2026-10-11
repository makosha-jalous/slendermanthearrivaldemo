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

    // ---- resting pose ----
    /** elbows are slightly bent when he just stands (radians, about 16 degrees). */
    private static final float REST_ELBOW = -0.28F;

    // ---- reach (sharp screamer pose, 1 s) ----
    /** how far the torso bends forward while he reaches (radians, about 17 degrees). */
    private static final float REACH_LEAN = 0.30F;
    /** absolute angle of the right arm: 0 = hanging, -PI/2 = horizontal forward. */
    private static final float REACH_ARM = -1.5707964F;
    /** the elbow stays slightly bent while reaching. */
    private static final float REACH_ELBOW = -0.20F;
    /** the arm turns a bit inwards (towards the player in front of him). */
    private static final float REACH_AIM_IN = -0.15F;
    /** head tilts sideways towards the reaching (right) arm; negative = right side goes down. */
    private static final float REACH_HEAD_TILT = -0.30F;

    // ---- bow (slow, 3 s) ----
    private static final float BOW_LEAN = 0.40F;
    private static final float BOW_HEAD_TILT = 0.28F;

    // ---- tentacles: 6 pieces, every one is a chain of 8 short tapering segments, so it bends smoothly like a snake ----
    private static final int TENTACLES = 6;
    private static final int SEGMENTS = 8;
    /** how far each pair of tentacles spreads outwards (rad). Upper pair rises, lower pair is almost level. */
    private static final float[] TENT_SPREAD = {2.45F, 2.05F, 1.70F};

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

        // The arms are made wider with CubeDeformation: it grows x/z only (not the length) and does NOT change the
        // texture mapping, so the PNG stays exactly as it is. Upper arm 4 -> 6, forearm 4 -> 5.5 (hand is 5).
        CubeDeformation upperGrow = new CubeDeformation(1.0F, 0.0F, 1.0F);
        CubeDeformation foreGrow = new CubeDeformation(0.75F, 0.0F, 0.75F);

        // ---- left arm (x+): upper arm, forearm, square hand. Hands end below the knees ----
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm",
            CubeListBuilder.create().texOffs(0, 40).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 20.0F, 4.0F, upperGrow),
            PartPose.offset(9.0F, -24.0F, 0.0F));
        PartDefinition leftForearm = leftArm.addOrReplaceChild("left_forearm",
            CubeListBuilder.create().texOffs(16, 40).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 20.0F, 4.0F, foreGrow),
            PartPose.offset(0.0F, 18.0F, 0.0F));
        PartDefinition leftHand = leftForearm.addOrReplaceChild("left_hand",
            CubeListBuilder.create().texOffs(32, 40).addBox(-2.5F, 0.0F, -2.5F, 5.0F, 5.0F, 5.0F),
            PartPose.offset(0.0F, 20.0F, 0.0F));

        // ---- right arm (x-) ----
        PartDefinition rightArm = torso.addOrReplaceChild("right_arm",
            CubeListBuilder.create().texOffs(0, 40).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 20.0F, 4.0F, upperGrow),
            PartPose.offset(-9.0F, -24.0F, 0.0F));
        PartDefinition rightForearm = rightArm.addOrReplaceChild("right_forearm",
            CubeListBuilder.create().texOffs(16, 40).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 20.0F, 4.0F, foreGrow),
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

        // ---- 6 tentacles (t0..t5): the roots sit inside the torso at the CENTRE of his back; 8 thin segments each ----
        PartDefinition t0 = torso.addOrReplaceChild("t0",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -20.0F, 0.5F));
        PartDefinition t0s1 = t0.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s2 = t0s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s3 = t0s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s4 = t0s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s5 = t0s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s6 = t0s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t0s7 = t0s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1 = torso.addOrReplaceChild("t1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -20.0F, 0.5F));
        PartDefinition t1s1 = t1.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s2 = t1s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s3 = t1s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s4 = t1s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s5 = t1s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s6 = t1s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t1s7 = t1s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2 = torso.addOrReplaceChild("t2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -13.0F, 0.5F));
        PartDefinition t2s1 = t2.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s2 = t2s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s3 = t2s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s4 = t2s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s5 = t2s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s6 = t2s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t2s7 = t2s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3 = torso.addOrReplaceChild("t3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -13.0F, 0.5F));
        PartDefinition t3s1 = t3.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s2 = t3s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s3 = t3s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s4 = t3s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s5 = t3s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s6 = t3s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t3s7 = t3s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4 = torso.addOrReplaceChild("t4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(1.5F, -6.0F, 0.5F));
        PartDefinition t4s1 = t4.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s2 = t4s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s3 = t4s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s4 = t4s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s5 = t4s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s6 = t4s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t4s7 = t4s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5 = torso.addOrReplaceChild("t5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.7F, 0.0F, -1.7F, 3.4F, 8.0F, 3.4F),
            PartPose.offset(-1.5F, -6.0F, 0.5F));
        PartDefinition t5s1 = t5.addOrReplaceChild("s1",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.6F, 0.0F, -1.6F, 3.2F, 8.0F, 3.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s2 = t5s1.addOrReplaceChild("s2",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 8.0F, 3.0F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s3 = t5s2.addOrReplaceChild("s3",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.4F, 0.0F, -1.4F, 2.8F, 8.0F, 2.8F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s4 = t5s3.addOrReplaceChild("s4",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.25F, 0.0F, -1.25F, 2.5F, 8.0F, 2.5F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s5 = t5s4.addOrReplaceChild("s5",
            CubeListBuilder.create().texOffs(60, 70).addBox(-1.1F, 0.0F, -1.1F, 2.2F, 8.0F, 2.2F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s6 = t5s5.addOrReplaceChild("s6",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.95F, 0.0F, -0.95F, 1.9F, 8.0F, 1.9F),
            PartPose.offset(0.0F, 7.0F, 0.0F));
        PartDefinition t5s7 = t5s6.addOrReplaceChild("s7",
            CubeListBuilder.create().texOffs(60, 70).addBox(-0.8F, 0.0F, -0.8F, 1.6F, 8.0F, 1.6F),
            PartPose.offset(0.0F, 7.0F, 0.0F));

        return LayerDefinition.create(mesh, 128, 128);
    }

    @Override
    public void setupAnim(SlendermanEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        float partial = ageInTicks - entity.tickCount;
        int action = entity.getAction();
        // 0..1: the sharp "arm over the player's head" screamer pose
        float reach = action == SlendermanEntity.ACTION_LUNGE ? entity.getProgress(partial) : 0F;
        // 0..1: slow bow with a sideways head tilt
        float bow = action == SlendermanEntity.ACTION_PEEK ? entity.getProgress(partial) : 0F;
        float side = entity.getActionSide();
        float tent = entity.getTentacles(partial);
        float lookPitch = Mth.clamp(headPitch, -40F, 40F) * DEG;

        // ---------- slow stomping walk (only while he really walks; zero when he stands or acts) ----------
        float amp = Mth.clamp(limbSwingAmount * 1.8F, 0F, 1F) * (1F - Math.max(reach, bow));
        float phase = limbSwing * 0.35F;                          // slow: one full stride takes about 2 s
        float stride = Mth.cos(phase) * amp;                      // -1..1, left leg forward when positive
        float liftL = Math.max(0F, -Mth.sin(phase)) * amp;       // left leg is being lifted (knee bent)
        float liftR = Math.max(0F, Mth.sin(phase)) * amp;        // right leg is being lifted

        // ---------- torso: dead still when standing, bends forward for reach / bow, sways while he walks ----------
        torso.xRot = reach * REACH_LEAN + bow * BOW_LEAN + amp * 0.05F;
        torso.yRot = stride * 0.06F;
        torso.zRot = stride * 0.07F;

        // ---------- head: the torso lean is cancelled, so he keeps looking where he looked ----------
        head.yRot = Mth.clamp(netHeadYaw, -75F, 75F) * DEG;
        head.xRot = lookPitch - torso.xRot;
        head.zRot = reach * REACH_HEAD_TILT + bow * side * BOW_HEAD_TILT - stride * 0.04F;

        // ---------- legs: the knees bend sharply when a foot is lifted, then it stomps down ----------
        leftThigh.xRot = -stride * 0.50F - liftL * 0.30F;
        rightThigh.xRot = stride * 0.50F - liftR * 0.30F;
        leftShin.xRot = liftL * 1.0F;
        rightShin.xRot = liftR * 1.0F;

        // ---------- arms: straight down, a hair away from the body, elbows slightly bent ----------
        // arms swing in turns, opposite to the legs
        leftArm.xRot = stride * 0.30F;
        rightArm.xRot = -stride * 0.30F;
        leftArm.yRot = 0F;
        rightArm.yRot = 0F;
        leftArm.zRot = -0.03F;
        rightArm.zRot = 0.03F;
        leftForearm.xRot = REST_ELBOW - Math.max(0F, -stride) * 0.25F;
        rightForearm.xRot = REST_ELBOW - Math.max(0F, stride) * 0.25F;
        leftHand.xRot = 0F;
        rightHand.xRot = 0F;

        if (bow > 0F) {
            // arms keep hanging straight down while the torso bows
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
            leftArm.xRot -= torso.xRot * 0.9F;
        }

        // ---------- tentacles: they push out of the CENTRE of his back, fan out, then writhe slowly ----------
        float grow = Mth.clamp(tent, 0F, 1F);
        float e = grow * grow * (3F - 2F * grow);
        float emerge = Mth.clamp(e / 0.35F, 0F, 1F);           // phase 1: swing out of the torso, backwards
        float fan = Mth.clamp((e - 0.35F) / 0.65F, 0F, 1F);    // phase 2: fan out sideways and upwards
        for (int i = 0; i < TENTACLES; i++) {
            float tside = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            float seed = i * 1.93F;
            ModelPart base = tentacles[i][0];
            base.visible = tent > 0.02F;

            base.xRot = Mth.lerp(fan, 1.5707964F * emerge, 0.55F) + 0.05F * e * Mth.sin(ageInTicks * 0.040F + seed * 1.3F);
            base.zRot = -tside * (TENT_SPREAD[level] * fan) + 0.06F * e * Mth.sin(ageInTicks * 0.045F + seed);
            base.yRot = tside * 0.08F * e * Mth.sin(ageInTicks * 0.035F + seed * 0.7F);

            for (int j = 1; j < SEGMENTS; j++) {
                ModelPart seg = tentacles[i][j];
                // the chain comes out segment by segment (a hidden segment hides everything after it)
                seg.visible = e > 0.25F + 0.65F * (j - 1) / (SEGMENTS - 1);
                // slow travelling waves in two directions with a phase shift = a soft, snake-like writhing;
                // every segment follows the one before it a little later, the tip moves the most
                float amp = (0.65F + 0.08F * j) * e;
                float wave = Mth.sin(ageInTicks * 0.065F - j * 0.70F + seed);
                float sway = Mth.sin(ageInTicks * 0.050F - j * 0.60F + seed * 1.7F + 1.3F);
                seg.zRot = tside * 0.10F * fan + 0.20F * amp * wave;   // gentle outward arc + side wave
                seg.xRot = 0.16F * amp * sway;                          // up/down wave
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
