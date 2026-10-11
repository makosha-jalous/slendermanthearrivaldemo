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

    /** the looping sound that comes from Slenderman (file: assets/slenderman/sounds/roaming.ogg). */
    public static final RegistryObject<SoundEvent> ROAMING = SOUNDS.register("roaming",
            () -> new SoundEvent(new ResourceLocation(ID, "roaming")));

    /**
     * the three screamer sounds (files: assets/slenderman/sounds/dramatic1a.ogg, dramatic1b.ogg, dramatic1c.ogg).
     * One screamer plays exactly one of them, chosen at random; each one has its own 15-20 s cooldown.
     */
    public static final List<RegistryObject<SoundEvent>> SCREAMS = List.of(
            registerScream("dramatic1a"),
            registerScream("dramatic1b"),
            registerScream("dramatic1c"));

    /**
     * the four "sight" sounds (files: assets/slenderman/sounds/sight1a.ogg ... sight1d.ogg): played when he is seen
     * far away (but not too far). They share one 15-20 s cooldown and are independent of the screamers.
     */
    public static final List<RegistryObject<SoundEvent>> SIGHTS = List.of(
            registerScream("sight1a"),
            registerScream("sight1b"),
            registerScream("sight1c"),
            registerScream("sight1d"));

    private static RegistryObject<SoundEvent> registerScream(String name) {
        return SOUNDS.register(name, () -> new SoundEvent(new ResourceLocation(ID, name)));
    }

    public SlendermanMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }
}
