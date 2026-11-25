package com.lumina.twinstar.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Lights a block up in the world for a few seconds (the usage screen's double click). The core cannot draw it itself: the mod
 * that owns the machines sets the {@link Lighter} at client start-up, and until then nothing happens.
 */
public final class MachineHighlight {
    private MachineHighlight() {
    }

    public interface Lighter {
        /**
         * @param face     the side of the block that is marked
         * @param host     the block of the interface the machine is reached through (a line is drawn to it), or null
         * @param hostSide the side of {@code host} the interface part sits on, or null for a block of its own
         */
        void light(BlockPos machine, Direction face, @Nullable BlockPos host, @Nullable Direction hostSide);
    }

    private static volatile @Nullable Lighter lighter;

    public static void set(@Nullable Lighter l) {
        lighter = l;
    }

    public static boolean available() {
        return lighter != null;
    }

    public static void light(BlockPos machine, Direction face, @Nullable BlockPos host, @Nullable Direction hostSide) {
        Lighter l = lighter;
        if (l != null)
            l.light(machine, face, host, hostSide);
    }
}
