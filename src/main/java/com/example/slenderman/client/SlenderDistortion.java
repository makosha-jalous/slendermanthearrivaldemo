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
    private static final double PAD_SIDE = 0.7D;
    private static final double PAD_UPDOWN = 0.25D;
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

    // ---------------------------------------------------------------- state
    private static final RandomSource RNG = RandomSource.create();
    private static Matrix4f view;
    private static Matrix4f proj;
    private static Vec3 camPos = Vec3.ZERO;
    private static SlendermanEntity target;
    private static boolean targetShown;
    private static RenderTarget copy;
    private static RenderTarget work;

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
        return new float[]{minX * 0.5F + 0.5F, minY * 0.5F + 0.5F, maxX * 0.5F + 0.5F, maxY * 0.5F + 0.5F};
    }

    /**
     * The effect is made in a second picture (work) and put on the game picture with SOFT edges: the closer to the border
     * of the rectangle, the more transparent it is, so the rectangle itself is never visible, only the heat waves, the
     * burnt / pale colours and the split colours inside it.
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
        if (work == null) {
            work = new TextureTarget(tw, th, false, Minecraft.ON_OSX);
            work.setFilterMode(GL11.GL_LINEAR);
        } else if (work.width != tw || work.height != th) {
            work.resize(tw, th, Minecraft.ON_OSX);
        }
        // copy only the rectangle of the picture
        int bx0 = Mth.clamp((int) Math.floor(r[0] * tw) - 2, 0, tw), bx1 = Mth.clamp((int) Math.ceil(r[2] * tw) + 2, 0, tw);
        int by0 = Mth.clamp((int) Math.floor(r[1] * th) - 2, 0, th), by1 = Mth.clamp((int) Math.ceil(r[3] * th) + 2, 0, th);
        if (bx1 - bx0 < 2 || by1 - by0 < 2) return;
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GL30.glBlitFramebuffer(bx0, by0, bx1, by1, bx0, by0, bx1, by1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

        int gw = mc.getWindow().getGuiScaledWidth(), gh = mc.getWindow().getGuiScaledHeight();
        float rectW = (r[2] - r[0]) * gw;
        Matrix4f m = ps.last().pose();
        float wave = rectW * WAVE * strength;
        float shift = rectW * COLOR_SHIFT * strength;

        RenderSystem.disableCull();
        RenderSystem.disableDepthTest();

        // ---- 1) the effect is made in the second picture
        work.bindWrite(false);
        RenderSystem.colorMask(true, true, true, false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, copy.getColorTextureId());
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        // the plain picture first (no holes), then the waving picture with the colour channels pushed apart
        plain(m, r, gw, gh);
        for (int pass = 0; pass < 3; pass++) {
            RenderSystem.colorMask(pass == 0, pass == 1, pass == 2, false);
            net(m, r, gw, gh, wave, (pass - 1) * shift);
        }
        RenderSystem.colorMask(true, true, true, false);

        // contrast and brightness up: the picture is multiplied with itself (dark gets darker, light burns out)
        if (mode != 1 || tint > 0.5F) {
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.SRC_COLOR);
            float k = 0.75F + 0.25F * strength;
            RenderSystem.setShaderColor(k, k, k, 1F);
            net(m, r, gw, gh, wave, 0F);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }

        // the colours: burnt (warm, glaring) and / or washed out (grey, pale)
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        if (mode != 1) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            quad(m, x0, y0, x1, y1, 0.95F, 0.62F + 0.2F * tint, 0.25F, 0.16F + 0.12F * strength);
        }
        if (mode != 0) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            quad(m, x0, y0, x1, y1, 0.78F, 0.80F, 0.76F, 0.30F + 0.20F * strength);
        }

        // ---- 2) the finished effect goes onto the game picture, fading out towards the border of the rectangle
        main.bindWrite(false);
        RenderSystem.colorMask(true, true, true, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        RenderSystem.setShaderTexture(0, work.getColorTextureId());
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        feathered(m, r, gw, gh);
    }

    /** the rectangle with the unchanged picture (the base of the effect). */
    private static void plain(Matrix4f m, float[] r, int gw, int gh) {
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        b.vertex(m, x0, y0, 0F).uv(r[0], r[3]).endVertex();
        b.vertex(m, x0, y1, 0F).uv(r[0], r[1]).endVertex();
        b.vertex(m, x1, y1, 0F).uv(r[2], r[1]).endVertex();
        b.vertex(m, x1, y0, 0F).uv(r[2], r[3]).endVertex();
        BufferUploader.drawWithShader(b.end());
    }

    /** how visible the effect is at a point of the rectangle (0..1 across): 0 at the border, 1 from FEATHER inwards. */
    private static float edgeWeight(float t) {
        float e = Math.min(t, 1F - t) / FEATHER;
        e = Mth.clamp(e, 0F, 1F);
        return e * e * (3F - 2F * e);
    }

    /** the effect picture as a net whose vertices get more transparent towards the border (no visible rectangle). */
    private static void feathered(Matrix4f m, float[] r, int gw, int gh) {
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR_TEX);
        for (int j = 0; j < FJ; j++) {
            float ta = (float) j / FJ, tb = (float) (j + 1) / FJ;
            float wa = edgeWeight(ta), wb = edgeWeight(tb);
            float ya = y0 + (y1 - y0) * ta, yb = y0 + (y1 - y0) * tb;
            float va = r[3] - (r[3] - r[1]) * ta, vb = r[3] - (r[3] - r[1]) * tb;
            for (int i = 0; i < FI; i++) {
                float sa = (float) i / FI, sb = (float) (i + 1) / FI;
                float ha = edgeWeight(sa), hb = edgeWeight(sb);
                float xa = x0 + (x1 - x0) * sa, xb = x0 + (x1 - x0) * sb;
                float ua = r[0] + (r[2] - r[0]) * sa, ub = r[0] + (r[2] - r[0]) * sb;
                b.vertex(m, xa, ya, 0F).color(1F, 1F, 1F, wa * ha).uv(ua, va).endVertex();
                b.vertex(m, xa, yb, 0F).color(1F, 1F, 1F, wb * ha).uv(ua, vb).endVertex();
                b.vertex(m, xb, yb, 0F).color(1F, 1F, 1F, wb * hb).uv(ub, vb).endVertex();
                b.vertex(m, xb, ya, 0F).color(1F, 1F, 1F, wa * hb).uv(ub, va).endVertex();
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    /** the picture of the rectangle as a net of small quads, every row is pushed sideways by the wave. */
    private static void net(Matrix4f m, float[] r, int gw, int gh, float wave, float colorShift) {
        float x0 = r[0] * gw, x1 = r[2] * gw, y0 = (1F - r[3]) * gh, y1 = (1F - r[1]) * gh;
        BufferBuilder b = Tesselator.getInstance().getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        for (int j = 0; j < ROWS; j++) {
            float ta = (float) j / ROWS, tb = (float) (j + 1) / ROWS;
            float oa = rowOffset(j, wave) + colorShift, ob = rowOffset(j + 1, wave) + colorShift;
            float ya = y0 + (y1 - y0) * ta, yb = y0 + (y1 - y0) * tb;
            float va = r[3] - (r[3] - r[1]) * ta, vb = r[3] - (r[3] - r[1]) * tb;
            for (int i = 0; i < COLS; i++) {
                float sa = (float) i / COLS, sb = (float) (i + 1) / COLS;
                float xa = x0 + (x1 - x0) * sa, xb = x0 + (x1 - x0) * sb;
                float ua = r[0] + (r[2] - r[0]) * sa, ub = r[0] + (r[2] - r[0]) * sb;
                b.vertex(m, xa + oa, ya, 0F).uv(ua, va).endVertex();
                b.vertex(m, xa + ob, yb, 0F).uv(ua, vb).endVertex();
                b.vertex(m, xb + ob, yb, 0F).uv(ub, vb).endVertex();
                b.vertex(m, xb + oa, ya, 0F).uv(ub, va).endVertex();
            }
        }
        BufferUploader.drawWithShader(b.end());
    }

    /** the sideways push of one row: a fast wave plus a rough shiver, new every time the effect flashes. */
    private static float rowOffset(int row, float wave) {
        float a = (float) Math.sin(row * 0.85F + phase) * 0.6F;
        float c = (float) Math.sin(row * 2.3F + phase * 1.7F) * 0.4F;
        return wave * (a + c);
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
