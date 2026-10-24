package com.example.slenderman.client;

import com.example.slenderman.StepSoundRegistry;
import com.example.slenderman.SlendermanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.List;

public class WindAmbientSoundInstance extends AbstractTickableSoundInstance {
    private float currentVolume = 0.0F;
    private static final float OUTDOOR_VOLUME = 0.3F; // 100% громкости ветра (базовая)
    private static final float INDOOR_VOLUME = 0.09F;  // На 70% тише (0.3 * 0.3 = 0.09)
    private static final int FADE_DURATION = 40;       // 2 секунды на плавное изменение громкости (40 тиков)
    private int fadeTimer = 0;

    public WindAmbientSoundInstance() {
        super(StepSoundRegistry.WIND_AMBIENCE.get(), SoundSource.AMBIENT, Minecraft.getInstance().level.random);
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        this.relative = true;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) {
            this.stop();
            return;
        }

        this.x = (float) mc.player.getX();
        this.y = (float) mc.player.getY();
        this.z = (float) mc.player.getZ();

        // Проверяем, есть ли Слендермен в мире
        List<SlendermanEntity> slendermen = mc.level.getEntitiesOfClass(
                SlendermanEntity.class,
                mc.player.getBoundingBox().inflate(128.0D)
        );
        boolean slenderExists = !slendermen.isEmpty();

        // Проверяем, находится ли игрок под крышей
        boolean isIndoors = SlenderInteriorSoundManager.checkIndoors(mc);

        // Целевая громкость: если Слендермен в мире и игрок внутри — ветер полностью глушится (0).
        // Если внутри, но Слендермена нет — играет на 70% тише (INDOOR_VOLUME).
        // Если на улице — полная громкость (OUTDOOR_VOLUME).
        float targetVol;
        if (isIndoors) {
            targetVol = slenderExists ? 0.0F : INDOOR_VOLUME;
        } else {
            targetVol = OUTDOOR_VOLUME;
        }

        // Плавное изменение громкости за 2 секунды (FADE_DURATION = 40 тиков)
        if (Math.abs(this.currentVolume - targetVol) > 0.001F) {
            float step = (targetVol - currentVolume) / (float) FADE_DURATION;
            // Ускоряем шаг смены, если длительность равна FADE_DURATION
            this.currentVolume += (targetVol > currentVolume ? 1.0F : -1.0F) * (OUTDOOR_VOLUME / (float) FADE_DURATION);
            this.currentVolume = clamp(this.currentVolume, 0.0F, OUTDOOR_VOLUME);
            
            // Точное достижение цели
            if ((step > 0 && currentVolume >= targetVol) || (step < 0 && currentVolume <= targetVol)) {
                this.currentVolume = targetVol;
            }
        } else {
            this.currentVolume = targetVol;
        }

        this.volume = this.currentVolume;
    }

    private float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }
}