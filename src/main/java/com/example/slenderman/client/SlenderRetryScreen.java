package com.example.slenderman.client;

import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.sound.PlaySoundEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Random;

/**
 * THE RETRY MENU. It replaces the vanilla death screen completely: a black screen with the words RETRY? / QUIT, the grain of
 * the camera on top, and the fisheye of the camera (the same dice and the same 20 ms steps as the portrait) bending the letters.
 * The word under the mouse gets its streaks (only one of them at a time). A click is a flash of grain, then the action.
 * No sounds at all, no vanilla buttons, no clicks.
 */
public class SlenderRetryScreen extends Screen {
    private static final ResourceLocation NORMAL = new ResourceLocation(SlendermanMod.ID, "textures/gui/retry_normal.png");
    private static final ResourceLocation HOVER_RETRY = new ResourceLocation(SlendermanMod.ID, "textures/gui/retry_hover_retry.png");
    private static final ResourceLocation HOVER_QUIT = new ResourceLocation(SlendermanMod.ID, "textures/gui/retry_hover_quit.png");

    /** the place of the words inside the 512 x 256 picture (pixels): RETRY? and QUIT, with a little margin. */
    private static final int[] BOX_RETRY = {100, 56, 412, 134};
    private static final int[] BOX_QUIT = {180, 138, 298, 186};
    /** how wide the picture is on the screen (share of the screen width). */
    private static final float WIDTH_SHARE = 0.62F;
    /** the calm grain, and how long the flash of grain after a click lasts (ms). */
    private static final float GRAIN = 0.20F;
    private static final long FLASH_MS = 280L;

    private static final Random RND = new Random();

    private int hover = 0;            // 0 nothing, 1 retry, 2 quit
    private int chosen = 0;
    private long clickMs = 0L;

    public SlenderRetryScreen() {
        super(Component.empty());
    }

    @Override
    protected void init() {
        DeathSequenceClient.retryBegin();
        try {
            this.minecraft.getSoundManager().stop();         // nothing is heard here
        } catch (Throwable ignored) {
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- where the picture is on the screen
    private int picW() { return Math.max(64, Math.round(this.width * WIDTH_SHARE)); }
    private int picX() { return (this.width - picW()) / 2; }
    private int picY() { return Math.round(this.height * 0.5F - picW() / 4F); }

    private int hitTest(double mx, double my) {
        float k = picW() / 512F;
        float px = (float) ((mx - picX()) / k), py = (float) ((my - picY()) / k);
        if (px >= BOX_RETRY[0] && px <= BOX_RETRY[2] && py >= BOX_RETRY[1] && py <= BOX_RETRY[3]) return 1;
        if (px >= BOX_QUIT[0] && px <= BOX_QUIT[2] && py >= BOX_QUIT[1] && py <= BOX_QUIT[3]) return 2;
        return 0;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && this.chosen == 0) {
            int h = hitTest(mx, my);
            if (h != 0) {
                this.chosen = h;
                this.clickMs = System.currentTimeMillis();
            }
        }
        return true;                                           // no vanilla click sound, nothing else reacts
    }

    @Override
    public void render(PoseStack ps, int mx, int my, float pt) {
        long now = System.currentTimeMillis();
        this.hover = this.chosen != 0 ? this.chosen : hitTest(mx, my);
        DeathSequenceClient.retryStep();

        GuiComponent.fill(ps, 0, 0, this.width, this.height, 0xFF000000);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        int x = picX(), y = picY(), w = picW(), h = w / 2;
        RenderSystem.setShaderTexture(0, NORMAL);
        GuiComponent.blit(ps, x, y, w, h, 0F, 0F, 512, 256, 512, 256);
        if (this.hover == 1 || this.hover == 2) {
            RenderSystem.setShaderTexture(0, this.hover == 1 ? HOVER_RETRY : HOVER_QUIT);
            GuiComponent.blit(ps, x, y, w, h, 0F, 0F, 512, 256, 512, 256);
        }

        // the grain of the camera; a click is a flash of it
        float grain = GRAIN * (0.78F + 0.42F * RND.nextFloat());
        if (this.chosen != 0) {
            float t = (now - this.clickMs) / (float) FLASH_MS;
            grain = Math.max(grain, 1.0F - 0.35F * Math.min(1F, t));
        }
        DeathSequenceClient.drawNoise(ps, this.width, this.height, Math.min(1F, grain));
        // the fisheye of the camera bends the letters (and the grain)
        DeathSequenceClient.drawBentScene(ps, this.width, this.height, DeathSequenceClient.retryChroma());
        RenderSystem.disableBlend();

        if (this.chosen != 0 && now - this.clickMs >= FLASH_MS) {
            int c = this.chosen;
            this.chosen = 0;
            act(c);
        }
    }

    private void act(int what) {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        if (what == 1) {
            if (mc.player != null) mc.player.respawn();            // RETRY: the same as the vanilla "Respawn"
            mc.setScreen(null);
        } else {
            // QUIT: the same as the vanilla "Title Screen"
            if (mc.level != null) mc.level.disconnect();
            mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            mc.setScreen(new TitleScreen());
        }
    }

    @Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
    public static final class Events {
        /** the vanilla death screen is never shown: our menu opens in its place. */
        @SubscribeEvent
        public static void onOpening(ScreenEvent.Opening e) {
            if (e.getNewScreen() instanceof DeathScreen) {
                e.setNewScreen(new SlenderRetryScreen());
            }
        }

        /** the menu is silent: no sound starts while it is open. */
        @SubscribeEvent
        public static void onSound(PlaySoundEvent e) {
            if (Minecraft.getInstance().screen instanceof SlenderRetryScreen) e.setSound(null);
        }
    }
}
