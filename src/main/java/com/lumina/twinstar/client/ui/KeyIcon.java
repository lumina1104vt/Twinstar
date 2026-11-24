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
        // items are drawn raised by the game; fluids and chemicals are flat sprites at depth 0 and would sit
        // under a slot background drawn before them, so they are lifted the same way
        boolean flat = !(key instanceof appeng.api.stacks.AEItemKey);
        if (flat) {
            context.graphics.pose().pushPose();
            context.graphics.pose().translate(0, 0, 100);
        }
        AEKeyRendering.drawInGui(context.mc, context.graphics, (int) getPositionX(), (int) getPositionY(), key);
        if (flat)
            context.graphics.pose().popPose();
    }
}
