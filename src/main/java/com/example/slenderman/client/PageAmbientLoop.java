package com.example.slenderman.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * One endless ambient loop of a page group. It is not tied to any place (plays the same in both ears everywhere),
 * fades in when it starts and fades out when the next group replaces it. The audio file itself is a seamless loop.
 */
public class PageAmbientLoop extends AbstractTickableSoundInstance {
    private float step;
    private boolean fadingOut;

    public PageAmbientLoop(SoundEvent event, int fadeInTicks) {
        super(event, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
        this.looping = true;
        this.delay = 0;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.x = 0.0D;
        this.y = 0.0D;
        this.z = 0.0D;
        this.volume = fadeInTicks > 0 ? 0.0F : 1.0F;
        this.step = 1.0F / Math.max(1, fadeInTicks);
    }

    /** the loop gets quieter and stops by itself when it is silent. */
    public void fadeOut(int ticks) {
        this.fadingOut = true;
        this.step = 1.0F / Math.max(1, ticks);
    }

    @Override
    public void tick() {
        if (this.fadingOut) {
            this.volume -= this.step;
            if (this.volume <= 0.0F) {
                this.volume = 0.0F;
                this.stop();
            }
        } else if (this.volume < 1.0F) {
            this.volume = Math.min(1.0F, this.volume + this.step);
        }
    }
}
