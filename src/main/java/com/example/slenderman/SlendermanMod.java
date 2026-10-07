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
                    .build("slenderman"));

    public static final RegistryObject<Item> SPAWN_EGG = ITEMS.register("slenderman_spawn_egg",
            () -> new ForgeSpawnEggItem(SLENDERMAN, 0x101018, 0xD8D8DC,
                    new Item.Properties().tab(CreativeModeTab.TAB_MISC)));

    /** the looping sound that comes from Slenderman (file: assets/slenderman/sounds/roaming.ogg). */
    public static final RegistryObject<SoundEvent> ROAMING = SOUNDS.register("roaming",
            () -> new SoundEvent(new ResourceLocation(ID, "roaming")));

    public SlendermanMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        ITEMS.register(bus);
        SOUNDS.register(bus);
    }
}
