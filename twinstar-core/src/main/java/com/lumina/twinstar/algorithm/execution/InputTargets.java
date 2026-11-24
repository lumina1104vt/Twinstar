package com.lumina.twinstar.algorithm.execution;

/**
 * Where a pattern input goes. A target is one int: the kind in the low four bits, the distance in front
 * (1…{@link #MAX_DISTANCE}) above them.
 */
public final class InputTargets {
    /** not set: with sub-machines, {@link #MACHINE} for the first material and {@link #SUB} for the rest; else {@link #MACHINE} */
    public static final int DEFAULT = 0;
    /** into the machine: its capabilities, or a right-click when it has none */
    public static final int MACHINE = 4;
    /** into the sub-machines near it, in their order */
    public static final int SUB = 1;
    /** placed as a block in front of its opening */
    public static final int FRONT_BLOCK = 2;
    /** dropped as an item in front of its opening */
    public static final int FRONT_DROP = 3;
    public static final int KINDS = 5;
    public static final int MAX_DISTANCE = 8;

    private InputTargets() {
    }

    /** {@code target} with {@link #DEFAULT} made what it stands for; {@code first}: the pattern's first material. */
    public static int resolve(int target, boolean hasSubMachines, boolean first) {
        return kind(target) == DEFAULT ? (hasSubMachines && !first ? SUB : MACHINE) : target;
    }

    public static int kind(int target) {
        int k = target & 0xF;
        return k < KINDS ? k : DEFAULT;
    }

    /** How far in front, for the front kinds (1 when not set). */
    public static int distance(int target) {
        return Math.max(1, Math.min(MAX_DISTANCE, target >> 4));
    }
}
