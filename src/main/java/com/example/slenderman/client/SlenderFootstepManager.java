package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "slenderman", value = Dist.CLIENT)
public class SlenderFootstepManager {

    private static final int MIN_COOLDOWN_TICKS = 15 * 20; // 15 секунд
    private static final int MAX_COOLDOWN_TICKS = 240 * 20; // 4 минуты
    private static int nextPlayTimer = MIN_COOLDOWN_TICKS;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;

        // Шаги играют только если Слендермен реально существует в мире
        List<SlendermanEntity> slendermen = mc.level.getEntitiesOfClass(
                SlendermanEntity.class, 
                mc.player.getBoundingBox().inflate(128.0D)
        );

        if (slendermen.isEmpty()) {
            nextPlayTimer = MIN_COOLDOWN_TICKS;
            return;
        }

        nextPlayTimer--;

        if (nextPlayTimer <= 0) {
            RandomSource random = mc.level.random;

            StereoFootstepSoundInstance.StereoMode[] modes = StereoFootstepSoundInstance.StereoMode.values();
            StereoFootstepSoundInstance.StereoMode selectedMode = modes[random.nextInt(modes.length)];

            float volume = 0.25F + random.nextFloat() * 0.75F;
            float[] pitchOptions = { 0.8F, 1.0F, 1.25F };
            float basePitch = pitchOptions[random.nextInt(pitchOptions.length)];
            float finalPitch = basePitch + (random.nextFloat() - 0.5F) * 0.05F;

            int durationTicks = (int) ((60 + random.nextInt(40)) / finalPitch);

            StereoFootstepSoundInstance soundInstance = new StereoFootstepSoundInstance(
                    selectedMode, volume, finalPitch, durationTicks
            );
            mc.getSoundManager().play(soundInstance);

            nextPlayTimer = MIN_COOLDOWN_TICKS + random.nextInt(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS);
        }
    }
}