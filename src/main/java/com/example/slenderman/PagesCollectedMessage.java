package com.example.slenderman;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client: "the player now has this many pages", the client shows the text for 4 seconds. */
public class PagesCollectedMessage {
    private final int count;

    public PagesCollectedMessage(int count) {
        this.count = count;
    }

    public static void encode(PagesCollectedMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.count);
    }

    public static PagesCollectedMessage decode(FriendlyByteBuf buffer) {
        return new PagesCollectedMessage(buffer.readVarInt());
    }

    public static void handle(PagesCollectedMessage message, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.example.slenderman.client.PageHud.show(message.count)));
        context.get().setPacketHandled(true);
    }
}
