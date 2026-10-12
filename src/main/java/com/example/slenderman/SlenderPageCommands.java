package com.example.slenderman;

import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Test helper: /slenderpages reset  sets your collected pages back to 0 (needs operator rights / cheats). */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public class SlenderPageCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("slenderpages")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reset").executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    SlenderPages.reset(player);
                    context.getSource().sendSuccess(Component.literal("Pages: 0 of " + SlenderPages.TOTAL), false);
                    return 1;
                })));
    }
}
