package com.example.slenderman.client;

import com.example.slenderman.StepSoundRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

public class StereoFootstepSoundInstance extends AbstractTickableSoundInstance {
    public enum StereoMode {
        ALWAYS_LEFT,
        ALWAYS_RIGHT,
        LEFT_TO_RIGHT,
        RIGHT_TO_LEFT
    }

    private final StereoMode mode;
    private final int maxTicks;
    private int currentTicks = 0;

    public StereoFootstepSoundInstance(StereoMode mode, float volume, float pitch, int durationTicks) {
        super(StepSoundRegistry.FOOTSTEPS.get(), SoundSource.HOSTILE, Minecraft.getInstance().level.random);
        this.mode = mode;
        this.volume = volume;
        this.pitch = pitch;
        this.maxTicks = Math.max(20, durationTicks);
        this.looping = false;
        this.delay = 0;
        this.relative = false;

        updatePosition(0.0F);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            this.stop();
            return;
        }

        currentTicks++;
        if (currentTicks >= maxTicks) {
            this.stop();
            return;
        }

        float progress = (float) currentTicks / (float) maxTicks;
        updatePosition(progress);
    }

    private void updatePosition(float progress) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Vec3 playerPos = mc.player.getEyePosition();
        Vec3 look = mc.player.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0, look.x).normalize();

        double offsetDistance = 3.5D;
        double currentOffset = 0.0D;

        switch (mode) {
            case ALWAYS_LEFT -> currentOffset = -offsetDistance;
            case ALWAYS_RIGHT -> currentOffset = offsetDistance;
            case LEFT_TO_RIGHT -> currentOffset = -offsetDistance + (offsetDistance * 2.0D * progress);
            case RIGHT_TO_LEFT -> currentOffset = offsetDistance - (offsetDistance * 2.0D * progress);
        }

        Vec3 soundPos = playerPos.add(right.scale(currentOffset));
        this.x = soundPos.x;
        this.y = soundPos.y;
        this.z = soundPos.z;
    }
}