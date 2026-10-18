package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
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
                // everything is cut off; a light echo is left (the volume stutters down)
                mc.getSoundManager().stop();
                echoTick = 0;
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

    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post e) {
        if (phase != 2) return;
        GuiComponent.fill(e.getPoseStack(), 0, 0,
                e.getWindow().getGuiScaledWidth(), e.getWindow().getGuiScaledHeight(), 0xFF000000);
    }
}
