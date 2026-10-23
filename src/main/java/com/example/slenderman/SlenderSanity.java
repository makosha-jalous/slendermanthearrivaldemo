package com.example.slenderman;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Sanity LOSS of a player, 0..100 (100 = full loss: Slenderman kills with one hit).
 * It is stored in the player's own data (key "slenderman_sanity"), like the pages.
 * The Slenderman entity changes it every tick; the same value is synced to the client through
 * SlendermanEntity.getSanity(), so camerahud can read it.
 *
 * The numbers are taken from the original Slender: The Arrival (SlenderMan.cs). There the game runs 50 steps
 * per second and every step while he is on screen:
 *     with a focused flashlight:  sanity -= 2^(-dist / 10)
 *     without it:                 sanity -= 2^(-dist * 2 / 10)
 *     when he is not on screen:   sanity += 0.1
 * Minecraft has 20 ticks per second, so every value is multiplied by 50 / 20 = 2.5.
 */
public final class SlenderSanity {
    public static final float MAX = 100.0F;
    private static final String KEY = "slenderman_sanity";

    /** original game: 50 steps per second, Minecraft: 20 ticks per second. */
    private static final double STEP_RATIO = 50.0D / 20.0D;

    /** recovery (not looking at him): 0.1 per original step = 0.25 per tick, always the same speed (about 20 s from 100). */
    public static final float RECOVER_PER_TICK = (float) (0.1D * STEP_RATIO);

    private SlenderSanity() {}

    public static float get(Player player) {
        return player.getPersistentData().getFloat(KEY);
    }

    public static void set(Player player, float value) {
        float clamped = Mth.clamp(value, 0.0F, MAX);
        if (clamped != get(player)) {
            player.getPersistentData().putFloat(KEY, clamped);
        }
    }

    public static void reset(Player player) {
        player.getPersistentData().putFloat(KEY, 0.0F);
    }

    /**
     * loss per tick at this distance, flashlight ON (the harder case of the original: he hurts from far away).
     * Right next to him it is 2.5 per tick = about 2 s to 100; at 10 blocks 1.25 per tick = 4 s; at 20 blocks 8 s.
     */
    public static float lossPerTick(double dist) {
        return lossPerTick(dist, true);
    }

    /**
     * loss per tick. flashlightOn = the flashlight is on and pointed at him (in the original: isOnAndFocused).
     * Without the flashlight the loss falls twice as fast with distance: 10 blocks = 0.625 per tick (8 s).
     */
    public static float lossPerTick(double dist, boolean flashlightOn) {
        double d = Math.max(0.0D, dist);
        double perStep = Math.pow(2.0D, -(flashlightOn ? d : d * 2.0D) / 10.0D);
        return (float) (perStep * STEP_RATIO);
    }

    /** loss points per tick for a recovery (the original has one constant speed, so it does not depend on the loss). */
    public static float recoverPerTick(float loss) {
        return RECOVER_PER_TICK;
    }

    /** how many seconds the recovery takes from this loss. */
    public static double recoverSeconds(float loss) {
        return loss / (RECOVER_PER_TICK * 20.0D);
    }
}
