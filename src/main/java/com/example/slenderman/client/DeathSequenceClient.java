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
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import org.lwjgl.BufferUtils;
import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
    private static final float MODEL_DARK = 1.00F;     // the skin of the death scene is painted dark already: no extra darkening
    /** the glow behind him is 70 % darker than before (brightness x 0.30). */
    private static final float GLOW_DIM = 0.30F;
    /** strength of the bending of the picture (share of the screen height). */
    private static final float WARP_STRENGTH = 0.055F;
    private static final Random FX_RND = new Random();
    private static boolean dramaticActive = false;
    private static DeathLoop lightInst, mediumInst, heavyInst;
    /**
     * THE PORTRAIT USES THE DICE OF THE ORIGINAL GAME (the same as the camera): once per game tick (50 ms) one dice
     * Random.value < (loss + 35) / 100 decides for the sounds AND for the picture at the same moment:
     *   heard / on:  Static Light, Medium and Heavy are audible, the picture gets a chromatic aberration and the fisheye
     *                (strengthY with 15 %, else strengthX with 25 %, range -0.01*(100-loss) .. 0.005*(100-loss));
     *   silent / off: all sounds are muted and the picture is straight again (Revert).
     * DEATH_LOSS is the sanity loss that the dice uses in the portrait.
     */
    private static final float DEATH_LOSS = 30F;
    /** strength of the grain over the portrait: more transparent than before (0.40), because he is not always visible. */
    private static final float DEATH_NOISE = 0.24F;
    private static boolean deathOn = false;
    private static float fishX = 0F, fishY = 0F;
    private static float glowFlick = 1.0F;
    private static float caNow = 0F;
    /** the black screen over the picture: it has its own throw every tick with the same formula (covers with the chance 1 - (loss + 35) / 100). */
    private static boolean coverOn = false;
    /** the scratches: gaps in the grain (horizontal bands over the whole width), new ones about 10 times a second. */
    private static int bandCount = 0, bandTick = 0;
    private static final float[] BAND_Y = new float[3], BAND_T = new float[3];
    private static RenderTarget sceneCopy;

    private DeathSequenceClient() {}

    /** true while the portrait is on the screen: the renderer then uses the skin of the death scene. */
    public static boolean isPortrait() {
        return phase == 3;
    }

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
                startBreath(mc);
                echoTick = 0;
                noiseStartMs = System.currentTimeMillis();
            }
            if (current == 3) {
                restoreGain();                    // the echo is over: the portrait has its own sounds
                stopBreath();
                stopLoops();
                deathOn = false;
                coverOn = false;
                bandCount = 0;
                fishX = fishY = caNow = 0F;
                glowFlick = 1.0F;
                processedStep = -1L;
                epochNs = System.nanoTime();
                try {
                    lightInst = new DeathLoop("death_light");
                    mediumInst = new DeathLoop("death_medium");
                    heavyInst = new DeathLoop("death_heavy");
                    mc.getSoundManager().play(lightInst);
                    mc.getSoundManager().play(mediumInst);
                    mc.getSoundManager().play(heavyInst);
                } catch (Throwable ignored) {
                }
            }
            if (current == 4) {
                stopBreath();
                stopLoops();                      // black again: everything dies away
            }
            if (current == 0) {
                stopBreath();
                stopLoops();
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
        if (phase == 4) {
            // everything dies away
            float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
            float k = 1.0F - Mth.clamp((ticks + 1) / (float) SlendermanEntity.DEATH_END_TICKS, 0.0F, 1.0F);
            setGain(master * k);
        }
    }

    private static SoundInstance breathInst;

    /** the only thing heard on the black screen (besides the screamers): the breathing of the camera. */
    private static void startBreath(Minecraft mc) {
        try {
            breathInst = SimpleSoundInstance.forUI(new SoundEvent(new ResourceLocation("slendercam", "breathing")), 1.0F, 0.9F);
            mc.getSoundManager().play(breathInst);
        } catch (Throwable ignored) {
            breathInst = null;
        }
    }

    private static void stopBreath() {
        if (breathInst == null) return;
        try {
            Minecraft.getInstance().getSoundManager().stop(breathInst);
        } catch (Throwable ignored) {
        }
        breathInst = null;
    }

    /** from the black screen on NO new sound may start, except the screamers and the sounds of the scene itself. */
    @SubscribeEvent
    public static void onPlaySound(net.minecraftforge.client.event.sound.PlaySoundEvent e) {
        if (phase < 2) return;
        SoundInstance si = e.getSound();
        if (si == null) return;
        if (si.getSource() == SlendermanRoamingSound.SCREAM_SOURCE) return;
        ResourceLocation loc = si.getLocation();
        if (loc.getNamespace().equals("slendercam")) {
            String p = loc.getPath();
            if (p.equals("breathing") || p.equals("static_medium_piece") || p.equals("static_heavy")) return;
        }
        if (loc.getNamespace().equals("slendercamfx")) {
            String p = loc.getPath();
            if (p.equals("death_light") || p.equals("death_medium") || p.equals("death_heavy")
                    || p.equals("static_light_full") || p.equals("static_medium_full") || p.equals("static_heavy_full")) return;
        }
        e.setSound(null);
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

    // ------------------------------------------------------------------------------------------
    //  THE DICE OF THE PORTRAIT: 20 ms STEPS (the FixedUpdate step of the original), for the sounds AND the picture.
    //  Every 20 ms step has its own dice Random.value < (loss + 35) / 100 and its own random numbers; they are computed from
    //  the number of the step (diceAt / rnd), so the sounds (produced on the sound thread) and the picture (drawn every frame)
    //  get exactly the same answers for the same step. The time of step n is  epoch + n * 20 ms  (the picture is shifted by
    //  SYNC_MS, the delay of the sound output).
    // ------------------------------------------------------------------------------------------
    private static final long STEP_NS = 20_000_000L;
    private static final float DEATH_P = (DEATH_LOSS + 35F) / 100F;
    private static final long SYNC_MS = 50L;
    private static volatile long epochNs = 0L;
    private static long processedStep = -1L;
    private static final long SEED = 0x5EED5L;

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** a random number 0..1 that belongs to a step and a purpose (salt). */
    private static float rnd(long step, int salt) {
        long h = mix(mix(step * 31L + salt) ^ SEED);
        return ((h >>> 40) & 0xFFFFFFL) / (float) 0x1000000;
    }

    /** the dice of one step: true = Apply (heard, distorted), false = Revert (muted, straight). */
    static boolean diceAt(long step) {
        return rnd(step, 1) < DEATH_P;
    }

    private static float range(long step, int salt, float a, float b) {
        return a + (b - a) * rnd(step, salt);       // Random.Range(a, b)
    }

    /** the step that is heard / seen right now. */
    private static long currentStep(long shiftMs) {
        long e = epochNs;
        if (e == 0L) return -1L;
        return (System.nanoTime() - e - shiftMs * 1_000_000L) / STEP_NS;
    }

    /** one step of the picture: Apply or Revert, exactly like Visuals() / Revert() of the original. */
    private static void applyStep(long s) {
        float I = DEATH_LOSS;
        deathOn = diceAt(s) && I > 0F;
        if (deathOn) {
            caNow = Mth.clamp(range(s, 2, 0.2F, 1.5F * I + 50F) / 200F, 0F, 1F);
            if (rnd(s, 3) > 0.85F) {
                fishY = range(s, 4, -0.01F * (100F - I), 0.005F * (100F - I));
            } else if (rnd(s, 5) > 0.75F) {
                fishX = range(s, 6, -0.01F * (100F - I), 0.005F * (100F - I));
            }
            glowFlick = 0.88F + 0.12F * rnd(s, 7);
            if (s % 5 == 0) {                                   // the scratches jump about 10 times a second
                bandCount = 1 + (int) (rnd(s, 8) * 3F);
                if (bandCount > 3) bandCount = 3;
                for (int i = 0; i < bandCount; i++) {
                    BAND_Y[i] = rnd(s, 20 + i);
                    BAND_T[i] = 0.9F + 2.4F * rnd(s, 30 + i);   // thickness in pixels of a 1080p screen
                }
            }
        } else {
            caNow = 0F;
            fishX = 0F;
            fishY = 0F;
            bandCount = 0;
        }
        coverOn = rnd(s, 9) >= DEATH_P;                          // the black screen: its own throw with the same chance formula
        lastNoiseMs = 0L;                                        // a fresh grain picture with every step
    }

    /** runs the steps that have passed since the last frame (called every rendered frame during the portrait). */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START || phase != 3) return;
        long cur = currentStep(SYNC_MS);
        if (cur < 0L) return;
        if (processedStep < 0L) processedStep = Math.max(-1L, cur - 1L);
        int n = 0;
        while (processedStep < cur && n < 12) {
            processedStep++;
            applyStep(processedStep);
            n++;
        }
        if (processedStep < cur) processedStep = cur;
    }

    /**
     * A layer of the portrait: a looping whole track. When the sound engine asks for its stream (getStream) it gets a GateStream
     * that mutes / unmutes the track in every 20 ms block with the dice of that block. If that does not work (streamed stays
     * false) the layer falls back to switching its volume once per game tick.
     */
    private static final class DeathLoop extends net.minecraft.client.resources.sounds.AbstractTickableSoundInstance {
        volatile boolean streamed;

        DeathLoop(String event) {
            super(new SoundEvent(new ResourceLocation("slendercamfx", event)), SoundSource.MASTER, net.minecraft.util.RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 1.0F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public boolean canStartSilent() { return true; }

        public CompletableFuture<AudioStream> getStream(SoundBufferLibrary lib, Sound sound, boolean loop) {
            return lib.getStream(sound.getPath(), false).thenApply(src -> {
                try {
                    GateStream gs = new GateStream(src);
                    streamed = true;
                    return (AudioStream) gs;
                } catch (IOException ex) {
                    throw new CompletionException(ex);
                }
            });
        }

        @Override
        public void tick() {
            if (phase != 3) { this.stop(); return; }
            if (streamed) { this.volume = 1.0F; return; }
            long st = currentStep(0L);
            this.volume = (st >= 0L && diceAt(st)) ? 1.0F : 0.0001F;
        }
    }

    /** the whole track, copied sample by sample; every 20 ms block is heard or silent (the track keeps running while it is muted). */
    private static final class GateStream implements AudioStream {
        private final AudioFormat format;
        private final short[] pcm;
        private final ByteOrder order;
        private final int stepSamples;
        private long step = -1L;
        private int stepLeft;
        private boolean on;
        private int pos;

        GateStream(AudioStream src) throws IOException {
            this.format = src.getFormat();
            this.stepSamples = Math.max(1, Math.round(format.getSampleRate() * 0.02F));
            List<short[]> chunks = new ArrayList<>();
            ByteOrder ord = ByteOrder.nativeOrder();
            boolean first = true;
            int total = 0;
            while (true) {
                ByteBuffer b = src.read(32768);
                if (b == null || !b.hasRemaining()) break;
                if (first) { ord = b.order(); first = false; }
                ShortBuffer sb = b.asShortBuffer();
                short[] a = new short[sb.remaining()];
                sb.get(a);
                chunks.add(a);
                total += a.length;
            }
            src.close();
            short[] all = new short[Math.max(1, total)];
            int p = 0;
            for (short[] a : chunks) { System.arraycopy(a, 0, all, p, a.length); p += a.length; }
            this.pcm = all;
            this.order = ord;
        }

        @Override public AudioFormat getFormat() { return format; }

        @Override public ByteBuffer read(int size) {
            if (step < 0L) {
                long e = epochNs;
                step = e == 0L ? 0L : Math.max(0L, (System.nanoTime() - e) / STEP_NS);   // the same clock as the picture
                stepLeft = 0;
            }
            int n = Math.max(1, size / 2);
            ByteBuffer out = BufferUtils.createByteBuffer(n * 2).order(order);
            for (int i = 0; i < n; i++) {
                if (stepLeft <= 0) {
                    on = diceAt(step++);
                    stepLeft = stepSamples;
                }
                stepLeft--;
                out.putShort(on ? pcm[pos] : 0);
                if (++pos >= pcm.length) pos = 0;
            }
            out.flip();
            return out;
        }

        @Override public void close() { }
    }

    private static void stopLoops() {
        try {
            SoundManager sm = Minecraft.getInstance().getSoundManager();
            if (lightInst != null) sm.stop(lightInst);
            if (mediumInst != null) sm.stop(mediumInst);
            if (heavyInst != null) sm.stop(heavyInst);
        } catch (Throwable ignored) {
        }
        lightInst = null;
        mediumInst = null;
        heavyInst = null;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        phase = 0;
        killer = null;
        stopLoops();
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
            // layers, from the back: background - Slenderman - black screen - static (grain, noise, scratches) - fisheye
            drawPortrait(ps, w, h);
            if (coverOn) GuiComponent.fill(ps, 0, 0, w, h, 0xFF000000);      // the black screen covers the picture now and then
            // the grain of the camera at its strongest stage (20% weaker, with a flicker)
            drawNoise(ps, w, h, DEATH_NOISE * (0.78F + 0.42F * FX_RND.nextFloat()));
            drawBentScene(ps, w, h, caNow);   // the fisheye and the colour split of the dice bend everything, the static too
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
        drawGlow(ps, cx, cy, h * 0.60F, h * 0.55F, 0.46F * glowFlick * GLOW_DIM, 0.40F, 0.48F, 0.20F);
        if (killer == null) return;
        try {
            int scale = Math.round(h * PORTRAIT_SCALE);
            int x = Math.round(cx);
            int y = Math.round(h * PORTRAIT_TOP + 4.0F * scale);       // the feet are far below the screen: only the head and the shoulders show
            RenderSystem.setShaderColor(MODEL_DARK, MODEL_DARK, MODEL_DARK * 0.95F, 1.0F);
            InventoryScreen.renderEntityInInventory(x, y, scale, 0.0F, 0.0F, killer);    // 0 / 0: exactly from the front
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        // (no dark shadow over the face any more: it is painted on the skin of the death scene)
        drawGlow(ps, cx, h * (PORTRAIT_GLOW_Y + 0.36F), h * 0.50F, h * 0.16F, 0.20F * glowFlick * GLOW_DIM, 0.55F, 0.62F, 0.40F);
    }

    /**
     * The same bending as the camera: the picture that is on the screen (black, glow and Slenderman) is copied and drawn
     * back as a net of small squares whose corners are moved - like the interface frame and the world in the camera.
     */
    private static float caScale = 0F;

    private static void drawBentScene(PoseStack ps, int w, int h, float chroma) {
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
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.setShaderTexture(0, sceneCopy.getColorTextureId());
            Matrix4f m = ps.last().pose();
            final int cols = 36, rows = 22;
            float x0 = -0.06F * w, x1 = 1.06F * w, y0 = -0.06F * h, y1 = 1.06F * h;
            // the colour split like in the camera: only the green channel is moved (red + blue stay together = pink)
            boolean split = chroma > 0.002F;
            int passes = split ? 3 : 1;
            for (int pass = 0; pass < passes; pass++) {
                if (split) {
                    RenderSystem.colorMask(pass == 0, pass == 1, pass == 2, false);
                    caScale = (pass == 1 ? 1F : 0F) * chroma * 0.16F;
                } else {
                    RenderSystem.colorMask(true, true, true, false);
                    caScale = 0F;
                }
                BufferBuilder b = Tesselator.getInstance().getBuilder();
                b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
                for (int jj = 0; jj < rows; jj++) {
                    float ya = y0 + (y1 - y0) * jj / rows;
                    float yb = y0 + (y1 - y0) * (jj + 1) / rows;
                    for (int ii = 0; ii < cols; ii++) {
                        float xa = x0 + (x1 - x0) * ii / cols;
                        float xb = x0 + (x1 - x0) * (ii + 1) / cols;
                        warpVertex(b, m, xa, ya, w, h);
                        warpVertex(b, m, xa, yb, w, h);
                        warpVertex(b, m, xb, yb, w, h);
                        warpVertex(b, m, xb, ya, w, h);
                    }
                }
                BufferUploader.drawWithShader(b.end());
            }
            caScale = 0F;
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
        }
    }

    /** the shadow of the face: black over the middle of the head (a soft band), light again on the shoulders below it. */
    private static void drawOverheadShade(PoseStack ps, int w, int h) {
        try {
            Matrix4f m = ps.last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder b = Tesselator.getInstance().getBuilder();
            b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            final int rows = 28, cols = 14;
            float top = h * 0.14F, bottom = h * 0.80F;
            float left = w * 0.5F - h * 0.62F, right = w * 0.5F + h * 0.62F;
            for (int r = 0; r < rows; r++) {
                float ya = top + (bottom - top) * r / rows, yb = top + (bottom - top) * (r + 1) / rows;
                for (int c = 0; c < cols; c++) {
                    float xa = left + (right - left) * c / cols, xb = left + (right - left) * (c + 1) / cols;
                    float a00 = shadeAlpha(xa, ya, w, h), a01 = shadeAlpha(xa, yb, w, h);
                    float a11 = shadeAlpha(xb, yb, w, h), a10 = shadeAlpha(xb, ya, w, h);
                    b.vertex(m, xa, ya, 0F).color(0F, 0F, 0F, a00).endVertex();
                    b.vertex(m, xa, yb, 0F).color(0F, 0F, 0F, a01).endVertex();
                    b.vertex(m, xb, yb, 0F).color(0F, 0F, 0F, a11).endVertex();
                    b.vertex(m, xb, ya, 0F).color(0F, 0F, 0F, a10).endVertex();
                }
            }
            Tesselator.getInstance().end();
        } catch (Throwable ignored) {
        } finally {
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        }
    }

    private static float shadeAlpha(float x, float y, int w, float h) {
        float v = (y / h);
        float vert;
        if (v < 0.24F) vert = 0F;                                        // the lit top of the head
        else if (v < 0.36F) vert = (v - 0.24F) / 0.12F;                  // the shadow of the brow comes in
        else if (v < 0.58F) vert = 1F;                                   // the face: black
        else if (v < 0.72F) vert = 1F - 0.78F * (v - 0.58F) / 0.14F;     // down to the shoulders: lit again
        else vert = 0.22F;
        float dx = Math.abs(x - w * 0.5F) / (h * 0.62F);
        float horiz = Mth.clamp(1.15F - dx * dx * 1.2F, 0F, 1F);
        return Mth.clamp(vert * (0.35F + 0.62F * horiz), 0F, 0.97F);
    }

    private static final float FISH_K = 80F / 512F;

    /**
     * FISHEYE of the original (Unity Fisheye): the picture is sampled at (uv - offset), offset.x = (1 - y*y) * strengthY * K * x,
     * offset.y = (1 - x*x) * strengthX * aspect * K * y, x and y from -1 to 1, K = 80/512. The colour split moves the green
     * channel outwards by the cube of the distance from the middle.
     */
    private static void warpVertex(BufferBuilder b, Matrix4f m, float x, float y, int w, int h) {
        float u0 = x / w;
        float v0 = 1F - y / h;                                  // frame buffer textures are upside down
        float cx = 2F * u0 - 1F, cy = 2F * v0 - 1F;
        float ar = w / (float) h;
        float offX = (1F - cy * cy) * fishY * FISH_K * cx;
        float offY = (1F - cx * cx) * fishX * ar * FISH_K * cy;
        float u = Mth.clamp(u0 - offX, 0F, 1F), v = Mth.clamp(v0 - offY, 0F, 1F);
        float dxn = (x - w * 0.5F) / (w * 0.5F), dyn = (y - h * 0.5F) / (h * 0.5F);
        float fe = 1F + caScale * (dxn * dxn + dyn * dyn);
        float px = w * 0.5F + (x - w * 0.5F) * fe;
        float py = h * 0.5F + (y - h * 0.5F) * fe;
        b.vertex(m, px, py, 0F).uv(u, v).endVertex();
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

    private static void noiseStrip(PoseStack ps, int w, int ya, int yb, float u0, float v0, double g, float alpha) {
        if (yb <= ya || alpha <= 0.001F) return;
        RenderSystem.setShaderColor(1F, 1F, 1F, alpha);
        int regionW = Math.max(1, (int) Math.round(w * g));
        int regionH = Math.max(1, (int) Math.round((yb - ya) * g));
        GuiComponent.blit(ps, 0, ya, w, yb - ya, u0, v0 + (float) (ya * g), regionW, regionH, NOISE_TEX, NOISE_TEX);
    }

    private static void drawNoise(PoseStack ps, int w, int h, float alpha) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (noise == null) {
                noise = new DynamicTexture(NOISE_TEX, NOISE_TEX, false);
                noise.setFilter(false, false);
                noiseLoc = mc.getTextureManager().register("slenderman_death_noise", noise);
            }
            long now = System.currentTimeMillis();
            if (now - lastNoiseMs > 12L) {
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
            float u0 = NOISE_RND.nextInt(NOISE_TEX), v0 = NOISE_RND.nextInt(NOISE_TEX);
            // scratches = gaps in the grain (only in the portrait): the strips between the bands get the full grain, the bands almost none
            int n = phase == 3 ? bandCount : 0;
            int[] ba = new int[3], bb = new int[3];
            for (int i = 0; i < n; i++) {
                int th = Math.max(1, Math.round(BAND_T[i] * h / 1080F));
                ba[i] = Math.round(BAND_Y[i] * (h - th));
                bb[i] = ba[i] + th;
            }
            for (int i = 1; i < n; i++) {                // sort from the top
                int a = ba[i], b = bb[i], j = i - 1;
                while (j >= 0 && ba[j] > a) { ba[j + 1] = ba[j]; bb[j + 1] = bb[j]; j--; }
                ba[j + 1] = a; bb[j + 1] = b;
            }
            int y = 0;
            for (int i = 0; i < n; i++) {
                int a = Math.max(y, ba[i]), b = Math.max(a, bb[i]);
                noiseStrip(ps, w, y, a, u0, v0, g, alpha);
                noiseStrip(ps, w, a, b, u0, v0, g, alpha * 0.08F);
                y = b;
            }
            noiseStrip(ps, w, y, h, u0, v0, g, alpha);
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            RenderSystem.disableBlend();
        } catch (Throwable ignored) {
            RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        }
    }
}
