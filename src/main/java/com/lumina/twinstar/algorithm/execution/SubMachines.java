package com.lumina.twinstar.algorithm.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The sub-machines of a machine: blocks of the given kinds within {@code range} blocks across (one up
 * and down), the first kind first and the nearest first within a kind. A block nearer another machine
 * of the same kind as this one is that machine's (two altars side by side keep their own pedestals).
 * Used by the hub to put materials in and by the interface to power them.
 */
public final class SubMachines {
    public static final int MAX_RANGE = 8;

    private SubMachines() {
    }

    /** {@code others}: where the other machines with an interface are (those of another kind are skipped). */
    public static List<BlockPos> find(ServerLevel level, BlockPos pos, int range, List<Item> kinds, Collection<BlockPos> others) {
        List<BlockPos> out = new ArrayList<>();
        if (kinds.isEmpty())
            return out;
        range = Math.max(1, Math.min(MAX_RANGE, range));
        Item self = level.getBlockState(pos).getBlock().asItem();
        List<BlockPos> rivals = new ArrayList<>();
        for (BlockPos op : others)
            if (!op.equals(pos) && op.distManhattan(pos) <= 4 * range + 2 && level.isLoaded(op)
                    && level.getBlockState(op).getBlock().asItem() == self)
                rivals.add(op);
        List<List<BlockPos>> byKind = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++)
            byKind.add(new ArrayList<>());
        for (BlockPos at : BlockPos.betweenClosed(pos.offset(-range, -1, -range), pos.offset(range, 1, range))) {
            if (at.equals(pos) || !level.isLoaded(at))
                continue;
            Item item = level.getBlockState(at).getBlock().asItem();
            if (item == Items.AIR)
                continue;
            int kind = kinds.indexOf(item);
            if (kind < 0)
                continue;
            double d = at.distSqr(pos);
            boolean theirs = false;
            for (BlockPos rival : rivals)
                theirs |= at.distSqr(rival) < d;
            if (!theirs)
                byKind.get(kind).add(at.immutable());
        }
        for (List<BlockPos> kind : byKind) {
            kind.sort(Comparator.comparingDouble(a -> a.distSqr(pos)));
            out.addAll(kind);
        }
        return out;
    }
}
