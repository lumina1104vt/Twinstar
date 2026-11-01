package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lumina.twinstar.client.TwinStarBadge;

/** The rotating twin star ({@link TwinStarBadge}) as an LDLib2 element. */
public class TwinStarElement extends UIElement {
    public TwinStarElement() {
        layout(layout -> layout.width(TwinStarBadge.SIZE).height(TwinStarBadge.SIZE).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        // LDLib2 queues the panel behind this; draw it first, or the panel is painted over the star
        context.graphics.flush();
        TwinStarBadge.draw(context.graphics, (int) getPositionX(), (int) getPositionY());
    }
}
