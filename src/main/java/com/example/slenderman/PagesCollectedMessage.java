package com.example.slenderman;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Server -> client: "the player now has this many pages".
 * pickup = true: the player has just taken a page (the client shows the text for 4 seconds and plays the pickup sound).
 * pickup = false: a silent sync (join, respawn, /slenderpages reset): only the ambient music is set to match the count.
 */
public class PagesCollectedMessage {
    private final int count;
    private final boolean pickup;

    public PagesCollectedMessage(int count) {
        this(count, true);
    }

    public PagesCollectedMessage(int count, boolean pickup) {
        this.count = count;
        this.pickup = pickup;
    }

    public static void encode(PagesCollectedMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.count);
        buffer.writeBoolean(message.pickup);
    }

    public static PagesCollectedMessage decode(FriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        boolean pickup = buffer.readBoolean();
        return new PagesCollectedMessage(count, pickup);
    }

    public static void handle(PagesCollectedMessage message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    if (message.pickup) {
                        com.example.slenderman.client.PageHud.show(message.count);
                    }
                    com.example.slenderman.client.PageAudio.onPages(message.count, message.pickup);
                }));
        context.get().setPacketHandled(true);
    }
}
