package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.Pos;
import com.lumina.twinstar.port.World;
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

    private static final String AIR = "minecraft:air";

    /** The cells within {@code range} sideways and one up or down of {@code pos}. */
    private static List<Pos> around(Pos pos, int range) {
        List<Pos> cells = new ArrayList<>();
        // the order Minecraft's own box walk has (x fastest): ties in distance keep it
        for (int z = -range; z <= range; z++)
            for (int y = -1; y <= 1; y++)
                for (int x = -range; x <= range; x++)
                    cells.add(pos.offset(x, y, z));
        return cells;
    }

    private SubMachines() {
    }

    /** {@code others}: where the other machines with an interface are (those of another kind are skipped). */
    public static List<Pos> find(World level, Pos pos, int range, List<String> kinds, Collection<Pos> others) {
        List<Pos> out = new ArrayList<>();
        if (kinds.isEmpty())
            return out;
        range = Math.max(1, Math.min(MAX_RANGE, range));
        String self = level.blockItemId(pos);
        List<Pos> rivals = new ArrayList<>();
        for (Pos op : others)
            if (!op.equals(pos) && op.distManhattan(pos) <= 4 * range + 2 && level.isLoaded(op)
                    && level.blockItemId(op).equals(self))
                rivals.add(op);
        List<List<Pos>> byKind = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++)
            byKind.add(new ArrayList<>());
        for (Pos at : around(pos, range)) {
            if (at.equals(pos) || !level.isLoaded(at))
                continue;
            String item = level.blockItemId(at);
            if (item.equals(AIR))
                continue;
            int kind = kinds.indexOf(item);
            if (kind < 0)
                continue;
            double d = at.distSqr(pos);
            boolean theirs = false;
            for (Pos rival : rivals)
                theirs |= at.distSqr(rival) < d;
            if (!theirs)
                byKind.get(kind).add(at);
        }
        for (List<Pos> kind : byKind) {
            kind.sort(Comparator.comparingDouble(a -> a.distSqr(pos)));
            out.addAll(kind);
        }
        return out;
    }
}
