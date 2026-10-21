package com.example.slenderman;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * /slender god [on|off]    - God Mode: no sanity loss, no damage, no death scene
 * /slender kill            - kills every Slenderman and resets the pages of all players
 * /slender watch [on|off]  - watcher mode: he only watches from far away, nothing is lost
 * (operators only). Without on/off the mode is switched over.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public final class SlenderModeCommands {
    private SlenderModeCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("slender").requires(s -> s.hasPermission(2))
                .then(Commands.literal("god")
                        .executes(c -> god(c, null))
                        .then(Commands.literal("on").executes(c -> god(c, true)))
                        .then(Commands.literal("off").executes(c -> god(c, false))))
                .then(Commands.literal("kill").executes(SlenderModeCommands::kill))
                .then(Commands.literal("watch")
                        .executes(c -> watch(c, null))
                        .then(Commands.literal("on").executes(c -> watch(c, true)))
                        .then(Commands.literal("off").executes(c -> watch(c, false)))));
    }

    private static int god(CommandContext<CommandSourceStack> c, Boolean value) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        boolean on = value != null ? value : !SlenderModes.isGod(player);
        SlenderModes.setGod(player, on);
        if (on) SlenderSanity.reset(player);
        c.getSource().sendSuccess(Component.literal("God Mode: " + (on ? "ВКЛ (рассудок не теряется, умереть нельзя)" : "ВЫКЛ")), false);
        return 1;
    }

    private static int watch(CommandContext<CommandSourceStack> c, Boolean value) throws CommandSyntaxException {
        ServerPlayer player = c.getSource().getPlayerOrException();
        boolean on = value != null ? value : !SlenderModes.isWatch(player);
        SlenderModes.setWatch(player, on);
        if (on) {
            SlenderSanity.reset(player);
            SlenderPages.ensureSlenderman(player);     // he has to exist to watch
        }
        c.getSource().sendSuccess(Component.literal("Режим смотрителя: " + (on ? "ВКЛ (он наблюдает издалека)" : "ВЫКЛ")), false);
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> c) {
        int count = 0;
        for (ServerLevel level : c.getSource().getServer().getAllLevels()) {
            List<SlendermanEntity> all = new ArrayList<>();
            for (SlendermanEntity s : level.getEntities(EntityTypeTest.forClass(SlendermanEntity.class), e -> true)) all.add(s);
            for (SlendermanEntity s : all) {
                s.discard();
                count++;
            }
        }
        // there is no Slenderman any more: the pages are gone too
        for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
            SlenderPages.reset(p);
        }
        final int killed = count;
        c.getSource().sendSuccess(Component.literal("Slenderman убит (" + killed + "), страницы сброшены"), true);
        return killed;
    }
}
