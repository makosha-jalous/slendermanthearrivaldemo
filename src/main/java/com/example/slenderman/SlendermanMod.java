package com.example.slenderman;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

@Mod(SlendermanMod.ID)
public class SlendermanMod {
    public static final String ID = "slenderman";

    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ID);
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ID);

    public static final RegistryObject<EntityType<SlendermanEntity>> SLENDERMAN = ENTITIES.register("slenderman",
            () -> EntityType.Builder.of(SlendermanEntity::new, MobCategory.MONSTER)
                    .sized(0.8F, 4.0F)
                    .fireImmune()
                    .clientTrackingRange(10)
                    // position is sent every tick, so the teleport shows up at once (no 3-tick delay)
                    .updateInterval(1)
                    .build("slenderman"));

    public static final RegistryObject<Item> SPAWN_EGG = ITEMS.register("slenderman_spawn_egg",
            () -> new ForgeSpawnEggItem(SLENDERMAN, 0x101018, 0xD8D8DC,
                    new Item.Properties().tab(CreativeModeTab.TAB_MISC)));

    /**
     * a sheet of paper stuck to a block surface (see SlenderPageEntity). Static: its position is never updated
     * after it is placed.
     */
    public static final RegistryObject<EntityType<SlenderPageEntity>> PAGE = ENTITIES.register("slender_page",
            () -> EntityType.Builder.<SlenderPageEntity>of(SlenderPageEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.6F)
                    .fireImmune()
                    .clientTrackingRange(10)
                    .updateInterval(Integer.MAX_VALUE)
                    .build("slender_page"));

    /**
     * the 8 pages as items (slender_page_1 .. slender_page_8), found in the creative tab "Misc".
     * Right click on a block surface sticks the page there.
     */
    public static final List<RegistryObject<Item>> PAGE_ITEMS = List.of(
            registerPage(1), registerPage(2), registerPage(3), registerPage(4),
            registerPage(5), registerPage(6), registerPage(7), registerPage(8));

    private static RegistryObject<Item> registerPage(int number) {
        return ITEMS.register("slender_page_" + number,
                () -> new SlenderPageItem(number, new Item.Properties().tab(CreativeModeTab.TAB_MISC).stacksTo(16)));
    }

    /** the looping sound that comes from Slenderman (file: assets/slenderman/sounds/roaming.ogg). */
    public static final RegistryObject<SoundEvent> ROAMING = SOUNDS.register("roaming",
            () -> new SoundEvent(new ResourceLocation(ID, "roaming")));

    /**
     * the three screamer sounds (files: assets/slenderman/sounds/dramatic1a.ogg, dramatic1b.ogg, dramatic1c.ogg).
     * One screamer plays exactly one of them, chosen at random; after any of them there is a shared 20-25 s cooldown.
     */
    public static final List<RegistryObject<SoundEvent>> SCREAMS = List.of(
            registerScream("dramatic1a"),
            registerScream("dramatic1b"),
            registerScream("dramatic1c"));

    /**
     * the five "far" sight sounds (files: sight1a..sight1d and sight2 in assets/slenderman/sounds/): played when he is seen
     * 12-40 blocks away. They share one 15-20 s cooldown and are independent of the screamers.
     */
    public static final List<RegistryObject<SoundEvent>> SIGHTS = List.of(
            registerScream("sight1a"),
            registerScream("sight1b"),
            registerScream("sight1c"),
            registerScream("sight1d"),
            registerScream("sight2"));

    /**
     * the six "middle distance" sounds (files sight2a, sight3, sight3a, sight3b, sight4a, sight4b in
     * assets/slenderman/sounds/): played when the player sees him 6-12 blocks away. Closer than 6 blocks the
     * dramatic1a/b/c screamers take over.
     */
    public static final List<RegistryObject<SoundEvent>> MID_SIGHTS = List.of(
            registerScream("sight2a"),
            registerScream("sight3"),
            registerScream("sight3a"),
            registerScream("sight3b"),
            registerScream("sight4a"),
            registerScream("sight4b"));

    private static RegistryObject<SoundEvent> registerScream(String name) {
        return SOUNDS.register(name, () -> new SoundEvent(new ResourceLocation(ID, name)));
    }

    /** the sound of taking a page: one event with three files (pagegrab, pagegrab1, pagegrab2), one is picked at random. */
    public static final RegistryObject<SoundEvent> PAGE_GRAB = registerScream("pagegrab");

    /**
     * the ambient loops by collected pages (files page1-2, page3-4, page5-6, page7 in assets/slenderman/sounds/):
     * index 0 = pages 1-2, 1 = pages 3-4, 2 = pages 5-6, 3 = page 7.
     */
    public static final List<RegistryObject<SoundEvent>> PAGE_AMBIENTS = List.of(
            registerScream("page_ambient_1_2"),
            registerScream("page_ambient_3_4"),
            registerScream("page_ambient_5_6"),
            registerScream("page_ambient_7"));

    public SlendermanMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
        StepSoundRegistry.SOUNDS.register(bus);
        bus.addListener(this::commonSetup);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(SlenderNetwork::register);
    }
}
