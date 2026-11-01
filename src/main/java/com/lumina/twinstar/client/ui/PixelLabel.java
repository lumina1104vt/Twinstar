package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

/**
 * A label in Minecraft's own pixel font, like the rest of the game's screens: LDLib2 otherwise draws
 * text in its smooth font (thinner strokes), whatever font the style names.
 */
public class PixelLabel extends Label {
    public PixelLabel() {
    }

    public PixelLabel(Component text) {
        setText(text);
    }

    @Override
    public Font getFont() {
        return Minecraft.getInstance().font;
    }

    /**
     * A button whose caption is a {@link PixelLabel}, centred. The caption carries the class of
     * LDLib2's own button text, so the stylesheet's button text rules (disabled colour) apply to it.
     */
    public static Button button(Component text) {
        Button button = new Button();
        button.noText();
        PixelLabel caption = new PixelLabel(text);
        caption.addClass("__button_text__");
        caption.textStyle(t -> t.adaptiveWidth(true));
        button.addChild(caption);
        button.layout(l -> l.justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        return button;
    }
}
