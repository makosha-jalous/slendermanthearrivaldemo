package com.example.slenderman;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * The link to the flashlight mod (slenderflashlight), WITHOUT depending on its classes.
 *
 * The flashlight mod tells the server (a packet from the client) in what state the flashlight of the player is, and the server
 * writes it into the player's own data under these keys. Slenderman only reads them here:
 *   state 0 = no lit flashlight in the main hand, 1 = lit, 2 = lit AND focused (the left mouse button is held).
 * The state counts only while it is fresh (the client repeats it every 10 ticks); a stale value means "no flashlight".
 * If the flashlight mod is not installed, the state is always 0 (the original game without a focused flashlight).
 */
public final class FlashlightLink {
    public static final String STATE_KEY = "slenderflashlight_state";
    public static final String TIME_KEY = "slenderflashlight_state_time";
    /** how long (ticks) a received state stays valid. */
    private static final long FRESH_TICKS = 40L;

    public static final int NONE = 0;
    public static final int ON = 1;
    public static final int FOCUSED = 2;

    private FlashlightLink() {}

    public static int state(Player player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(STATE_KEY)) return NONE;
        long age = player.level.getGameTime() - data.getLong(TIME_KEY);
        if (age < 0L || age > FRESH_TICKS) return NONE;
        return data.getInt(STATE_KEY);
    }

    /** the lit flashlight is in the main hand. */
    public static boolean isOn(Player player) {
        return state(player) >= ON;
    }

    /** the original flashlight.isOnAndFocused(): lit and concentrated. */
    public static boolean isOnAndFocused(Player player) {
        return state(player) >= FOCUSED;
    }
}
