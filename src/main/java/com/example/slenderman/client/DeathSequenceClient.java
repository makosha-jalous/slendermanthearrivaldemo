package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.BufferUploader;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.math.Matrix4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client side of the death scene (the server runs it, see SlendermanEntity.getDeathPhase()):
 *  - phase 1: the view turns to his face in exactly 3 s; the mouse has no effect (the view is set absolutely);
 *  - phase 2: sharp black screen for 2 s; every sound is cut off EXCEPT the screamers (dramatic1a/b/c), which play to
 *             the end; when no screamer is playing a light echo fades out;
 *  - phase 3: the portrait (3 s): Slenderman to the chest on black, a dirty green glow behind his head, the head
 *             almost black. Static Medium stutters in pieces, Static Heavy plays in chopped pieces of 80-100 ms with no
 *             pauses, and the picture twitches with every piece. (The sounds are the ones of the slendercam mod.)
 *  - phase 4: black again, every sound dies away; then the real Minecraft death screen.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public final class DeathSequenceClient {
    private static SlendermanEntity killer;
    private static int phase = 0;
    private static int ticks = 0;
    private static float startYaw, startPitch;
    private static boolean gainChanged = false;
    private static int echoTick = 0;
    private static long noiseStartMs = 0L;
    private static DynamicTexture noise;
    private static ResourceLocation noiseLoc;
    private static long lastNoiseMs = 0L;
    private static final Random NOISE_RND = new Random();
    private static final int NOISE_TEX = 256;

    // ---- portrait ----
    /** size of the portrait: pixels per block (as a share of the screen height) and where the top of his head is (share of the height). */
    private static final float PORTRAIT_SCALE = 1.00F;
    private static final float PORTRAIT_TOP = 0.15F;
    /** where the glow and the dark spot of the face sit (share of the height) and the size of the face spot. */
    private static final float PORTRAIT_GLOW_Y = 0.45F;
    private static final float FACE_RX = 0.22F, FACE_RY = 0.30F;
    /** how dark the model is (colour multiplier): the clothes can barely be seen. */
    private static final float MODEL_DARK = 0.20F;
    /** strength of the bending of the picture (share of the screen height). */
    private static final float WARP_STRENGTH = 0.055F;
    private static final Random FX_RND = new Random();
    private static SoundEvent mediumPiece, heavyLoop;
    private static boolean dramaticActive = false;
    private static long mediumNextMs = 0L;
    private static HeavyTrack heavyInst;
    /** Static Heavy of the portrait: ONE track, its volume stutters at changing speeds; every stutter step bends the picture. */
    private static float heavyLevel = 1.0F;
    private static boolean chopHigh = true;
    private static int chopTicks = 0, speedTicks = 0, chopLen = 2;
    private static float pV, pVB, pH, pHB, glowFlick = 1.0F;
    private static RenderTarget sceneCopy;

    private DeathSequenceClient() {}

    private static float[] targetAngles(Minecraft mc, float pt) {
        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 face = killer.getEyePosition(pt);
        double dx = face.x - eye.x, dy = face.y - eye.y, dz = face.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        return new float[]{(float) (Mth.atan2(dz, dx) * 57.29577951308232D) - 90.0F,
                (float) (-(Mth.atan2(dy, flat) * 57.29577951308232D))};
    }

    private static float[] angles(Minecraft mc, float pt) {
        float k = 1.0F;
        if (phase == 1) k = Mth.clamp((ticks + pt) / (float) SlendermanEntity.DEATH_TURN_TICKS, 0.0F, 1.0F);
        k = k * k * (3.0F - 2.0F * k);
        float[] t = targetAngles(mc, pt);
        return new float[]{startYaw + Mth.wrapDegrees(t[0] - startYaw) * k, startPitch + (t[1] - startPitch) * k};
    }

    private static void setGain(float g) {
        try {
            Minecraft.getInstance().getSoundManager().updateSourceVolume(SoundSource.MASTER, g);
            gainChanged = true;
        } catch (Throwable ignored) {
        }
    }

    private static void restoreGain() {
        if (!gainChanged) return;
        gainChanged = false;
        try {
            Minecraft mc = Minecraft.getInstance();
            mc.getSoundManager().updateSourceVolume(SoundSource.MASTER, mc.options.getSoundSourceVolume(SoundSource.MASTER));
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        SlendermanEntity found = null;
        int current = 0;
        if (mc.level != null && mc.player != null) {
            for (Entity en : mc.level.entitiesForRendering()) {
                if (en instanceof SlendermanEntity s) {
                    int p = s.getDeathPhase();
                    if (p > current) {
                        current = p;
                        found = s;
                    }
                }
            }
        }
        int old = phase;
        if (current != phase) {
            phase = current;
            ticks = 0;
            if (current != 0 && old == 0 && mc.player != null) {
                startYaw = mc.player.getYRot();
                startPitch = mc.player.getXRot();
            }
            if (current == 2 && old < 2) {
                // everything is cut off at once, EXCEPT the screamers (they have their own sound category and play to the
                // end). When no screamer is playing, a light echo of the rising sounds is left (the volume stutters down).
                silenceAll();
                dramaticActive = isScreamPlaying();
                echoTick = 0;
                noiseStartMs = System.currentTimeMillis();
            }
            if (current == 3) {
                restoreGain();                    // the echo is over: the portrait has its own sounds
                mediumNextMs = 0L;
                stopHeavy();
                chopHigh = false;
                chopTicks = 0;
                speedTicks = 0;
                heavyLevel = 1.0F;
                newPose();
                try {
                    if (heavyLoop == null) heavyLoop = new SoundEvent(new ResourceLocation("slendercam", "static_heavy"));
                    heavyInst = new HeavyTrack(heavyLoop);
                    mc.getSoundManager().play(heavyInst);
                } catch (Throwable ignored) {
                }
            }
            if (current == 4) {
                stopHeavy();                      // black again: everything dies away
            }
            if (current == 0) {
                stopHeavy();
                restoreGain();
                resetRoamingSounds(mc);
            }
        } else if (current != 0) {
            ticks++;
        }
        killer = found;

        if (phase != 0 && killer != null && mc.player != null) {
            // the player cannot turn the head: the real rotation is overwritten every tick
            float[] a = angles(mc, 1.0F);
            mc.player.setYRot(a[0]);
            mc.player.setXRot(a[1]);
            mc.player.yRotO = a[0];
            mc.player.xRotO = a[1];
        }
        if (phase == 3) stutterTick();
        if (phase == 4) {
            // everything dies away
            float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float k = 1.0F - Mth.clamp((ticks + 1) / (float) SlendermanEntity.DEATH_END_TICKS, 0.0F, 1.0F);
            setGain(master * k);
        }
        if (phase == 2 && !dramaticActive) {
            // echo: repeats that get quieter and quieter
            echoTick++;
            float g = 0.0F;
            int[] hits = {2, 8, 15, 23, 32};
            float[] vol = {0.20F, 0.12F, 0.07F, 0.04F, 0.02F};
            for (int i = 0; i < hits.length; i++) {
                if (echoTick >= hits[i] && echoTick < hits[i] + 3) g = vol[i];
            }
            setGain(g);
        }
    }

    /** stops every sound except the screamers (they are in the category VOICES). */
    private static void silenceAll() {
        try {
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            for (SoundSource src : SoundSource.values()) {
                if (src == SlendermanRoamingSound.SCREAM_SOURCE) continue;
                sm.stop(null, src);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isScreamPlaying() {
        try {
            SoundInstance inst = SlendermanRoamingSound.lastScream;
            return inst != null && Minecraft.getInstance().getSoundManager().isActive(inst);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** the roaming loops were stopped from outside: let every Slenderman start a fresh one when it is needed. */
    private static void resetRoamingSounds(Minecraft mc) {
        if (mc.level == null) return;
        for (Entity en : mc.level.entitiesForRendering()) {
            if (en instanceof SlendermanEntity s) s.roamingSound = null;
        }
    }

    private static float randPose() {
        float v = 0.45F + 0.55F * FX_RND.nextFloat();
        return FX_RND.nextBoolean() ? v : -v;
    }

    /** a new bent pose of the picture (like the camera interface). */
    private static void newPose() {
        pV = randPose();
        pVB = randPose();
        pH = randPose();
        pHB = randPose();
        glowFlick = 0.88F + 0.12F * FX_RND.nextFloat();
    }

    /** one step of the stutter (every client tick during the portrait): the volume jumps between full and low at changing speeds. */
    private static void stutterTick() {
        if (--speedTicks <= 0) {
            speedTicks = 6 + FX_RND.nextInt(10);                // every 0.3-0.8 s the speed changes
            chopLen = 1 + FX_RND.nextInt(3);                    // 1, 2 or 3 ticks per step: fast ... slower
        }
        if (--chopTicks > 0) return;
        chopHigh = !chopHigh;
        chopTicks = chopLen;
        heavyLevel = chopHigh ? 1.0F : 0.10F;
        if (chopHigh) newPose();
    }

    /** the one continuous track of Static Heavy. */
    private static final class HeavyTrack extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
        HeavyTrack(SoundEvent ev) {
            super(ev, SoundSource.MASTER, net.minecraft.util.RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 1.0F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            this.volume = Math.max(0.001F, heavyLevel);
            if (phase != 3) this.stop();
        }
    }

    private static void stopHeavy() {
        if (heavyInst == null) return;
        try {
            Minecraft.getInstance().getSoundManager().stop(heavyInst);
        } catch (Throwable ignored) {
        }
        heavyInst = null;
    }

    /**
     * The sounds of the portrait, checked every rendered frame (the pieces are only 80-160 ms long):
     * Static Medium in pieces (one after another, 125-160 ms). Static Heavy is one continuous track (see stutterTick).
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (phase != 3) return;
        Minecraft mc = Minecraft.getInstance();
        SoundManager sm = mc.getSoundManager();
        long now = System.currentTimeMillis();
        try {
            if (mediumPiece == null) mediumPiece = new SoundEvent(new ResourceLocation("slendercam", "static_medium_piece"));
            if (mediumNextMs == 0L || now - mediumNextMs > 400L) mediumNextMs = now;
            if (now >= mediumNextMs) {
                sm.play(SimpleSoundInstance.forUI(mediumPiece, 0.97F + FX_RND.nextFloat() * 0.06F, 1.0F));
                mediumNextMs = now + 125L + FX_RND.nextInt(36);
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        phase = 0;
        killer = null;
        stopHeavy();
        restoreGain();
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles e) {
        if (phase == 0 || killer == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        float[] a = angles(mc, (float) e.getPartialTick());
        e.setYaw(a[0]);
        e.setPitch(a[1]);
    }

    /**
     * The black screen (drawn after ALL overlays, so nothing shows through) with the camera noise on it:
     * at the very start a silent flash of noise, then a light flickering noise, the screen stays black.
     */
    @SubscribeEvent
    public static void onGuiPost(RenderGuiEvent.Post e) {
        if (phase < 2) return;
        int w = e.getWindow().getGuiScaledWidth();
        int h = e.getWindow().getGuiScaledHeight();
        PoseStack ps = e.getPoseStack();
        GuiComponent.fill(ps, 0, 0, w, h, 0xFF000000);
        if (phase == 4) return;                       // the last black screen: nothing but black
        if (phase == 3) {
            drawPortrait(ps, w, h);
            if (chopHigh) drawBentScene(ps, w, h);              // the picture bends together with the stutter of Static Heavy, like the camera
            // the grain of the camera at its strongest stage (20% weaker, with a flicker)
            drawNoise(ps, w, h, 0.40F * (0.78F + 0.42F * FX_RND.nextFloat()));
            return;
        }

        long now = System.currentTimeMillis();
        long t = now - noiseStartMs;
        float alpha;
        if (t < 260L) {
            alpha = 0.88F + 0.08F * NOISE_RND.nextFloat();                         // the flash
        } else if (t < 520L) {
            float k = (t - 260L) / 260F;                                           // flash dies down to the light level
            alpha = Mth.lerp(k, 0.60F, 0.12F) * (0.8F + 0.4F * NOISE_RND.nextFloat());
        } else {
            // light noise that flickers: the level changes every 70 ms, now and then a stronger flicker
            long bucket = now / 70L;
            Random r = new Random(bucket * 7919L);
            alpha = 0.05F + 0.09F * r.nextFloat();
            if (r.nextFloat() < 0.12F) alpha += 0.08F;
        }
        drawNoise(ps, w, h, Mth.clamp(alpha * 0.8F, 0F, 0.97F));
    }

    /**
     * Slenderman seen straight from the front, close: the head in the middle and the start of the shoulders, nothing
     * lower. He does not move at all. He is in the dark: only the clothes can be guessed, the face is black. Behind the
     * head a weak dirty green glow (an overlay only, no light source).
     */
    private static void drawPortrait(PoseStack ps, int w, int h) {
        float cx = w * 0.5F;
        float cy = h * PORTRAIT_GLOW_Y;
        drawGlow(ps, cx, cy, h * 0.60F, h * 0.55F, 0.46F * glowFlick, 0.40F, 0.48F, 0.20F);
        if (killer == null) return;
        try {
            int scale = Math.round(h * PORTRAIT_SCALE);
            int x = Math.round(cx);
            int y = Math.round(h * PORTRAIT_TOP + 4.0F * scale);       // the feet are far below the screen: only the head and the shoulders show
            RenderSystem.setShaderColor(MODEL_DARK, MODEL_DARK, MODEL_DARK, 1.0F);
            InventoryScreen.renderEntityInInventory(x, y, scale, 0.0F, 0.0F, killer);    // 0 / 0: exactly from the front
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        // the face is in the dark: a black spot over the head (soft edge)
        drawGlow(ps, cx, cy, h * FACE_RX, h * FACE_RY, 0.97F, 0.0F, 0.0F, 0.0F);
    }

    /**
     * The same bending as the camera: the picture that is on the screen (black, glow and Slenderman) is copied and drawn
     * back as a net of small squares whose corners are moved - like the interface frame and the world in the camera.
     */
    private static void drawBentScene(PoseStack ps, int w, int h) {
        try {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.getMainRenderTarget();
            int tw = main.width, th = main.height;
            if (sceneCopy == null) {
                sceneCopy = new TextureTarget(tw, th, false, Minecraft.ON_OSX);
                sceneCopy.setFilterMode(GL11.GL_LINEAR);
            } else if (sceneCopy.width != tw || sceneCopy.height != th) {
                sceneCopy.resize(tw, th, Minecraft.ON_OSX);
            }
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, sceneCopy.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, tw, th, 0, 0, tw, th, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            main.bindWrite(false);

            RenderSystem.disableBlend();
            RenderSystem.disableCull();
            RenderSystem.colorMask(true, true, true, false);
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(0, sceneCopy.getColorTextureId());
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            Matrix4f m = ps.last().pose();
            final int cols = 36, rows = 22;
            float x0 = -0.06F * w, x1 = 1.06F * w, y0 = -0.06F * h, y1 = 1.06F * h;
            for (int j = 0; j < rows; j++) {
                float ya = y0 + (y1 - y0) * j / rows;
                float yb = y0 + (y1 - y0) * (j + 1) / rows;
                for (int i = 0; i < cols; i++) {
                    float xa = x0 + (x1 - x0) * i / cols;
                    float xb = x0 + (x1 - x0) * (i + 1) / cols;
                    warpVertex(b, m, xa, ya, w, h);
                    warpVertex(b, m, xa, yb, w, h);
                    warpVertex(b, m, xb, yb, w, h);
                    warpVertex(b, m, xb, ya, w, h);
                }
            }
            BufferUploader.drawWithShader(b.end());
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
        }
    }

    private static void warpVertex(BufferBuilder b, Matrix4f m, float x, float y, int w, int h) {
        float u = Mth.clamp(x / w, 0F, 1F);
        float v = Mth.clamp(y / h, 0F, 1F);
        float amp = WARP_STRENGTH * h;
        float dx = amp * (pVB * (float) Math.sin(Math.PI * v) + pV * (v - 0.5F) * 1.6F);
        float dy = amp * (pHB * (float) Math.sin(Math.PI * u) + pH * (u - 0.5F) * 1.6F);
        b.vertex(m, x + dx, y + dy, 0F).uv(u, 1F - v).endVertex();
    }

    /** a soft round glow (overlay only, no light source): the colour fades to nothing at the edge. */
    private static void drawGlow(PoseStack ps, float cx, float cy, float rx, float ry, float alpha, float cr, float cg, float cb) {
        try {
            Matrix4f m = ps.last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
            b.vertex(m, cx, cy, 0F).color(cr, cg, cb, Mth.clamp(alpha, 0F, 1F)).endVertex();
            int n = 40;
            for (int i = 0; i <= n; i++) {
                double a = i * (Math.PI * 2.0D / n);
                b.vertex(m, cx + (float) Math.cos(a) * rx, cy + (float) Math.sin(a) * ry, 0F).color(cr, cg, cb, 0F).endVertex();
            }
            Tesselator.getInstance().end();
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static int clamp255(int v) { return v < 0 ? 0 : Math.min(v, 255); }

    private static void drawNoise(PoseStack ps, int w, int h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (noise == null) {
                noise = new DynamicTexture(NOISE_TEX, NOISE_TEX, false);
                noise.setFilter(false, false);
                noiseLoc = mc.getTextureManager().register("slenderman_death_noise", noise);
            }
            long now = System.currentTimeMillis();
            if (now - lastNoiseMs > 40L) {
                lastNoiseMs = now;
                NativeImage img = noise.getPixels();
                if (img != null) {
                    for (int y = 0; y < NOISE_TEX; y++) {
                        for (int x = 0; x < NOISE_TEX; x++) {
                            // the same grain as the camera: grey with a soft colour cast
                            int v = NOISE_RND.nextInt(256);
                            int a = NOISE_RND.nextInt(256);
                            int cr = v, cg = v, cb = v;
                            if (NOISE_RND.nextInt(100) < 60) {
                                cr = clamp255(v + NOISE_RND.nextInt(91) - 45);
                                cg = clamp255(v + NOISE_RND.nextInt(91) - 45);
                                cb = clamp255(v + NOISE_RND.nextInt(91) - 45);
                            }
                            img.setPixelRGBA(x, y, (a << 24) | (cb << 16) | (cg << 8) | cr);
                        }
                    }
                    noise.upload();
                }
            }
            double g = mc.getWindow().getGuiScale();
            int regionW = Math.max(1, (int) Math.round(w * g));
            int regionH = Math.max(1, (int) Math.round(h * g));
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
            RenderSystem.setShaderTexture(0, noiseLoc);
            GuiComponent.blit(ps, 0, 0, w, h, NOISE_RND.nextInt(NOISE_TEX), NOISE_RND.nextInt(NOISE_TEX),
                    regionW, regionH, NOISE_TEX, NOISE_TEX);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
        } catch (Throwable ignored) {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }
}
