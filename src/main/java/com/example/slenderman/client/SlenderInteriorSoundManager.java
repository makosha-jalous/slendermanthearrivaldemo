package com.example.slenderman.client;

import com.example.slenderman.SlendermanEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = "slenderman", value = Dist.CLIENT)
public class SlenderInteriorSoundManager {
    private static InteriorAmbienceSoundInstance activeInteriorSound = null;
    private static WindAmbientSoundInstance activeWindSound = null;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            stopSounds();
            return;
        }

        // 1. Управление звуком ветра (играет всегда в мире, независимо от Слендермена)
        if (activeWindSound == null || activeWindSound.isStopped()) {
            activeWindSound = new WindAmbientSoundInstance();
            mc.getSoundManager().play(activeWindSound);
        }

        // 2. Проверяем наличие Слендермена для внутреннего гула
        List<SlendermanEntity> slendermen = mc.level.getEntitiesOfClass(
                SlendermanEntity.class,
                mc.player.getBoundingBox().inflate(128.0D)
        );

        if (slendermen.isEmpty()) {
            stopInteriorSound();
            return;
        }

        // 3. Проверка нахождения под крышей для гула
        boolean isIndoors = checkIndoors(mc);

        if (isIndoors) {
            if (activeInteriorSound == null || activeInteriorSound.isStopped()) {
                activeInteriorSound = new InteriorAmbienceSoundInstance();
                mc.getSoundManager().play(activeInteriorSound);
            }
        } else {
            stopInteriorSound();
        }
    }

    public static boolean checkIndoors(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        BlockPos pos = mc.player.blockPosition();
        int topY = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        
        if (topY > pos.getY() + 2) {
            return true;
        }

        for (int dy = 1; dy <= 10; dy++) {
            BlockPos checkPos = pos.above(dy);
            BlockState state = mc.level.getBlockState(checkPos);
            if (!state.isAir() && !state.getCollisionShape(mc.level, checkPos).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private static void stopInteriorSound() {
        if (activeInteriorSound != null) {
            activeInteriorSound.startFadeOut();
            activeInteriorSound = null;
        }
    }

    private static void stopSounds() {
        stopInteriorSound();
        if (activeWindSound != null) {
            activeWindSound.stop();
            activeWindSound = null;
        }
    }
}