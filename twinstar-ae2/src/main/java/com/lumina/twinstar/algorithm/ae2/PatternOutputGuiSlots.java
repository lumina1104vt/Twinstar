package com.lumina.twinstar.algorithm.ae2;

import com.lumina.twinstar.algorithm.ae2.AEKeyCodec;

import appeng.api.stacks.AEKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Data component on a hub pattern: for machines that keep their items inside and show them only in a GUI, which
 * slot of that GUI each product is taken out of (1 = the first slot that is not the player's inventory, in the
 * menu's order). Keyed by the product's key; a product not listed is taken from any slot that does not take
 * what it holds (a result slot).
 */
public record PatternOutputGuiSlots(List<Entry> entries) {
    public static final int MAX = 99;

    public record Entry(AEKey key, int slot) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                AEKeyCodec.KEY.fieldOf("key").forGetter(Entry::key),
                Codec.INT.fieldOf("slot").forGetter(Entry::slot)).apply(i, Entry::new));
    }

    public static final Codec<PatternOutputGuiSlots> CODEC = Entry.CODEC.listOf()
            .xmap(PatternOutputGuiSlots::new, PatternOutputGuiSlots::entries);

    public PatternOutputGuiSlots {
        entries = List.copyOf(entries);
    }

    /** The slot of {@code key} (0 = not set). */
    public int slotOf(AEKey key) {
        for (Entry e : entries)
            if (e.key().equals(key))
                return e.slot();
        return 0;
    }
}
