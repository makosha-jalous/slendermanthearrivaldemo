package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import com.mojang.math.Vector4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * The "radio wave / heat" distortion in front of Slenderman (CLIENT ONLY), like the child object "Distortion" of the
 * original game:
 *  - a transparent rectangle in front of him that bends everything behind it (and him), pushes the contrast and the
 *    brightness up and burns or washes out the colours;
 *  - every frame there is a 25 % chance that it is on, otherwise it is off (so it flickers);
 *  - it is off when he is hidden or not in the player's view.
 *
 * How it is made: the finished picture of the game is copied, and the screen rectangle around him (his box seen from the
 * camera) is drawn back as a net of small quads that wave sideways, in three colour channels with a small shift, then
 * burned (contrast) or washed out. It needs no shader pack.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class SlenderDistortion {
    // ---------------------------------------------------------------- settings
    /** the chance that the distortion is on in a moment (the original: Random.value < 0.25). */
    private static final float ON_CHANCE = 0.25F;
    /** how often the dice are thrown (ms): 16 = about 60 times a second whatever the FPS is. */
    private static final long ROLL_MS = 16L;
    /** how far the rectangle reaches beyond his body (blocks, to the sides / up and down). */
    private static final double PAD_SIDE = 1.9D;        // much wider than his body, like the plane of the original (was 0.7)
    private static final double PAD_UPDOWN = 0.45D;
    /** the waving sideways (share of the rectangle width). */
    private static final float WAVE = 0.07F;
    /** the shift of the colour channels (share of the rectangle width). */
    private static final float COLOR_SHIFT = 0.018F;
    /** he is not distorted when he is further away than this (blocks). */
    private static final double MAX_DIST = 70.0D;
    private static final int ROWS = 30, COLS = 8;
    /** how wide the soft border is (share of the rectangle on every side): the effect fades out over this distance. */
    private static final float FEATHER = 0.30F;
    /** the net of the soft border. */
    private static final int FI = 12, FJ = 16;
    /** the original shader: strength (0.1..0.3, default 0.2) and transparency (0.01..0.1, default 0.05). */
    private static final float STRENGTH = 0.2F;
    private static final float TRANSPARENCY = 0.05F;
    /** how many times the noise texture repeats over the rectangle (the tiling of the original is not known). */
    private static final float NOISE_TILE = 1.0F;
    /** the net of the heat waves. */
    private static final int HI = 24, HJ = 40;
    /** our extra colours (contrast, burnt, washed out) that are NOT in the original shader: false = the pure original. */
    private static final boolean EXTRA_COLOR = false;

    // ---- chromatic aberration inside the rectangle (always, together with the heat waves)
    /** how far the red and the blue picture are pushed apart (share of the screen width). */
    private static final float CA_SHIFT = 0.0055F;
    /** the aberration is a bit stronger than the plain heat: it is also seen far away. */
    private static final float CA_ALPHA_BOOST = 1.8F;

    // ---- "overexposure" burst when he is close: burnt light, brown blacks (sepia), like flashes together with the distortion
    private static final double EXPOSE_FAR = 9.0D;      // starts at this distance (blocks) ...
    private static final double EXPOSE_NEAR = 4.0D;     // ... and is at its full strength at this distance
    /** how strong (0..1 each): the light is pushed up, the blacks turn brown, the colours burn out. */
    private static final float EXPOSE_LIGHT = 0.55F;
    private static final float EXPOSE_BROWN = 0.40F;
    private static final float EXPOSE_SEPIA = 0.30F;

    // ---------------------------------------------------------------- state
    private static final RandomSource RNG = RandomSource.create();
    private static Matrix4f view;
    private static Matrix4f proj;
    private static Vec3 camPos = Vec3.ZERO;
    private static SlendermanEntity target;
    private static boolean targetShown;
    private static RenderTarget copy;

    private static long nextRoll;
    private static boolean on;
    private static float phase, strength, tint;
    private static int mode;

    private SlenderDistortion() {}

    // ---------------------------------------------------------------- who is visible (20 times a second)
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        target = null;
        targetShown = false;
        if (mc.player == null || mc.level == null) return;
        double best = Double.MAX_VALUE;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (en instanceof SlendermanEntity s) {
                double d = s.distanceToSqr(mc.player);
                if (d < best) {
                    best = d;
                    target = s;
                }
            }
        }
        if (target == null) return;
        SlendermanEntity s = target;
        targetShown = Math.sqrt(best) <= MAX_DIST
                && !s.isInvisible() && !s.isTeleportHidden() && !s.isWatcher() && s.isAlive()
                && s.getDeathPhase() < 2
                && SlendermanRoamingSound.isVisible(s);
    }

    // ---------------------------------------------------------------- the camera of the world (every frame)
    @SubscribeEvent
    public static void onLevelStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        view = e.getPoseStack().last().pose().copy();
        proj = e.getProjectionMatrix().copy();
        camPos = e.getCamera().getPosition();
    }

    // ---------------------------------------------------------------- the effect (first thing the interface draws)
    @SubscribeEvent
    public static void onOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.VIGNETTE.id())) return;
        Minecraft mc = Minecraft.getInstance();
        if (!targetShown || target == null || target.isRemoved() || view == null || proj == null) return;

        // the dice: on / off about 60 times a second, a new "noise" every time it is on
        long now = System.currentTimeMillis();
        if (now >= nextRoll) {
            nextRoll = now + ROLL_MS;
            on = RNG.nextFloat() < ON_CHANCE;
            phase = RNG.nextFloat() * 6.2831853F;
            strength = 0.55F + 0.45F * RNG.nextFloat();
            tint = RNG.nextFloat();
            mode = RNG.nextInt(3);          // 0 burnt, 1 washed out, 2 both
        }
        if (!on) return;

        float pt = e.getPartialTick();
        float[] r = screenRect(target, pt);
        if (r == null) return;
        try {
            draw(mc, e.getPoseStack(), r);
        } catch (Throwable ignored) {
            // never break the game because of a graphics detail
        } finally {
            mc.getMainRenderTarget().bindWrite(false);      // whatever happened, the game picture is the target again
            RenderSystem.enableCull();
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }

    /** the rectangle on the screen (0..1 in x and y, y up) around his box, or null when he is behind / outside. */
    private static float[] screenRect(SlendermanEntity s, float pt) {
        Vec3 ip = s.getPosition(pt);
        AABB box = s.getBoundingBox().move(ip.subtract(s.position())).inflate(PAD_SIDE, PAD_UPDOWN, PAD_SIDE);
        double[] xs = {box.minX, box.maxX};
        double[] ys = {box.minY, box.maxY};
        double[] zs = {box.minZ, box.maxZ};
        float minX = 9F, minY = 9F, maxX = -9F, maxY = -9F;
        for (double x : xs) {
            for (double y : ys) {
                for (double z : zs) {
                    Vector4f v = new Vector4f((float) (x - camPos.x), (float) (y - camPos.y), (float) (z - camPos.z), 1F);
                    v.transform(view);
                    v.transform(proj);
                    if (v.w() < 0.05F) return null;       // a corner is behind the camera: no rectangle
                    float nx = v.x() / v.w(), ny = v.y() / v.w();
                    minX = Math.min(minX, nx);
                    maxX = Math.max(maxX, nx);
                    minY = Math.min(minY, ny);
                    maxY = Math.max(maxY, ny);
                }
            }
        }
        minX = Mth.clamp(minX, -1F, 1F);
        maxX = Mth.clamp(maxX, -1F, 1F);
        minY = Mth.clamp(minY, -1F, 1F);
        maxY = Mth.clamp(maxY, -1F, 1F);
        if (maxX - minX < 0.01F || maxY - minY < 0.01F) return null;
        // 0..1
        Vector4f c = new Vector4f((float) (ip.x - camPos.x), (float) (ip.y + s.getBbHeight() * 0.5D - camPos.y), (float) (ip.z - camPos.z), 1F);
        c.transform(view);
        c.transform(proj);
        float depth = Math.max(0F, c.w());                       // distance along the view direction (blocks)
        return new float[]{minX * 0.5F + 0.5F, minY * 0.5F + 0.5F, maxX * 0.5F + 0.5F, maxY * 0.5F + 0.5F, depth};
    }

    /**
     * THE ORIGINAL SHADER "HeatDistortion" (Unity), ported 1:1 in its maths:
     *  - the game picture is copied ("GrabPass") and every point of the rectangle shows the picture of the SCREEN taken from a
     *    shifted place: shift = amount * (noiseA + noiseB - 1), both in x (red of the noise) and y (green of the noise);
     *  - noiseA is read at uv + (t/20, 2t), noiseB at uv - (t, t/20), t = time in seconds: two noises sliding through each
     *    other in different directions = the "heat" waving;
     *  - amount = strength * (normal . view)^2 / (1 + depth): strongest facing the camera, weaker with the distance;
     *  - the transparency of the whole thing = amount / transparency (so it also fades out with the distance and at the borders).
     * No colour changes in the shader itself. The soft "(normal . view)^2" of the unknown mesh is replaced by the soft border
     * of the rectangle (edgeWeight).
     */
    private static void draw(Minecraft mc, PoseStack ps, float[] r) {
        RenderTarget main = mc.getMainRenderTarget();
        int tw = main.width, th = main.height;
        if (copy == null) {
            copy = new TextureTarget(tw, th, false, Minecraft.ON_OSX);
            copy.setFilterMode(GL11.GL_LINEAR);
        } else if (copy.width != tw || copy.height != th) {
            copy.resize(tw, th, Minecraft.ON_OSX);
        }
        // the WHOLE picture is copied: the shift can look outside of the rectangle
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, tw, th, 0, 0, tw, th, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        Matrix4f m = ps.last().pose();
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        // the alpha of the copy is made 1 (the main picture does not always hold a real alpha, and the shader below multiplies with it)
        copy.bindWrite(false);
        RenderSystem.disableBlend();
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        quad(m, 0F, 0F, gw, gh, 1F, 1F, 1F, 1F);
        RenderSystem.colorMask(true, true, true, false);

        // ---- the heat waves, straight onto the game picture
        main.bindWrite(false);
        RenderSystem.colorMask(true, true, true, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.setShaderTexture(0, copy.getColorTextureId());
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        heat(m, r, gw, gh);
        exposure(m, r, gw, gh);

        // ---- optional extra colours of our version (contrast up, burnt / washed out), soft at the border
        if (EXTRA_COLOR) {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            if (mode != 1 || tint > 0.5F) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_COLOR);
                softQuad(m, x0, y0, x1, y1, 0.5F + (0.25F + 0.25F * strength) * 0.5F, 0.5F + (0.25F + 0.25F * strength) * 0.5F,
                        0.5F + (0.25F + 0.25F * strength) * 0.5F, 0.5F, 0.5F, 0.5F, true);
            }
            if (mode != 1) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
                softQuad(m, x0, y0, x1, y1, 0.95F, 0.62F + 0.2F * tint, 0.25F, 0F, 0F, 0F, false, 0.16F + 0.12F * strength);
            }
            if (mode != 0) {
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                softQuad(m, x0, y0, x1, y1, 0.78F, 0.80F, 0.76F, 0F, 0F, 0F, false, 0.30F + 0.20F * strength);
            }
        }
    }

    /** how visible the effect is at a point of the rectangle (0..1 across): 0 at the border, 1 from FEATHER inwards. */
    private static float edgeWeight(float t) {
        float e = Math.min(t, 1F - t) / FEATHER;
        e = Mth.clamp(e, 0F, 1F);
        return e * e * (3F - 2F * e);
    }

    // ---- the noise texture (the original "_NoiseTex" is not known, so a seamless smooth RG noise is made here)
    private static final int NZ = 16;
    private static final float[] NOISE_R = new float[NZ * NZ];
    private static final float[] NOISE_G = new float[NZ * NZ];
    static {
        java.util.Random rnd = new java.util.Random(1305L);
        for (int k = 0; k < NZ * NZ; k++) {
            NOISE_R[k] = rnd.nextFloat();
            NOISE_G[k] = rnd.nextFloat();
        }
    }

    /** bilinear read of the seamless noise, u and v repeat with the period 1 (like a texture with Repeat). */
    private static float noise(float[] t, float u, float v) {
        float x = (u - (float) Math.floor(u)) * NZ, y = (v - (float) Math.floor(v)) * NZ;
        int ix = (int) x, iy = (int) y;
        float fx = x - ix, fy = y - iy;
        fx = fx * fx * (3F - 2F * fx);
        fy = fy * fy * (3F - 2F * fy);
        int ix1 = (ix + 1) % NZ, iy1 = (iy + 1) % NZ;
        ix %= NZ;
        iy %= NZ;
        float a = t[iy * NZ + ix] + (t[iy * NZ + ix1] - t[iy * NZ + ix]) * fx;
        float b = t[iy1 * NZ + ix] + (t[iy1 * NZ + ix1] - t[iy1 * NZ + ix]) * fx;
        return a + (b - a) * fy;
    }

    /** the net of the rectangle: every vertex reads the screen from a shifted place (see the description above).
     *  Drawn three times, one colour channel each, the red and the blue picture pushed apart = chromatic aberration. */
    private static void heat(Matrix4f m, float[] r, int gw, int gh) {
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        float depth = r[4];
        float t = (System.nanoTime() / 1_000_000L % 3_600_000L) / 1000F;        // _Time.y in seconds
        float base = STRENGTH / (1F + depth);
        float[][] px = new float[HI + 1][HJ + 1], py = new float[HI + 1][HJ + 1];
        float[][] pu = new float[HI + 1][HJ + 1], pv = new float[HI + 1][HJ + 1], pa = new float[HI + 1][HJ + 1];
        for (int j = 0; j <= HJ; j++) {
            float tj = (float) j / HJ;
            for (int i = 0; i <= HI; i++) {
                float ti = (float) i / HI;
                float w = edgeWeight(ti) * edgeWeight(tj);          // stands for (normal . view)
                float amount = base * w * w;
                float tu = ti * NOISE_TILE, tv = tj * NOISE_TILE;
                float ox = noise(NOISE_R, tu + t / 20F, tv + 2F * t) + noise(NOISE_R, tu - t, tv - t / 20F) - 1F;
                float oy = noise(NOISE_G, tu + t / 20F, tv + 2F * t) + noise(NOISE_G, tu - t, tv - t / 20F) - 1F;
                px[i][j] = x0 + (x1 - x0) * ti;
                py[i][j] = y0 + (y1 - y0) * tj;
                pu[i][j] = r[0] + (r[2] - r[0]) * ti + amount * ox;
                pv[i][j] = r[3] - (r[3] - r[1]) * tj + amount * oy;
                pa[i][j] = Math.min(1F, amount / TRANSPARENCY * CA_ALPHA_BOOST * w);
            }
        }
        float ca = CA_SHIFT * (0.7F + 0.3F * strength);
        // channel passes: red picture shifted one way, green in the middle, blue the other way
        for (int pass = 0; pass < 3; pass++) {
            RenderSystem.colorMask(pass == 0, pass == 1, pass == 2, false);
            float du = (pass - 1) * ca;
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
            for (int j = 0; j < HJ; j++) {
                for (int i = 0; i < HI; i++) {
                    b.vertex(m, px[i][j], py[i][j], 0F).color(1F, 1F, 1F, pa[i][j]).uv(pu[i][j] + du, pv[i][j]).endVertex();
                    b.vertex(m, px[i][j + 1], py[i][j + 1], 0F).color(1F, 1F, 1F, pa[i][j + 1]).uv(pu[i][j + 1] + du, pv[i][j + 1]).endVertex();
                    b.vertex(m, px[i + 1][j + 1], py[i + 1][j + 1], 0F).color(1F, 1F, 1F, pa[i + 1][j + 1]).uv(pu[i + 1][j + 1] + du, pv[i + 1][j + 1]).endVertex();
                    b.vertex(m, px[i + 1][j], py[i + 1][j], 0F).color(1F, 1F, 1F, pa[i + 1][j]).uv(pu[i + 1][j] + du, pv[i + 1][j]).endVertex();
                }
            }
            BufferUploader.drawWithShader(b.end());
        }
        RenderSystem.colorMask(true, true, true, false);
    }

    /** when he is close: a burst of overexposure inside the rectangle (light pushed up, blacks turn brown, colours burn out). */
    private static void exposure(Matrix4f m, float[] r, int gw, int gh) {
        float d = r[4];
        float k = Mth.clamp((float) ((EXPOSE_FAR - d) / (EXPOSE_FAR - EXPOSE_NEAR)), 0F, 1F);
        if (k <= 0.001F) return;
        k = k * k * (3F - 2F * k);
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        float flash = 0.55F + 0.45F * strength;          // the flashes: a new strength every moment the distortion is on
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // 1) the exposure is turned up: lights get brighter (2 * src * dst)
        float up = 0.5F + 0.5F * EXPOSE_LIGHT * k * flash * 0.5F;
        RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_COLOR);
        softQuad(m, x0, y0, x1, y1, up, up, up, 0.5F, 0.5F, 0.5F, true);
        // 2) a warm light is added: black becomes brown
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        softQuad(m, x0, y0, x1, y1, 0.95F, 0.62F, 0.30F, 0F, 0F, 0F, false, EXPOSE_BROWN * k * flash);
        // 3) the colours burn out a little: sepia over everything
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        softQuad(m, x0, y0, x1, y1, 0.72F, 0.55F, 0.36F, 0F, 0F, 0F, false, EXPOSE_SEPIA * k * flash);
    }

    /** a colour rectangle whose border is neutral / transparent: multiply mode fades to (nr,ng,nb), the other modes to alpha 0. */
    private static void softQuad(Matrix4f m, float x0, float y0, float x1, float y1, float cr, float cg, float cb,
                                 float nr, float ng, float nb, boolean multiply) {
        softQuad(m, x0, y0, x1, y1, cr, cg, cb, nr, ng, nb, multiply, 1F);
    }

    private static void softQuad(Matrix4f m, float x0, float y0, float x1, float y1, float cr, float cg, float cb,
                                 float nr, float ng, float nb, boolean multiply, float alpha) {
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int j = 0; j < FJ; j++) {
            float ta = (float) j / FJ, tb = (float) (j + 1) / FJ;
            for (int i = 0; i < FI; i++) {
                float sa = (float) i / FI, sb = (float) (i + 1) / FI;
                float xa = x0 + (x1 - x0) * sa, xb = x0 + (x1 - x0) * sb;
                float ya = y0 + (y1 - y0) * ta, yb = y0 + (y1 - y0) * tb;
                soft(b, m, xa, ya, edgeWeight(sa) * edgeWeight(ta), cr, cg, cb, nr, ng, nb, multiply, alpha);
                soft(b, m, xa, yb, edgeWeight(sa) * edgeWeight(tb), cr, cg, cb, nr, ng, nb, multiply, alpha);
                soft(b, m, xb, yb, edgeWeight(sb) * edgeWeight(tb), cr, cg, cb, nr, ng, nb, multiply, alpha);
                soft(b, m, xb, ya, edgeWeight(sb) * edgeWeight(ta), cr, cg, cb, nr, ng, nb, multiply, alpha);
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void soft(BufferBuilder b, Matrix4f m, float x, float y, float w, float cr, float cg, float cb,
                             float nr, float ng, float nb, boolean multiply, float alpha) {
        if (multiply) {
            b.vertex(m, x, y, 0F).color(nr + (cr - nr) * w, ng + (cg - ng) * w, nb + (cb - nb) * w, 1F).endVertex();
        } else {
            b.vertex(m, x, y, 0F).color(cr, cg, cb, alpha * w).endVertex();
        }
    }

    private static void quad(Matrix4f m, float x0, float y0, float x1, float y1, float cr, float cg, float cb, float ca) {
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        b.vertex(m, x0, y0, 0F).color(cr, cg, cb, ca).endVertex();
        b.vertex(m, x0, y1, 0F).color(cr, cg, cb, ca).endVertex();
        b.vertex(m, x1, y1, 0F).color(cr, cg, cb, ca).endVertex();
        b.vertex(m, x1, y0, 0F).color(cr, cg, cb, ca).endVertex();
        BufferUploader.drawWithShader(b.end());
    }
}
