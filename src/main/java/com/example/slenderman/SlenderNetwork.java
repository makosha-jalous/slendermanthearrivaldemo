package com.example.slenderman;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** The one network channel of the Slenderman mod (server -> client messages). */
public final class SlenderNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(SlendermanMod.ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private SlenderNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, PagesCollectedMessage.class,
                PagesCollectedMessage::encode, PagesCollectedMessage::decode, PagesCollectedMessage::handle);
    }
}
