package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.renderer.GameRenderer;
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
 *  - phase 2: sharp black screen for 2 s; all sounds are cut off and only a light echo fades out.
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
            if (current == 2) {
                // everything is cut off at once; a light echo of the rising sounds is left (the volume stutters down).
                // Nothing is stopped for good (no SoundManager.stop()): the loops of other mods (camera!) keep their
                // state and simply go on after the scene.
                echoTick = 0;
                noiseStartMs = System.currentTimeMillis();
            }
            if (current == 0) restoreGain();
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
        if (phase == 2) {
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

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        phase = 0;
        killer = null;
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
        if (phase != 2) return;
        int w = e.getWindow().getGuiScaledWidth();
        int h = e.getWindow().getGuiScaledHeight();
        PoseStack ps = e.getPoseStack();
        GuiComponent.fill(ps, 0, 0, w, h, 0xFF000000);

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
