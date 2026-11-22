package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lumina.twinstar.client.hover.KeyHolder;

/** An AE2 key's icon (item, fluid, or any other key type), 16x16, as an LDLib2 element. */
public class KeyIcon extends UIElement implements KeyHolder {
    private final AEKey key;

    public KeyIcon(AEKey key) {
        this.key = key;
        layout(layout -> layout.width(16).height(16).flexShrink(0));
    }

    @Override
    public AEKey heldKey() {
        return key;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        AEKeyRendering.drawInGui(context.mc, context.graphics, (int) getPositionX(), (int) getPositionY(), key);
    }
}
