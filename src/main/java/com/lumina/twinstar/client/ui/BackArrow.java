package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;

/** The ↩ of the back button, drawn in pixels (the font's glyph is too small at this size). */
public class BackArrow extends UIElement {
    private static final int COLOR = 0xFFDEDEEA;

    public BackArrow() {
        layout(layout -> layout.width(9).height(8).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        draw(context.graphics, (int) getPositionX(), (int) getPositionY());
    }

    /** The arrow at (x, y), outside LDLib2 (the crafting graph's bar). */
    public static void draw(GuiGraphics g, int x, int y) {
        // a hook from the top right, down, then left to the arrow head
        g.fill(x + 7, y, x + 9, y + 6, COLOR);
        g.fill(x + 2, y + 4, x + 9, y + 6, COLOR);
        g.fill(x + 1, y + 3, x + 2, y + 7, COLOR);
        g.fill(x, y + 4, x + 1, y + 6, COLOR);
        g.fill(x + 2, y + 2, x + 3, y + 8, COLOR);
    }
}
