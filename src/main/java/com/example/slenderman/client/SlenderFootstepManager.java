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

    // Диапазон задержки между проигрываниями (в тиках): от 15 сек (300 тиков) до 4 минут (4800 тиков)
    private static final int MIN_COOLDOWN_TICKS = 15 * 20; 
    private static final int MAX_COOLDOWN_TICKS = 240 * 20; 

    private static int nextPlayTimer = MIN_COOLDOWN_TICKS;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) return;

        // Проверяем, существует ли Слендермен в мире
        List<SlendermanEntity> slendermen = mc.level.getEntitiesOfClass(
                SlendermanEntity.class, 
                mc.player.getBoundingBox().inflate(128.0D)
        );

        if (slendermen.isEmpty()) {
            // Если сущности нет — сбрасываем таймер на минимальную задержку
            nextPlayTimer = MIN_COOLDOWN_TICKS;
            return;
        }

        // Если Слендермен есть в мире, отсчитываем время
        nextPlayTimer--;

        if (nextPlayTimer <= 0) {
            RandomSource random = mc.level.random;

            // 1. Выбираем режим стерео
            StereoFootstepSoundInstance.StereoMode[] modes = StereoFootstepSoundInstance.StereoMode.values();
            StereoFootstepSoundInstance.StereoMode selectedMode = modes[random.nextInt(modes.length)];

            // 2. Громкость: от тихой (0.25f) до очень громкой (1.0f)
            float volume = 0.25F + random.nextFloat() * 0.75F;

            // 3. Скорость и тональность (Pitch):
            // Помедленнее (0.8F), обычная (1.0F), побыстрее (1.25F)
            float[] pitchOptions = { 0.8F, 1.0F, 1.25F };
            float basePitch = pitchOptions[random.nextInt(pitchOptions.length)];
            // Легкое микро-варьирование, чтобы каждый раз звучало уникально
            float finalPitch = basePitch + (random.nextFloat() - 0.5F) * 0.05F;

            // Длительность звучания в тиках для перехода панорамы (около 3-5 секунд с учетом скорости)
            int durationTicks = (int) ((60 + random.nextInt(40)) / finalPitch);

            // Создаем и проигрываем звук
            StereoFootstepSoundInstance soundInstance = new StereoFootstepSoundInstance(
                    selectedMode, volume, finalPitch, durationTicks
            );
            mc.getSoundManager().play(soundInstance);

            // 4. Задаем следующий случайный интервал (от 15 секунд до 4 минут)
            nextPlayTimer = MIN_COOLDOWN_TICKS + random.nextInt(MAX_COOLDOWN_TICKS - MIN_COOLDOWN_TICKS);
        }
    }
}