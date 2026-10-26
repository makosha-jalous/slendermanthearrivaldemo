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

    /** recovery (looking away): a loss close to 100 comes back in 15 s, a small loss in about 10 s. */
    public static final double RECOVER_SECONDS_SMALL = 10.0D;
    public static final double RECOVER_SECONDS_FULL = 15.0D;
    /** recovery speed-up against the old times above (1.4 = 40% faster). */
    public static final double RECOVER_SPEEDUP = 1.4D;
    /** kept for older code: the speed of a full recovery from 100. */
    public static final float RECOVER_PER_TICK = MAX / 300.0F;

    /** how many seconds the recovery takes from this loss (10 s at a tiny loss ... 15 s at 100). */
    public static double recoverSeconds(float loss) {
        // recovery is 40% faster than before (the loss formula is unchanged)
        return Mth.lerp(Mth.clamp(loss / MAX, 0.0F, 1.0F), RECOVER_SECONDS_SMALL, RECOVER_SECONDS_FULL) / RECOVER_SPEEDUP;
    }

    /** loss points per tick for a recovery that starts at this loss (constant until it reaches 0). */
    public static float recoverPerTick(float loss) {
        return (float) (loss / (recoverSeconds(loss) * 20.0D));
    }

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

    // ---- the formula of the original game (Slender: The Arrival, SlenderMan.LowerSanity) ----
    // The original loses  2^(-distance * 2 / 10)  points of sanity (of 100) in EVERY physics step while he is in view
    // (flashlight not focused). Unity makes 50 physics steps per second (0.02 s), Minecraft makes 20 ticks per second,
    // so one tick has to take 50 / 20 = 2.5 steps. In points per second: 50 * 2^(-distance / 5).
    //   distance  2 blocks:  2.6 s from 0 to 100      20 blocks:  32 s
    //   distance  5 blocks:  4 s                      25 blocks:  64 s
    //   distance 10 blocks:  8 s                      30 blocks: 128 s   (far away it is very slow, as in the original)
    //   distance 15 blocks: 16 s                      40 blocks: 512 s
    /** true: the exact curve of the original game; false: the older smooth curve with the times above (60 s at the most). */
    public static final boolean ORIGINAL_CURVE = true;
    /** physics steps per second of the original game (Unity default 0.02 s) and Minecraft ticks per second. */
    public static final double ORIGINAL_STEPS_PER_SECOND = 50.0D;
    public static final double TICKS_PER_SECOND = 20.0D;
    /** the distance (blocks) in which the loss halves; the original uses 5 (2^(-distance*2/10)). */
    public static final double HALVING_DIST = 5.0D;

    /** points of loss per Minecraft tick while he is in view, by the distance in blocks. */
    public static float lossPerTick(double dist) {
        if (ORIGINAL_CURVE) {
            return (float) (ORIGINAL_STEPS_PER_SECOND / TICKS_PER_SECOND * Math.pow(2.0D, -dist / HALVING_DIST));
        }
        return oldLossPerTick(dist);
    }

    /** smooth: the time falls from 60 s (far) to 30 s (middle) to 5 s (close) without jumps. */
    private static float oldLossPerTick(double dist) {
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
