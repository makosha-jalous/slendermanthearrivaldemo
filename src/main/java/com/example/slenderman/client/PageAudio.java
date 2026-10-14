package com.example.slenderman.client;

import com.example.slenderman.SlendermanMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Sounds of the pages (client side):
 *  - taking a page plays one of pagegrab / pagegrab1 / pagegrab2 at random;
 *  - ambient music by collected pages: 1-2, 3-4, 5-6, 7. A group keeps looping until another group starts
 *    (so it is NOT restarted when page 2 is taken), the next group replaces it at once;
 *  - after page 8 (or with 0 pages) everything fades out and stays quiet.
 */
@Mod.EventBusSubscriber(modid = SlendermanMod.ID, value = Dist.CLIENT)
public class PageAudio {
    /** after page 8 the music dies away, 30 ticks = 1.5 seconds (between groups there is NO fade: the switch is instant). */
    private static final int FINAL_FADE_TICKS = 30;
    /** how often (ticks) we check that the loop is really still playing. */
    private static final int WATCHDOG_INTERVAL = 20;
    /** after starting a loop we do not check it for this long (a big file needs a moment to load). */
    private static final int WATCHDOG_GRACE = 100;

    private static PageAmbientLoop current;
    private static int currentGroup = 0;
    private static int sinceStart = 0;

    /** group of the ambient music for this number of pages: 0 = silence, 1 = pages 1-2, 2 = 3-4, 3 = 5-6, 4 = page 7. */
    private static int groupFor(int pages) {
        if (pages <= 0 || pages >= 8) return 0;
        return (pages + 1) / 2;
    }

    /** called when the server reports the number of pages (pickup = the player has just taken a page). */
    public static void onPages(int pages, boolean pickup) {
        Minecraft mc = Minecraft.getInstance();

        if (pickup && pages > 0) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SlendermanMod.PAGE_GRAB.get(), 1.0F, 1.0F));
        }

        int group = groupFor(pages);
        if (group == currentGroup) return;   // same group: the music just keeps playing

        PageAmbientLoop old = current;
        current = null;
        currentGroup = group;
        // the new group starts FIRST, the old one is cut at the same moment: no gap and no fade
        if (group > 0) {
            startLoop(group);
        }
        if (old != null) {
            if (pages >= 8) old.fadeOut(FINAL_FADE_TICKS);
            else old.halt();
        }
    }

    private static void startLoop(int group) {
        current = new PageAmbientLoop(SlendermanMod.PAGE_AMBIENTS.get(group - 1).get(), 0);
        sinceStart = 0;
        Minecraft.getInstance().getSoundManager().play(current);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();

        // left the world: forget everything (the next world sends its own count)
        if (mc.level == null) {
            current = null;
            currentGroup = 0;
            return;
        }
        if (mc.isPaused() || current == null || currentGroup == 0) return;

        sinceStart++;
        // safety net: if the loop was stopped from outside (/stopsound, sound reload), start it again
        if (sinceStart > WATCHDOG_GRACE && sinceStart % WATCHDOG_INTERVAL == 0
                && !mc.getSoundManager().isActive(current)) {
            startLoop(currentGroup);
        }
    }
}
