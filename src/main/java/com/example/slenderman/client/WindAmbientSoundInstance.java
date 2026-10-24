package com.example.slenderman.client;

import com.example.slenderman.StepSoundRegistry;
import com.example.slenderman.SlendermanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.List;

public class WindAmbientSoundInstance extends AbstractTickableSoundInstance {
    private float currentVolume = 0.0F;
    private static final float OUTDOOR_VOLUME = 0.3F;
    private static final float INDOOR_VOLUME = 0.09F;
    private static final int FADE_DURATION = 40;

    public WindAmbientSoundInstance() {
        super(StepSoundRegistry.WIND_AMBIENCE.get(), SoundSource.AMBIENT, Minecraft.getInstance().level.random);
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 1.0F;
        this.relative = true;
    }

    // Безопасный публичный метод остановки звука
    public void stopSound() {
        this.stop();
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

        List<SlendermanEntity> slendermen = mc.level.getEntitiesOfClass(
                SlendermanEntity.class,
                mc.player.getBoundingBox().inflate(128.0D)
        );
        boolean slenderExists = !slendermen.isEmpty();
        boolean isIndoors = SlenderInteriorSoundManager.checkIndoors(mc);

        float targetVol;
        if (isIndoors) {
            targetVol = slenderExists ? 0.0F : INDOOR_VOLUME;
        } else {
            targetVol = OUTDOOR_VOLUME;
        }

        if (Math.abs(this.currentVolume - targetVol) > 0.001F) {
            this.currentVolume += (targetVol > currentVolume ? 1.0F : -1.0F) * (OUTDOOR_VOLUME / (float) FADE_DURATION);
            this.currentVolume = Math.max(0.0F, Math.min(OUTDOOR_VOLUME, currentVolume));
            if ((targetVol > currentVolume && currentVolume >= targetVol) || (targetVol < currentVolume && currentVolume <= targetVol)) {
                this.currentVolume = targetVol;
            }
        } else {
            this.currentVolume = targetVol;
        }

        this.volume = this.currentVolume;
    }
}