package com.lumina.twinstar.client.ui;

import appeng.api.stacks.AEKey;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHolder;
import org.jetbrains.annotations.Nullable;

/** An LDLib2 element (a row, a line) that is about one material: the mouse on any part of it means that material. */
public class KeyRow extends UIElement implements KeyHolder {
    private final AEKey key;

    public KeyRow(AEKey key) {
        this.key = key;
    }

    @Override
    public AEKey heldKey() {
        return key;
    }

    /**
     * The material under the mouse in an LDLib2 UI: the nearest {@link KeyHolder} around what the
     * mouse is on (as the UI last saw it), with that element's square.
     */
    public static @Nullable KeyHit under(@Nullable ModularUI ui) {
        if (ui == null)
            return null;
        for (UIElement hovered : ui.getLastHoveredElements())
            for (UIElement e = hovered; e != null; e = e.getParent())
                if (e instanceof KeyHolder holder && holder.heldKey() != null)
                    return new KeyHit(holder.heldKey(), Math.round(e.getPositionX()), Math.round(e.getPositionY()),
                            Math.round(e.getSizeWidth()), Math.round(e.getSizeHeight()));
        return null;
    }
}
