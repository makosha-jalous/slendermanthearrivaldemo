package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The client side of the death scene (the server runs it, see SlendermanEntity.getDeathPhase()):
 *  - phase 1: the view of the player turns smoothly to the face of Slenderman, in exactly 3 seconds;
 *  - phase 2: a sharp, fully black screen for 2 seconds (it is drawn above everything, also above the camera HUD).
 * After that the server kills the player and the normal death screen comes.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class DeathSequenceClient {
    private static SlendermanEntity killer;
    private static int phase = 0;
    /** client ticks since the current phase started. */
    private static int ticks = 0;

    private DeathSequenceClient() {}

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
        if (current != phase) {
            phase = current;
            ticks = 0;
        } else if (current != 0) {
            ticks++;
        }
        killer = found;
    }

    /** the view turns to his face: 0 -> 100 % in DEATH_TURN_TICKS (3 s), then it stays on his face. */
    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (phase == 0 || killer == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float pt = (float) e.getPartialTick();

        float k = 1.0F;
        if (phase == 1) k = Mth.clamp((ticks + pt) / (float) SlendermanEntity.DEATH_TURN_TICKS, 0.0F, 1.0F);
        k = k * k * (3.0F - 2.0F * k);   // soft start and soft end

        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 face = killer.getEyePosition(pt);
        double dx = face.x - eye.x;
        double dy = face.y - eye.y;
        double dz = face.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F;
        float targetPitch = (float) (-(Mth.atan2(dy, flat) * 57.29577951308232D));

        e.setYaw(e.getYaw() + Mth.wrapDegrees(targetYaw - e.getYaw()) * k);
        e.setPitch(e.getPitch() + (targetPitch - e.getPitch()) * k);
    }

    /** the black screen: drawn after ALL overlays, so nothing shows through. */
    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post e) {
        if (phase != 2) return;
        GuiComponent.fill(e.getPoseStack(), 0, 0,
                e.getWindow().getGuiScaledWidth(), e.getWindow().getGuiScaledHeight(), 0xFF000000);
    }
}
