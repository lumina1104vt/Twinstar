package com.lumina.twinstar.algorithm.ae2;

import com.lumina.twinstar.algorithm.ae2.AEKeyCodec;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.algorithm.execution.InputRoles;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Data component on a hub pattern: which materials are catalysts. Keyed by the material's key; a material
 * not listed is used up ({@link #NORMAL}).
 * <p>
 * A {@link #CATALYST} is not used up. It goes into the machine with the first run of a batch (one machine, one
 * job, this pattern's runs in a row) and stays there for the runs after, and is taken back out when the last
 * run of the batch is done: no putting in and taking out for every run. That is always so, there is no switch.
 * <p>
 * {@link #TOOL} is never in this component: the compiler gives it to the pattern's tool (its tool slot), which is
 * not put in but right-clicks the machine once every material of a run is in.
 */
public record PatternInputRoles(List<Entry> entries) {
    public static final int NORMAL = InputRoles.NORMAL, CATALYST = InputRoles.CATALYST, TOOL = InputRoles.TOOL;
    /** what a player can set: used up or catalyst */
    public static final int KINDS = 2;

    public record Entry(AEKey key, int role) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                AEKeyCodec.KEY.fieldOf("key").forGetter(Entry::key),
                Codec.INT.fieldOf("role").forGetter(Entry::role)).apply(i, Entry::new));
    }

    public static final Codec<PatternInputRoles> CODEC = Entry.CODEC.listOf()
            .xmap(PatternInputRoles::new, PatternInputRoles::entries);

    public PatternInputRoles {
        entries = List.copyOf(entries);
    }

    /** The role of {@code key} ({@link #NORMAL} when not listed). */
    public int roleOf(AEKey key) {
        for (Entry e : entries)
            if (e.key().equals(key))
                return e.role();
        return NORMAL;
    }

    /** The other of the two: used up, catalyst. */
    public static int next(int role) {
        return role == NORMAL ? CATALYST : NORMAL;
    }
}
