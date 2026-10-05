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

    // ---- attack pose (see SlendermanEntity: LUNGE_TICKS / REACH_TICKS) ----
    /** how far the torso bends forward at the hips while he leans in (radians, about 30 degrees). */
    private static final float LUNGE_LEAN = 0.52F;
    /** extra downward head tilt on top of looking at the player (radians, about 17 degrees). */
    private static final float LUNGE_HEAD_TILT = 0.30F;

    // ---- tentacles ----
    private static final int TENTACLES = 6;
    private static final int SEGMENTS = 8;
    /** square cross-section of every segment, thick at the root and tapering to the tip. */
    private static final float[] TENT_WIDTH = {30F, 28F, 26F, 24F, 21F, 19F, 16F, 13F};
    private static final float TENT_LEN = 46F;
    /** how far each pair of tentacles spreads outwards (rad). Upper pair rises, lower pair is almost level. */
    private static final float[] TENT_SPREAD = {2.35F, 2.05F, 1.75F};

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
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm",
            CubeListBuilder.create()
                .texOffs(667, 0).addBox(-10.0F, 0.0F, -10.0F, 20.0F, 125.0F, 20.0F),
            PartPose.offset(62.0F, -186.0F, 0.0F));
        PartDefinition leftForearm = leftArm.addOrReplaceChild("left_forearm",
            CubeListBuilder.create()
                .texOffs(289, 0).addBox(-9.0F, 0.0F, -9.0F, 18.0F, 140.0F, 18.0F),
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
                .texOffs(667, 0).addBox(-10.0F, 0.0F, -10.0F, 20.0F, 125.0F, 20.0F),
            PartPose.offset(-62.0F, -186.0F, 0.0F));
        PartDefinition rightForearm = rightArm.addOrReplaceChild("right_forearm",
            CubeListBuilder.create()
                .texOffs(289, 0).addBox(-9.0F, 0.0F, -9.0F, 18.0F, 140.0F, 18.0F),
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

    /** 6 tentacles (3 per side) growing out of his back; every one is a chain of 8 thick, tapering segments. */
    private static void addTentacles(PartDefinition torso) {
        for (int i = 0; i < TENTACLES; i++) {
            float side = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            // root sits just inside the back of the jacket (back surface is z = +24)
            PartPose rootPose = PartPose.offset(side * (30F + level * 6F), -170F + level * 52F, 18F);
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
        // 0..1: how far into the close-up "lean in and reach for your head" pose he is
        float reach = action == SlendermanEntity.ACTION_LUNGE ? entity.getProgress(partial) : 0F;
        // 0..1: slow bow when he peeks from behind the player
        float peek = action == SlendermanEntity.ACTION_PEEK ? entity.getProgress(partial) : 0F;
        float tent = entity.getTentacles(partial);

        // ---------- head ----------
        float lookPitch = Mth.clamp(headPitch, -40F, 40F) * DEG;
        head.yRot = Mth.clamp(netHeadYaw, -75F, 75F) * DEG;
        // while leaning in, the torso already tilts the head forward, so the head only adds a slight extra nod
        head.xRot = lookPitch + peek * 0.22F
                + reach * (LUNGE_HEAD_TILT - LUNGE_LEAN - 0.25F * lookPitch);
        head.zRot = 0F;

        // ---------- legs (only move while he really walks, otherwise limbSwingAmount is 0) ----------
        float swing = Mth.cos(limbSwing * 0.55F) * 0.38F * limbSwingAmount;
        leftThigh.xRot = swing;
        rightThigh.xRot = -swing;
        leftShin.xRot = Math.max(0F, swing) * 0.8F;
        rightShin.xRot = Math.max(0F, -swing) * 0.8F;

        // ---------- torso: dead still when standing, bends at the hips when he leans in ----------
        torso.xRot = peek * 0.32F + reach * LUNGE_LEAN;
        torso.zRot = Mth.cos(limbSwing * 0.55F) * 0.02F * limbSwingAmount;
        torso.yRot = 0F;

        // ---------- arms ----------
        float armSwing = swing * 0.3F;
        leftArm.xRot = -armSwing - reach * 0.35F;
        rightArm.xRot = armSwing;
        leftArm.zRot = -0.04F;
        rightArm.zRot = 0.04F;
        leftForearm.xRot = -0.05F - reach * 0.10F;
        rightForearm.xRot = -0.05F;
        leftHand.xRot = 0.04F;
        rightHand.xRot = 0.04F;

        if (reach > 0F) {
            // Right arm shoots out straight at the player's head: absolute pitch is "horizontal, then down by the
            // angle to the player's eyes". The torso already leans, so subtract its lean from the arm.
            float down = Math.max(lookPitch, 0.15F) * 0.95F;
            float target = -1.5707964F + down - LUNGE_LEAN;
            rightArm.xRot = Mth.lerp(reach, rightArm.xRot, target);
            rightArm.zRot = Mth.lerp(reach, rightArm.zRot, 0F);
            rightForearm.xRot = Mth.lerp(reach, rightForearm.xRot, 0F);
            rightHand.xRot = Mth.lerp(reach, rightHand.xRot, 0F);
        }

        // ---------- tentacles: slow, heavy, travelling waves ----------
        float grow = Mth.clamp(tent, 0F, 1F);
        float e = grow * grow * (3F - 2F * grow);
        for (int i = 0; i < TENTACLES; i++) {
            float side = (i % 2 == 0) ? 1F : -1F;
            int level = i / 2;
            float seed = i * 1.93F;
            ModelPart base = tentacles[i][0];
            base.visible = tent > 0.02F;

            // root segment: lifts out of the hanging position into its spread pose, with a slow breathing sway
            base.zRot = -side * (TENT_SPREAD[level] * e) + 0.09F * e * Mth.sin(ageInTicks * 0.031F + seed);
            base.xRot = 0.55F * e + 0.06F * e * Mth.sin(ageInTicks * 0.027F + seed * 1.3F);
            base.yRot = side * 0.10F * e * Mth.sin(ageInTicks * 0.022F + seed * 0.7F);

            for (int j = 1; j < SEGMENTS; j++) {
                ModelPart seg = tentacles[i][j];
                float lag = j * 0.62F;                      // each segment follows the one before it
                float amp = (0.55F + 0.13F * j) * e;        // movement grows towards the tip
                // gravity makes the chain curl downwards; the wave rides on top of that curl
                float droop = side * (0.10F + 0.012F * j) * e;
                float wave = 0.12F * Mth.sin(ageInTicks * 0.055F + seed + lag)
                        + 0.05F * Mth.sin(ageInTicks * 0.031F + seed * 1.7F + lag * 0.6F);
                seg.zRot = droop + wave * amp;
                seg.xRot = 0.10F * amp * Mth.sin(ageInTicks * 0.047F + seed * 0.8F + lag * 0.9F);
                seg.yRot = 0.07F * amp * Mth.sin(ageInTicks * 0.038F + seed + lag * 0.5F);
            }
        }
    }

    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlay,
                               float r, float g, float b, float a) {
        root.render(pose, buffer, light, overlay, r, g, b, a);
    }
}
