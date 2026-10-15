package com.example.slenderman;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.PacketDistributor;

/**
 * How many pages a player has collected. It is stored in the player's own data (key "slenderman_pages"),
 * so it is saved with the world. Other classes (for example the Slenderman AI) can read it with get(player).
 */
public final class SlenderPages {
    /** how many pages there are. */
    public static final int TOTAL = 8;
    private static final String KEY = "slenderman_pages";

    private SlenderPages() {}

    public static int get(Player player) {
        return player.getPersistentData().getInt(KEY);
    }

    /** the player picks up one more page: count it and tell the client to show "pages N of 8 collected". */
    public static void collect(ServerPlayer player) {
        int count = Math.min(TOTAL, get(player) + 1);
        player.getPersistentData().putInt(KEY, count);
        SlenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new PagesCollectedMessage(count));
        // every page makes him angrier: the Slenderman(s) around prepare an ambush
        for (SlendermanEntity slender : player.level.getEntitiesOfClass(SlendermanEntity.class,
                player.getBoundingBox().inflate(256.0D))) {
            slender.onPageTaken(count);
        }
    }

    /** silently tells the client how many pages there are (no text, no pickup sound): only the ambient music follows it. */
    public static void sync(ServerPlayer player) {
        SlenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PagesCollectedMessage(get(player), false));
    }

    public static void reset(ServerPlayer player) {
        player.getPersistentData().putInt(KEY, 0);
        sync(player);
    }
}
