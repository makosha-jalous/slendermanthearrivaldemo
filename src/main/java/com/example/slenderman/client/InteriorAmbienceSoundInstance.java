package com.example.slenderman.client;

import com.example.slenderman.StepSoundRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;

public class InteriorAmbienceSoundInstance extends AbstractTickableSoundInstance {
    private float targetVolume = 0.8F;
    private boolean fadingOut = false;
    private int fadeTicks = 0;
    private static final int FADE_IN_DURATION = 40;  // 2 секунды (40 тиков)
    private static final int FADE_OUT_DURATION = 60; // 3 секунды (60 тиков)

    public InteriorAmbienceSoundInstance() {
        super(StepSoundRegistry.INTERIOR_AMBIENCE.get(), SoundSource.AMBIENT, Minecraft.getInstance().level.random);
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0F;
        this.pitch = 0.95F;
        this.relative = true;
    }

    public void startFadeOut() {
        if (!this.fadingOut) {
            this.fadingOut = true;
            this.fadeTicks = FADE_OUT_DURATION;
        }
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

        if (fadingOut) {
            fadeTicks--;
            float progress = (float) fadeTicks / (float) FADE_OUT_DURATION;
            this.volume = Math.max(0.0F, targetVolume * progress);
            if (fadeTicks <= 0) {
                this.stop();
            }
        } else {
            if (this.volume < targetVolume) {
                this.volume = Math.min(targetVolume, this.volume + (targetVolume / FADE_IN_DURATION));
            }
        }
    }
}