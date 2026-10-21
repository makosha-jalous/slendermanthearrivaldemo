package com.example.slenderman;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * Test modes of a player (set with the /slender commands):
 *  - god:   the sanity is never lost, Slenderman cannot hurt or kill (no death scene);
 *  - watch: Slenderman only watches from far away (50-60+ blocks), nothing is lost, he vanishes when the player comes closer.
 * The flags live in the part of the player data that survives death ("PlayerPersisted").
 */
public final class SlenderModes {
    private static final String ROOT = "PlayerPersisted";
    private static final String GOD = "slenderman_god";
    private static final String WATCH = "slenderman_watch";

    private SlenderModes() {}

    private static CompoundTag tag(Player player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(ROOT)) data.put(ROOT, new CompoundTag());
        return data.getCompound(ROOT);
    }

    public static boolean isGod(Player player) { return tag(player).getBoolean(GOD); }

    public static boolean isWatch(Player player) { return tag(player).getBoolean(WATCH); }

    public static void setGod(Player player, boolean on) { tag(player).putBoolean(GOD, on); }

    public static void setWatch(Player player, boolean on) { tag(player).putBoolean(WATCH, on); }
}
