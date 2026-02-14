package com.void2.ds1music;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;


@Mod(DS1Music.MODID)
public class DS1Music {
    public static final String MODID = "ds1music";
    public static FadingSoundInstance currentSoundInstance;

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MODID);
    public static final DeferredHolder<SoundEvent, SoundEvent> MENU_SOUND = SOUNDS.register("main_menu_ds",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MODID, "main_menu_ds")));

    public DS1Music(IEventBus modEventBus, ModContainer modContainer) {
        SOUNDS.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        modContainer.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                ConfigurationScreen::new);
        }

    // --- (FORGE BUS) ---
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModEvents {
        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft mc = Minecraft.getInstance();
            boolean shouldPlay = mc.level == null && Config.ENABLE_MENU_MUSIC.get();

            if (shouldPlay) {
                mc.getMusicManager().stopPlaying();
                if (currentSoundInstance == null || currentSoundInstance.isStopped()) {
                    currentSoundInstance = new FadingSoundInstance(DS1Music.MENU_SOUND.get());
                    mc.getSoundManager().play(currentSoundInstance);
                }
            } else {
                if (currentSoundInstance != null && !currentSoundInstance.isStopped()) {
                    currentSoundInstance.fadeOut();
                }
            }
        }
    }

    // --- (MOD BUS) ---
    @EventBusSubscriber(modid = MODID, value = Dist.CLIENT)
    public static class ClientModBusEvents {
        @SubscribeEvent
        public static void onSoundReload(SoundEngineLoadEvent event) {
            if (currentSoundInstance != null) {
                currentSoundInstance = null;
            }
        }
    }

    public static class FadingSoundInstance extends AbstractTickableSoundInstance {
        private float targetVolume = 1.0F;

        protected FadingSoundInstance(SoundEvent soundEvent) {
            super(soundEvent, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.relative = true;
        }

        @Override
        public void tick() {
            float fadeSpeed = 0.01F;
            if (this.volume < targetVolume) {
                this.volume = Math.min(targetVolume, this.volume + fadeSpeed);
            } else if (this.volume > targetVolume) {
                this.volume = Math.max(0.0F, this.volume - fadeSpeed);
            }

            if (this.volume <= 0.0F && targetVolume == 0.0F) {
                this.stop();
            }
        }

        public void fadeOut() {
            this.targetVolume = 0.0F;
        }
    }
}