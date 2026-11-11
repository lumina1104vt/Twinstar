package com.lumina.twinstar.api;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * Which machine a hub pattern is for. The pattern item keeps it (Gravity Tech's synthesis pattern);
 * the mod that owns that item tells the engine how to read it. A hub pattern with a machine is sent
 * to that kind of machine through machine access interfaces ({@link IMachineAccess}); one without
 * is finished inside the hub. It may also name sub-machines: blocks near the machine (an altar's
 * pedestals) that the materials set to go there go into, in their order.
 */
public final class PatternMachines {
    private static Function<AEItemKey, @Nullable Item> reader = key -> null;
    private static Function<AEItemKey, List<Item>> subReader = key -> List.of();

    private PatternMachines() {
    }

    /** Called once by the mod whose pattern items name a machine. */
    public static void setReader(Function<AEItemKey, @Nullable Item> reader) {
        PatternMachines.reader = reader;
    }

    /** Called once by the mod whose pattern items name sub-machines. */
    public static void setSubMachineReader(Function<AEItemKey, List<Item>> reader) {
        PatternMachines.subReader = reader;
    }

    /** The sub-machines of the encoded pattern {@code definition}, in the order they are tried. */
    public static List<Item> subMachinesOf(AEItemKey definition) {
        return subReader.apply(definition);
    }

    /** The machine of the encoded pattern {@code definition}, or null. */
    public static @Nullable Item of(AEItemKey definition) {
        return reader.apply(definition);
    }
}
