package com.void2.ds1music;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(DS1Music.MODID)
public class DS1Music {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "ds1music";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();


    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public DS1Music(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        SOUNDS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (ExampleMod) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(ClientModEvents.class);
        // Register the item to a creative tab

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> MENU_SOUND = SOUNDS.register("main_menu_ds",
            () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MODID, "main_menu_ds")
            ));

    private void commonSetup(FMLCommonSetupEvent event) {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {

    }
    // Add the example block item to the building blocks tab


    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }

    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        private static SimpleSoundInstance currentSoundInstance;

        @SubscribeEvent
        public static void onScreenOpen(ScreenEvent.Opening event) {
            Minecraft mc = Minecraft.getInstance();

            // Проверяем: если мы НЕ в мире (mc.level == null), значит мы в каком-то меню
            if (mc.level == null) {

                // Если музыка еще не запущена, запускаем её
                if (currentSoundInstance == null || !mc.getSoundManager().isActive(currentSoundInstance)) {
                    // Останавливаем стандартную музыку
                    mc.getMusicManager().stopPlaying();

                    currentSoundInstance = new SimpleSoundInstance(
                            DS1Music.MENU_SOUND.get().getLocation(),
                            SoundSource.MUSIC,
                            1.0F, 1.0F,
                            SoundInstance.createUnseededRandom(),
                            true, 0,
                            SoundInstance.Attenuation.NONE,
                            0.0D, 0.0D, 0.0D,
                            true
                    );
                    mc.getSoundManager().play(currentSoundInstance);
                }
            }
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();

            if (!Config.ENABLE_MENU_MUSIC.get()) {
                if (currentSoundInstance != null) {
                    mc.getSoundManager().stop(currentSoundInstance);
                    currentSoundInstance = null;
                }
                return;
            }

            if (mc.level == null) {
                mc.getMusicManager().stopPlaying();

                if (currentSoundInstance == null || !mc.getSoundManager().isActive(currentSoundInstance)) {
                    currentSoundInstance = new SimpleSoundInstance(
                            DS1Music.MENU_SOUND.get().getLocation(),
                            SoundSource.MUSIC,
                            1.0F, 1.0F,
                            SoundInstance.createUnseededRandom(),
                            true, // Зацикливание
                            0,
                            SoundInstance.Attenuation.NONE,
                            0.0D, 0.0D, 0.0D,
                            true
                    );
                    mc.getSoundManager().play(currentSoundInstance);
                }
            }
            else {
                if (currentSoundInstance != null) {
                    mc.getSoundManager().stop(currentSoundInstance);
                    currentSoundInstance = null;
                }
            }
        }
    }
}
