package com.example.slenderman;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * Sanity LOSS of a player, 0..100 (100 = full loss: Slenderman kills with one hit).
 * It is stored in the player's own data (key "slenderman_sanity"), like the pages.
 * The Slenderman entity changes it every tick; the same value is synced to the client through
 * SlendermanEntity.getSanity(), so camerahud can read it.
 */
public final class SlenderSanity {
    public static final float MAX = 100.0F;
    private static final String KEY = "slenderman_sanity";

    // ---- seconds of looking at him until the loss goes from 0 to 100 ----
    /** closer than this: NEAR_SECONDS. */
    public static final double NEAR_DIST = 5.0D;
    public static final double NEAR_SECONDS = 4.0D;
    /** at this distance: MID_SECONDS. */
    public static final double MID_DIST = 15.0D;
    public static final double MID_SECONDS = 20.0D;
    /** at this distance and farther: FAR_SECONDS. */
    public static final double FAR_DIST = 40.0D;
    public static final double FAR_SECONDS = 60.0D;

    /** recovery: from 100 back to 0 in 15 s (a smaller loss comes back faster, in proportion). */
    public static final float RECOVER_PER_TICK = MAX / 300.0F;

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

    /** smooth: the time falls from 60 s (far) to 30 s (middle) to 5 s (close) without jumps. */
    public static float lossPerTick(double dist) {
        double seconds;
        if (dist <= NEAR_DIST) {
            seconds = NEAR_SECONDS;
        } else if (dist <= MID_DIST) {
            seconds = Mth.lerp(smooth((dist - NEAR_DIST) / (MID_DIST - NEAR_DIST)), NEAR_SECONDS, MID_SECONDS);
        } else if (dist <= FAR_DIST) {
            seconds = Mth.lerp(smooth((dist - MID_DIST) / (FAR_DIST - MID_DIST)), MID_SECONDS, FAR_SECONDS);
        } else {
            seconds = FAR_SECONDS;
        }
        return (float) (MAX / (seconds * 20.0D));
    }

    private static double smooth(double t) {
        t = Mth.clamp(t, 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }
}
