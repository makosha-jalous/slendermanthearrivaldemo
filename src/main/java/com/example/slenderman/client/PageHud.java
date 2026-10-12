package com.example.slenderman.client;

import com.example.slenderman.SlenderPages;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The text "pages N of 8 collected" near the top of the screen. It stays for 4 seconds after every pickup
 * (a new pickup restarts it with the new number) and fades in and out softly.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public class PageHud {
    /** how long the text stays: 80 ticks = 4 seconds. */
    private static final int SHOW_TICKS = 80;
    private static final float FADE_IN_TICKS = 4.0F;
    private static final float FADE_OUT_TICKS = 12.0F;
    /** height of the text as a part of the screen height (0 = top edge, 0.5 = middle). */
    private static final float TEXT_Y = 0.14F;
    private static final float TEXT_SCALE = 2.0F;

    private static int ticksLeft;
    private static int shownCount;

    /** called when the server says the player collected a page. */
    public static void show(int count) {
        shownCount = count;
        ticksLeft = SHOW_TICKS;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ticksLeft > 0 && !Minecraft.getInstance().isPaused()) {
            ticksLeft--;
        }
    }

    /** registered as a gui overlay in PageClientSetup. */
    public static void render(ForgeGui gui, PoseStack poseStack, float partialTick, int width, int height) {
        if (ticksLeft <= 0) return;

        float left = ticksLeft - partialTick;
        float passed = SHOW_TICKS - left;
        float fade = 1.0F;
        if (passed < FADE_IN_TICKS) fade = passed / FADE_IN_TICKS;
        if (left < FADE_OUT_TICKS) fade = Math.min(fade, left / FADE_OUT_TICKS);
        int alpha = (int) (Math.max(0.0F, Math.min(1.0F, fade)) * 255.0F);
        if (alpha < 8) return;   // very low alpha would be drawn fully solid by the font renderer

        Font font = Minecraft.getInstance().font;
        String text = "pages " + shownCount + " of " + SlenderPages.TOTAL + " collected";

        poseStack.pushPose();
        poseStack.translate(width / 2.0F, height * TEXT_Y, 0.0F);
        poseStack.scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        font.drawShadow(poseStack, text, -font.width(text) / 2.0F, 0.0F, (alpha << 24) | 0xFFFFFF);
        poseStack.popPose();
    }
}
