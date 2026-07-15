package com.void2.ds1music;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class SoundIconButton extends Button {

    private boolean enabledSound;

    private static final ResourceLocation ON =
            ResourceLocation.fromNamespaceAndPath(
                    "ds1music",
                    "textures/gui/sound_on.png"
            );

    private static final ResourceLocation OFF =
            ResourceLocation.fromNamespaceAndPath(
                    "ds1music",
                    "textures/gui/sound_off.png"
            );

    public SoundIconButton(int x, int y, int size, boolean initial, OnPress onPress) {
        super(x, y, size, size, Component.empty(), onPress, DEFAULT_NARRATION);
        this.enabledSound = initial;
    }

    public void toggle() {
        enabledSound = !enabledSound;
    }

    public boolean isEnabledSound() {
        return enabledSound;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {

        // фон кнопки (можешь убрать если не нужен)
        guiGraphics.fill(getX(), getY(),
                getX() + width,
                getY() + height,
                0x80000000);

        // иконка
        ResourceLocation tex = enabledSound ? ON : OFF;

        guiGraphics.blit(tex,
                getX() + 2,
                getY() + 2,
                0, 0,
                width - 4,
                height - 4,
                width - 4,
                height - 4);
    }
}