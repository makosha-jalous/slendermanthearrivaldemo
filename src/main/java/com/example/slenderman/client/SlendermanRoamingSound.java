package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The looping "roaming" sound that comes from Slenderman himself (CLIENT ONLY).
 *
 * - it is positional: it sits on the entity and starts to be heard from 20 blocks away, quietly,
 *   and gets louder the closer you get (linear fade, silent at 20 blocks);
 * - it only plays while he is in the player's field of view (and not hidden behind blocks);
 *   it fades in when he comes into view and fades out when you look away or he vanishes.
 */
public class SlendermanRoamingSound extends AbstractTickableSoundInstance {
    /** range of the sound in blocks. The engine's range is 16 * volume at the moment the sound starts. */
    private static final float RANGE = 20.0F;
    private static final float START_VOLUME = RANGE / 16.0F;
    /** how much of the screen counts as "in view" (dot product with the view direction, 0.5 = 60 degrees to each side). */
    private static final double VIEW_COS = 0.5D;

    private final SlendermanEntity entity;
    private float fade = 0.0F;

    public SlendermanRoamingSound(SlendermanEntity entity) {
        super(SlendermanMod.ROAMING.get(), SoundSource.HOSTILE, RandomSource.create());
        this.entity = entity;
        this.looping = true;
        this.delay = 0;
        this.volume = START_VOLUME;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.followEntity();
    }

    private void followEntity() {
        this.x = entity.getX();
        this.y = entity.getY() + 2.0D;
        this.z = entity.getZ();
    }

    @Override
    public void tick() {
        if (entity.isRemoved()) {
            this.stop();
            return;
        }
        this.followEntity();
        boolean visible = isVisible(entity);
        // fade in over about 1 second, fade out a bit faster
        this.fade += Mth.clamp((visible ? 1.0F : 0.0F) - this.fade, -0.10F, 0.05F);
        // never exactly 0, otherwise the engine would stop the loop
        this.volume = Math.max(this.fade, 0.0001F);
    }

    /** is the entity inside the local player's field of view with a free line of sight? */
    public static boolean isVisible(SlendermanEntity e) {
        Player p = Minecraft.getInstance().player;
        if (p == null || e.isInvisible() || !e.isAlive()) return false;
        Vec3 eye = p.getEyePosition();
        Vec3 view = p.getViewVector(1.0F).normalize();
        for (double h : new double[]{3.6D, 2.0D, 0.6D}) {
            Vec3 pt = new Vec3(e.getX(), e.getY() + h, e.getZ());
            Vec3 to = pt.subtract(eye);
            double len = to.length();
            if (len < 0.5D) return true;
            if (view.dot(to.scale(1.0D / len)) < VIEW_COS) continue;
            BlockHitResult hit = p.level.clip(new ClipContext(eye, pt, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(pt) < 0.25D) return true;
        }
        return false;
    }

    /** called every client tick by the entity: starts the sound the first time he comes into view within range. */
    public static void clientTick(SlendermanEntity e) {
        Object existing = e.roamingSound;
        if (existing instanceof SlendermanRoamingSound running && !running.isStopped()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.distanceTo(e) > RANGE) return;
        if (!isVisible(e)) return;
        SlendermanRoamingSound sound = new SlendermanRoamingSound(e);
        e.roamingSound = sound;
        mc.getSoundManager().play(sound);
    }
}
