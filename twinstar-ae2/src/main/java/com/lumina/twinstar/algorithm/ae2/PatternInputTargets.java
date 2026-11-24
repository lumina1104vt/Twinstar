package com.lumina.twinstar.algorithm.ae2;

import com.lumina.twinstar.algorithm.ae2.AEKeyCodec;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.algorithm.execution.InputTargets;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Data component on a hub pattern: where each material goes, when not into the machine itself.
 * Into the machine's sub-machines (blocks of the kinds the pattern names, near the machine: an
 * altar's pedestals), or into the world in front of the machine's opening (an atomic
 * reconstructor's beam), placed as a block or dropped as an item. Keyed by the material's key; a
 * material not listed goes by {@link #DEFAULT}: when the pattern names sub-machines, its first material
 * into the machine and the rest into them (an altar: the seed base on it, the essences on its
 * pedestals); else all into the machine.
 * <p>
 * A target is one int: the kind in the low four bits, the distance in front (1…{@link #MAX_DISTANCE})
 * above them.
 */
public record PatternInputTargets(List<Entry> entries) {
    /** not set: with sub-machines, {@link #MACHINE} for the first material and {@link #SUB} for the rest; else {@link #MACHINE} */
    public static final int DEFAULT = InputTargets.DEFAULT;
    /** into the machine: its capabilities, or a right-click when it has none */
    public static final int MACHINE = InputTargets.MACHINE;
    /** into the sub-machines near it, in their order */
    public static final int SUB = InputTargets.SUB;
    /** placed as a block in front of its opening */
    public static final int FRONT_BLOCK = InputTargets.FRONT_BLOCK;
    /** dropped as an item in front of its opening */
    public static final int FRONT_DROP = InputTargets.FRONT_DROP;
    public static final int KINDS = InputTargets.KINDS;
    /** The order a screen offers them in. */
    public static final int[] ORDER = {DEFAULT, MACHINE, SUB, FRONT_BLOCK, FRONT_DROP};
    public static final int MAX_DISTANCE = InputTargets.MAX_DISTANCE;

    public record Entry(AEKey key, int target) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                AEKeyCodec.KEY.fieldOf("key").forGetter(Entry::key),
                Codec.INT.fieldOf("target").forGetter(Entry::target)).apply(i, Entry::new));
    }

    public static final Codec<PatternInputTargets> CODEC = Entry.CODEC.listOf()
            .xmap(PatternInputTargets::new, PatternInputTargets::entries);

    public PatternInputTargets {
        entries = List.copyOf(entries);
    }

    /** The target of {@code key} ({@link #DEFAULT} when not listed). */
    public int targetOf(AEKey key) {
        for (Entry e : entries)
            if (e.key().equals(key))
                return e.target();
        return DEFAULT;
    }

    /** {@code target} with {@link #DEFAULT} made what it stands for; {@code first}: the pattern's first material. */
    public static int resolve(int target, boolean hasSubMachines, boolean first) {
        return InputTargets.resolve(target, hasSubMachines, first);
    }

    /** The kind after {@code kind} in {@link #ORDER}. */
    public static int next(int kind) {
        for (int i = 0; i < ORDER.length; i++)
            if (ORDER[i] == kind)
                return ORDER[(i + 1) % ORDER.length];
        return DEFAULT;
    }

    public static int of(int kind, int distance) {
        return kind == FRONT_BLOCK || kind == FRONT_DROP ? kind | clampDistance(distance) << 4 : kind;
    }

    public static int kind(int target) {
        return InputTargets.kind(target);
    }

    /** How far in front, for the front kinds (1 when not set). */
    public static int distance(int target) {
        return InputTargets.distance(target);
    }

    public static boolean isFront(int target) {
        int k = kind(target);
        return k == FRONT_BLOCK || k == FRONT_DROP;
    }

    private static int clampDistance(int d) {
        return Math.max(1, Math.min(MAX_DISTANCE, d));
    }
}
