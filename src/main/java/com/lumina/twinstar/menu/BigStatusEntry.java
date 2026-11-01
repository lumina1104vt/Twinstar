package com.lumina.twinstar.menu;

import org.jetbrains.annotations.Nullable;

/** Added to AE2's CraftingStatusEntry: the exact amounts, sent along with AE2's own longs. */
public interface BigStatusEntry {
    @Nullable BigStatusAmounts twinstar$big();

    void twinstar$setBig(@Nullable BigStatusAmounts big);
}
