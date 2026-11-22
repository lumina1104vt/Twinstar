package com.lumina.twinstar.client.hover;

import org.jetbrains.annotations.Nullable;

/**
 * A screen of ours that draws materials (items, fluids, other AE2 keys) by hand, not in slots: it tells
 * what is under the mouse, so JEI and EMI can bookmark it (their A key) like an item in a slot.
 * Implemented by the screen itself; {@link HoverKeys} asks the screen that is open.
 */
public interface KeyHover {
    /** The material drawn at the mouse position (screen pixels), or null when there is none. */
    @Nullable KeyHit keyAt(double mouseX, double mouseY);
}
