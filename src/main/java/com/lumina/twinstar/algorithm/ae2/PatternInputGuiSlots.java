package com.lumina.twinstar.algorithm.ae2;

import com.mojang.serialization.Codec;

import java.util.List;

/**
 * Data component on a hub pattern: for machines that keep their items inside and show them only in a GUI (an
 * ordered crafting table), which slot of that GUI each material slot of the pattern goes into: the n-th entry is for
 * the pattern's n-th material (in the order AE2 lists them), 1 = the first GUI slot that is not the player's
 * inventory, in the menu's order; 0 = the first slot that takes it. Per slot, not per material: the same material
 * can go into many places.
 */
public record PatternInputGuiSlots(List<Integer> slots) {
    public static final int MAX = 99;

    public static final Codec<PatternInputGuiSlots> CODEC = Codec.INT.listOf().xmap(PatternInputGuiSlots::new, PatternInputGuiSlots::slots);

    public PatternInputGuiSlots {
        slots = List.copyOf(slots);
    }

    /** The GUI slot of the {@code ordinal}-th material (0 = not set). */
    public int slotAt(int ordinal) {
        return ordinal >= 0 && ordinal < slots.size() ? slots.get(ordinal) : 0;
    }
}
