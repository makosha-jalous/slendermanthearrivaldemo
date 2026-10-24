package com.example.slenderman;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class StepSoundRegistry {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, SlendermanMod.ID);

    // Звук шагов
    public static final RegistryObject<SoundEvent> FOOTSTEPS = SOUNDS.register("slender_footsteps",
            () -> new SoundEvent(new ResourceLocation(SlendermanMod.ID, "slender_footsteps")));

    // Фоновый эмбиент (гул) в помещениях
    public static final RegistryObject<SoundEvent> INTERIOR_AMBIENCE = SOUNDS.register("interior_ambience",
            () -> new SoundEvent(new ResourceLocation(SlendermanMod.ID, "interior_ambience")));

    // Фоновый звук ветра
    public static final RegistryObject<SoundEvent> WIND_AMBIENCE = SOUNDS.register("wind_ambience",
            () -> new SoundEvent(new ResourceLocation(SlendermanMod.ID, "wind_ambience")));
}