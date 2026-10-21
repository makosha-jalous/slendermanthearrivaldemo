package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side of the death scene (the server runs it, see SlendermanEntity.getDeathPhase()):
 *  - phase 1: the view turns to his face in exactly 3 s; the mouse has no effect (the view is set absolutely);
 *  - phase 2: sharp black screen for 2 s; every sound is cut off EXCEPT the screamers (dramatic1a/b/c), which play to
 *             the end; when no screamer is playing a light echo fades out;
 *  - phase 3: the portrait (3 s): Slenderman to the chest on black, a dirty green glow behind his head, the head
 *             almost black. Static Medium stutters in pieces, Static Heavy plays in chopped pieces of 80-100 ms with no
 *             pauses, and the picture twitches with every piece. (The sounds are the ones of the slendercam mod.)
 *  - phase 4: black again, every sound dies away; then the real Minecraft death screen.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class DeathSequenceClient {
    private static SlendermanEntity killer;
    private static int phase = 0;
    private static int ticks = 0;
    private static float startYaw, startPitch;
    private static boolean gainChanged = false;
    private static int echoTick = 0;
    private static long noiseStartMs = 0L;
    private static DynamicTexture noise;
    private static ResourceLocation noiseLoc;
    private static long lastNoiseMs = 0L;
    private static final Random NOISE_RND = new Random();
    private static final int NOISE_TEX = 256;

    // ---- portrait ----
    /** size of the portrait: pixels per block (as a share of the screen height) and where the top of his head is (share of the height). */
    private static final float PORTRAIT_SCALE = 0.60F;
    private static final float PORTRAIT_TOP = 0.08F;
    /** where the glow sits (share of the height): the head. */
    private static final float PORTRAIT_GLOW_Y = 0.27F;
    private static final Random FX_RND = new Random();
    private static SoundEvent mediumPiece, heavyLoop;
    private static boolean dramaticActive = false;
    private static long mediumNextMs = 0L, heavyStopMs = 0L;
    private static SimpleSoundInstance heavyInst;
    private static float twX, twY, twMouse, glowFlick = 1.0F;

    private DeathSequenceClient() {}

    private static float[] targetAngles(Minecraft mc, float pt) {
        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 face = killer.getEyePosition(pt);
        double dx = face.x - eye.x, dy = face.y - eye.y, dz = face.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F,
                (float) (-(Mth.atan2(dy, flat) * 57.29577951308232D))};
    }

    private static float[] angles(Minecraft mc, float pt) {
        float k = 1.0F;
        if (phase == 1) k = Mth.clamp((ticks + pt) / (float) SlendermanEntity.DEATH_TURN_TICKS, 0.0F, 1.0F);
        k = k * k * (3.0F - 2.0F * k);
        float[] t = targetAngles(mc, pt);
        return new float[]{startYaw + Mth.wrapDegrees(t[0] - startYaw) * k, startPitch + (t[1] - startPitch) * k};
    }

    private static void setGain(float g) {
        try {
            Minecraft.getInstance().getSoundManager().updateSourceVolume(SoundSource.MASTER, g);
            gainChanged = true;
        } catch (Throwable ignored) {
        }
    }

    private static void restoreGain() {
        if (!gainChanged) return;
        gainChanged = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, mc.options.getSoundSourceVolume(SoundSource.MASTER));
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        SlendermanEntity found = null;
        int current = 0;
        if (mc.level != null && mc.player != null) {
            for (Entity en : mc.level.entitiesForRendering()) {
                if (en instanceof SlendermanEntity s) {
                    int p = s.getDeathPhase();
                    if (p > current) {
                        current = p;
                        found = s;
                    }
                }
            }
        }
        int old = phase;
        if (current != phase) {
            phase = current;
            ticks = 0;
            if (current != 0 && old == 0 && mc.player != null) {
                startYaw = mc.player.getYRot();
                startPitch = mc.player.getXRot();
            }
            if (current == 2 && old < 2) {
                // everything is cut off at once, EXCEPT the screamers (they have their own sound category and play to the
                // end). When no screamer is playing, a light echo of the rising sounds is left (the volume stutters down).
                silenceAll();
                dramaticActive = isScreamPlaying();
                echoTick = 0;
                noiseStartMs = System.currentTimeMillis();
            }
            if (current == 3) {
                restoreGain();                    // the echo is over: the portrait has its own sounds
                mediumNextMs = 0L;
                heavyStopMs = 0L;
                stopHeavy();
                newTwitch();
            }
            if (current == 4) {
                stopHeavy();                      // black again: everything dies away
            }
            if (current == 0) {
                stopHeavy();
                restoreGain();
                resetRoamingSounds(mc);
            }
        } else if (current != 0) {
            ticks++;
        }
        killer = found;

        if (phase != 0 && killer != null && mc.player != null) {
            // the player cannot turn the head: the real rotation is overwritten every tick
            float[] a = angles(mc, 1.0F);
            mc.player.setYRot(a[0]);
            mc.player.setXRot(a[1]);
            mc.player.yRotO = a[0];
            mc.player.xRotO = a[1];
        }
        if (phase == 4) {
            // everything dies away
            float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float k = 1.0F - Mth.clamp((ticks + 1) / (float) SlendermanEntity.DEATH_END_TICKS, 0.0F, 1.0F);
            setGain(master * k);
        }
        if (phase == 2 && !dramaticActive) {
            // echo: repeats that get quieter and quieter
            echoTick++;
            float g = 0.0F;
            int[] hits = {2, 8, 15, 23, 32};
            float[] vol = {0.20F, 0.12F, 0.07F, 0.04F, 0.02F};
            for (int i = 0; i < hits.length; i++) {
                if (echoTick >= hits[i] && echoTick < hits[i] + 3) g = vol[i];
            }
            setGain(g);
        }
    }

    /** stops every sound except the screamers (they are in the category VOICES). */
    private static void silenceAll() {
        try {
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            for (SoundSource src : SoundSource.values()) {
                if (src == SlendermanRoamingSound.SCREAM_SOURCE) continue;
                sm.stop(null, src);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isScreamPlaying() {
        try {
            SoundInstance inst = SlendermanRoamingSound.lastScream;
            return inst != null && Minecraft.getInstance().getSoundManager().isActive(inst);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** the roaming loops were stopped from outside: let every Slenderman start a fresh one when it is needed. */
    private static void resetRoamingSounds(Minecraft mc) {
        if (mc.level == null) return;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (en instanceof SlendermanEntity s) s.roamingSound = null;
        }
    }

    private static void newTwitch() {
        twX = (FX_RND.nextFloat() - 0.5F) * 0.050F;
        twY = (FX_RND.nextFloat() - 0.5F) * 0.040F;
        twMouse = (FX_RND.nextFloat() - 0.5F) * 30.0F;
        glowFlick = 0.80F + 0.20F * FX_RND.nextFloat();
    }

    private static void stopHeavy() {
        if (heavyInst == null) return;
        try {
            Minecraft.getInstance().getSoundManager().stop(heavyInst);
        } catch (Throwable ignored) {
        }
        heavyInst = null;
    }

    /**
     * The sounds of the portrait, checked every rendered frame (the pieces are only 80-160 ms long):
     * Static Medium in pieces (one after another, 125-160 ms) and Static Heavy in chopped pieces of 80-100 ms, no
     * pauses; every Heavy piece also gives the picture a new twitch.
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (phase != 3) {
            if (heavyInst != null) stopHeavy();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        SoundManager sm = mc.getSoundManager();
        long now = System.currentTimeMillis();
        try {
            if (mediumPiece == null) mediumPiece = new SoundEvent(new ResourceLocation("slendercam", "static_medium_piece"));
            if (heavyLoop == null) heavyLoop = new SoundEvent(new ResourceLocation("slendercam", "static_heavy"));
            if (mediumNextMs == 0L || now - mediumNextMs > 400L) mediumNextMs = now;
            if (now >= mediumNextMs) {
                sm.play(SimpleSoundInstance.forUI(mediumPiece, 0.97F + FX_RND.nextFloat() * 0.06F, 1.0F));
                mediumNextMs = now + 125L + FX_RND.nextInt(36);
            }
            if (heavyInst == null || now >= heavyStopMs) {
                if (heavyInst != null) sm.stop(heavyInst);
                heavyInst = SimpleSoundInstance.forUI(heavyLoop, 0.90F + FX_RND.nextFloat() * 0.25F, 1.0F);
                sm.play(heavyInst);
                heavyStopMs = now + 80L + FX_RND.nextInt(21);       // 80-100 ms, the next one follows at once
                newTwitch();
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        phase = 0;
        killer = null;
        stopHeavy();
        restoreGain();
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (phase == 0 || killer == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float[] a = angles(mc, (float) e.getPartialTick());
        e.setYaw(a[0]);
        e.setPitch(a[1]);
    }

    /**
     * The black screen (drawn after ALL overlays, so nothing shows through) with the camera noise on it:
     * at the very start a silent flash of noise, then a light flickering noise, the screen stays black.
     */
    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post e) {
        if (phase < 2) return;
        int w = e.getWindow().getGuiScaledWidth();
        int h = e.getWindow().getGuiScaledHeight();
        PoseStack ps = e.getPoseStack();
        GuiComponent.fill(ps, 0, 0, w, h, 0xFF000000);
        if (phase == 4) return;                       // the last black screen: nothing but black
        if (phase == 3) {
            drawPortrait(ps, w, h);
            drawNoise(ps, w, h, 0.10F);
            return;
        }

        long now = System.currentTimeMillis();
        long t = now - noiseStartMs;
        float alpha;
        if (t < 260L) {
            alpha = 0.88F + 0.08F * NOISE_RND.nextFloat();                         // the flash
        } else if (t < 520L) {
            float k = (t - 260L) / 260F;                                           // flash dies down to the light level
            alpha = Mth.lerp(k, 0.60F, 0.12F) * (0.8F + 0.4F * NOISE_RND.nextFloat());
        } else {
            // light noise that flickers: the level changes every 70 ms, now and then a stronger flicker
            long bucket = now / 70L;
            Random r = new Random(bucket * 7919L);
            alpha = 0.05F + 0.09F * r.nextFloat();
            if (r.nextFloat() < 0.12F) alpha += 0.08F;
        }
        drawNoise(ps, w, h, Mth.clamp(alpha, 0F, 0.97F));
    }

    /** Slenderman to the chest in the middle, a weak dirty green glow behind the head, the head almost black. */
    private static void drawPortrait(PoseStack ps, int w, int h) {
        float jx = twX * w;
        float jy = twY * h;
        drawGlow(ps, w * 0.5F + jx * 0.5F, h * PORTRAIT_GLOW_Y + jy * 0.5F, h * 0.55F, h * 0.50F, 0.46F * glowFlick);
        if (killer == null) return;
        try {
            int scale = Math.round(h * PORTRAIT_SCALE);
            int x = Math.round(w * 0.5F + jx);
            int y = Math.round(h * PORTRAIT_TOP + 4.0F * scale + jy);      // the feet are far below the screen: only the chest and up show
            RenderSystem.setShaderColor(0.07F, 0.07F, 0.07F, 1.0F);        // the model sits in the dark
            InventoryScreen.renderEntityInInventory(x, y, scale, twMouse, 0.0F, killer);
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** a soft round glow (overlay only, no light source): the colour fades to nothing at the edge. */
    private static void drawGlow(PoseStack ps, float cx, float cy, float rx, float ry, float alpha) {
        try {
            Matrix4f m = ps.last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            b.vertex(m, cx, cy, 0F).color(0.40F, 0.48F, 0.20F, Mth.clamp(alpha, 0F, 1F)).endVertex();
            int n = 40;
            for (int i = 0; i <= n; i++) {
                double a = i * (Math.PI * 2.0D / n);
                b.vertex(m, cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry, 0F).color(0.40F, 0.48F, 0.20F, 0F).endVertex();
            }
            Tesselator.getInstance().end();
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static void drawNoise(PoseStack ps, int w, int h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (noise == null) {
                noise = new DynamicTexture(NOISE_TEX, NOISE_TEX, false);
                noise.setFilter(false, false);
                noiseLoc = mc.getTextureManager().register("slenderman_death_noise", noise);
            }
            long now = System.currentTimeMillis();
            if (now - lastNoiseMs > 40L) {
                lastNoiseMs = now;
                NativeImage img = noise.getPixels();
                if (img != null) {
                    for (int y = 0; y < NOISE_TEX; y++) {
                        for (int x = 0; x < NOISE_TEX; x++) {
                            int v = NOISE_RND.nextInt(256);
                            int a = NOISE_RND.nextInt(256);
                            img.setPixelRGBA(x, y, (a << 24) | (v << 16) | (v << 8) | v);
                        }
                    }
                    noise.upload();
                }
            }
            double g = mc.getWindow().getGuiScale();
            int regionW = Math.max(1, (int) Math.round(w * g));
            int regionH = Math.max(1, (int) Math.round(h * g));
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
            RenderSystem.setShaderTexture(0, noiseLoc);
            GuiComponent.blit(ps, 0, 0, w, h, NOISE_RND.nextInt(NOISE_TEX), NOISE_RND.nextInt(NOISE_TEX),
                    regionW, regionH, NOISE_TEX, NOISE_TEX);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
        } catch (Throwable ignored) {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }
}
