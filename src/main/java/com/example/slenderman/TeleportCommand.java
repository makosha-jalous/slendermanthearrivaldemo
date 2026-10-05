package com.example.slenderman;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.List;

/**
 * /slenderteleport &lt;far|mid|close|vanish&gt;   - teleports the nearest Slenderman (160 blocks) next to you
 * /slenderteleport auto on|off                 - switches his automatic teleports on or off
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID)
public class TeleportCommand {

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("slenderteleport")
                .requires(source -> source.hasPermission(2));

        root.then(Commands.argument("kind", StringArgumentType.word())
                .suggests((context, builder) ->
                        SharedSuggestionProvider.suggest(new String[]{"far", "mid", "close", "vanish"}, builder))
                .executes(context -> run(context.getSource(), StringArgumentType.getString(context, "kind"))));

        root.then(Commands.literal("auto")
                .then(Commands.literal("on").executes(context -> {
                    SlendermanEntity.autoTeleport = true;
                    context.getSource().sendSuccess(Component.literal("Slenderman auto teleport: on"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(context -> {
                    SlendermanEntity.autoTeleport = false;
                    context.getSource().sendSuccess(Component.literal("Slenderman auto teleport: off"), false);
                    return 1;
                })));

        event.getDispatcher().register(root);
    }

    private static int run(CommandSourceStack source, String kindName) {
        SlendermanEntity.TeleportKind kind;
        try {
            kind = SlendermanEntity.TeleportKind.valueOf(kindName.toUpperCase());
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Use: far, mid, close or vanish"));
            return 0;
        }
        try {
            ServerPlayer player = source.getPlayerOrException();
            List<SlendermanEntity> found = player.level.getEntitiesOfClass(SlendermanEntity.class,
                    player.getBoundingBox().inflate(160.0D));
            if (found.isEmpty()) {
                source.sendFailure(Component.literal("No Slenderman within 160 blocks"));
                return 0;
            }
            SlendermanEntity nearest = found.stream()
                    .min(Comparator.comparingDouble(s -> s.distanceToSqr(player)))
                    .get();
            boolean ok = nearest.teleportNear(player, kind);
            if (ok) {
                source.sendSuccess(Component.literal("Slenderman teleported: " + kindName), false);
            } else {
                source.sendFailure(Component.literal("No safe place found, try again"));
            }
            return ok ? 1 : 0;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal("Only a player can use this"));
            return 0;
        }
    }
}
