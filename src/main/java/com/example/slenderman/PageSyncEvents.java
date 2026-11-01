package com.example.slenderman;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * One second after a player joins the world (or respawns) the server tells the client how many pages the player has,
 * so the right ambient music starts at once instead of staying silent until the next page is taken.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public class PageSyncEvents {
    /** after a DEATH the new player starts with no pages: the pages are collected again from the start, and Slenderman is gone until the first one. */
    @SubscribeEvent
    public static void onRespawn(net.minecraftforge.event.entity.player.PlayerEvent.PlayerRespawnEvent event) {
        if (!event.isEndConquered() && event.getEntity() instanceof ServerPlayer player) {
            SlenderPages.clearForNewLife(player);
        }
    }

    @SubscribeEvent
    public static void onClone(net.minecraftforge.event.entity.player.PlayerEvent.Clone event) {
        if (event.isWasDeath() && event.getEntity() instanceof ServerPlayer player) {
            SlenderPages.clearForNewLife(player);
        }
    }

    /** a new player object starts counting from 0 on join and on respawn; 20 ticks = 1 second later we sync. */
    private static final int SYNC_AT_TICK = 20;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.side.isServer()
                && event.player instanceof ServerPlayer player && player.tickCount == SYNC_AT_TICK) {
            SlenderPages.sync(player);
        }
    }
}
