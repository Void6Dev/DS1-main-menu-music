package com.void2.ds1music;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;


public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLE_MENU_MUSIC = BUILDER
            .comment("Turn off to disable dark souls menu music")
            .define("enableMenuMusic", true);

    static final ModConfigSpec SPEC = BUILDER.build();

}
