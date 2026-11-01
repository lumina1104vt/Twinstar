package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/** LDLib2's text field in Minecraft's pixel font (see {@link PixelLabel}). */
public class PixelTextField extends TextField {
    @Override
    public Font getFont() {
        return Minecraft.getInstance().font;
    }
}
