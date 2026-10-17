package com.example.slenderman;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

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
        // from the first page on he is in the world (also on peaceful difficulty)
        if (count < TOTAL) {
            SlendermanEntity fresh = ensureSlenderman(player);
            if (fresh != null) fresh.onPageTaken(count);
        }
        // every page makes him angrier: the Slenderman(s) around prepare an ambush
        for (SlendermanEntity slender : player.level.getEntitiesOfClass(SlendermanEntity.class,
                player.getBoundingBox().inflate(256.0D))) {
            slender.onPageTaken(count);
        }
    }

    /** if there is no Slenderman around the player yet, one appears 35-40 blocks away. Returns the new one, or null. */
    private static SlendermanEntity ensureSlenderman(ServerPlayer player) {
        List<SlendermanEntity> existing = player.level.getEntitiesOfClass(SlendermanEntity.class,
                player.getBoundingBox().inflate(256.0D));
        if (!existing.isEmpty()) return null;
        SlendermanEntity slender = SlendermanMod.SLENDERMAN.get().create(player.level);
        if (slender == null) return null;
        if (!slender.teleportNear(player, SlendermanEntity.TeleportKind.WATCH)) {
            // no free spot found: 30 blocks away on the ground
            double angle = player.getRandom().nextDouble() * Math.PI * 2.0D;
            double x = player.getX() + Math.cos(angle) * 30.0D;
            double z = player.getZ() + Math.sin(angle) * 30.0D;
            double y = player.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            slender.moveTo(x, y, z, 0.0F, 0.0F);
        }
        player.level.addFreshEntity(slender);
        return slender;
    }

    /** silently tells the client how many pages there are (no text, no pickup sound): only the ambient music follows it. */
    public static void sync(ServerPlayer player) {
        SlenderNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new PagesCollectedMessage(get(player), false));
    }

    public static void reset(ServerPlayer player) {
        player.getPersistentData().putInt(KEY, 0);
        SlenderSanity.reset(player);
        sync(player);
    }
}
