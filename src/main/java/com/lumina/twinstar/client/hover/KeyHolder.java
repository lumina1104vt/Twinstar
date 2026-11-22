package com.lumina.twinstar.client.hover;

import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

/**
 * An LDLib2 element that stands for one material (an icon, or a whole row about it). The mouse being
 * on it or on anything inside it makes that material the one under the mouse.
 */
public interface KeyHolder {
    /** The material, or null while there is none to show yet. */
    @Nullable AEKey heldKey();
}
