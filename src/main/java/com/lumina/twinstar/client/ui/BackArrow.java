package com.lumina.twinstar.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** The ↩ of the back button, drawn in pixels (the font's glyph is too small at this size). */
public final class BackArrow {
    private static final int COLOR = 0xFFDEDEEA;

    private BackArrow() {
    }

    /** The arrow at (x, y). */
    public static void draw(GuiGraphics g, int x, int y) {
        // a hook from the top right, down, then left to the arrow head
        g.fill(x + 7, y, x + 9, y + 6, COLOR);
        g.fill(x + 2, y + 4, x + 9, y + 6, COLOR);
        g.fill(x + 1, y + 3, x + 2, y + 7, COLOR);
        g.fill(x, y + 4, x + 1, y + 6, COLOR);
        g.fill(x + 2, y + 2, x + 3, y + 8, COLOR);
    }
}
