package com.example.slenderman;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.BlockPos;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/**
 * Client-side indoor ambience.
 *
 * Requires a registered sound event named "bassambiance" and the file
 * assets/slenderman/sounds/bassambiance.ogg.
 *
 * This implementation detects Slendermen currently tracked by the client.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class IndoorAmbientHandler {
    private static final float MAX_VOLUME = 0.60F; // 40% quieter than the original
    private static final int FADE_IN_TICKS = 40;   // 2 seconds at 20 TPS
    private static final int FADE_OUT_TICKS = 60;  // 3 seconds at 20 TPS

    private static IndoorAmbientSound activeSound;
    private static boolean wasEligible;

    private IndoorAmbientHandler() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            stopAndClear(mc);
            return;
        }

        boolean slendermanPresent = mc.level.entitiesForRendering().anyMatch(
                entity -> entity instanceof SlendermanEntity && entity.isAlive()
        );

        BlockPos playerPos = mc.player.blockPosition();
        boolean indoors = !mc.level.canSeeSky(playerPos);
        boolean eligible = slendermanPresent && indoors;

        if (eligible) {
            if (activeSound == null || activeSound.isFinished()) {
                activeSound = new IndoorAmbientSound();
                mc.getSoundManager().play(activeSound);
            }
            activeSound.fadeIn();
        } else if (activeSound != null && !activeSound.isStopped()) {
            activeSound.fadeOut();
        }

        wasEligible = eligible;
    }

    private static void stopAndClear(Minecraft mc) {
        if (activeSound != null) {
            activeSound.stopSound();
            activeSound = null;
        }
        wasEligible = false;
    }

    private static final class IndoorAmbientSound extends AbstractTickableSoundInstance {
        private int fadeTicks = 0;
        private int fadeOutTicks = -1;
        private boolean stopped = false;

        private IndoorAmbientSound() {
            super(SlendermanMod.BASS_AMBIANCE.get(), SoundSource.AMBIENT, new Random());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0F;
            this.pitch = 1.0F;
            this.x = 0.0D;
            this.y = 0.0D;
            this.z = 0.0D;
            this.relative = true;
        }

        private void fadeIn() {
            fadeOutTicks = -1;
        }

        private void fadeOut() {
            if (fadeOutTicks < 0) fadeOutTicks = 0;
        }

        private void stopSound() {
            stopped = true;
            stop();
        }

        private boolean isStopped() {
            return stopped || isStoppedByManager();
        }

        @Override
        public void tick() {
            if (fadeOutTicks >= 0) {
                fadeOutTicks++;
                volume = MAX_VOLUME * Math.max(0.0F, 1.0F - (float) fadeOutTicks / FADE_OUT_TICKS);
                if (fadeOutTicks >= FADE_OUT_TICKS) {
                    stopped = true;
                    stop();
                }
            } else {
                if (fadeTicks < FADE_IN_TICKS) fadeTicks++;
                volume = MAX_VOLUME * Math.min(1.0F, (float) fadeTicks / FADE_IN_TICKS);
            }
        }

        private boolean isStoppedByManager() {
            return stopped;
        }
    }
}
