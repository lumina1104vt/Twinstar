package com.lumina.twinstar.algorithm.ae2;

import com.lumina.twinstar.util.AEKeyCodec;

import appeng.api.stacks.AEKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * Data component on a hub pattern: which faces of the machine each material goes in through, for
 * a machine access interface set to "specified" (like Advanced AE's advanced processing patterns,
 * but any number of faces per material: tried in order until one takes it). Keyed by the
 * material's key; a material not listed goes in through the interface's own face.
 */
public record PatternInputFaces(List<Entry> entries) {
    /** {@code faces}: bit {@link Direction#get3DDataValue()} set for each face. */
    public record Entry(AEKey key, int faces) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                AEKeyCodec.KEY.fieldOf("key").forGetter(Entry::key),
                Codec.INT.fieldOf("faces").forGetter(Entry::faces)).apply(i, Entry::new));
    }

    public static final Codec<PatternInputFaces> CODEC = Entry.CODEC.listOf()
            .xmap(PatternInputFaces::new, PatternInputFaces::entries);

    public PatternInputFaces {
        entries = List.copyOf(entries);
    }

    /** The face bits of {@code key} (0 = not set). */
    public int facesOf(AEKey key) {
        for (Entry e : entries)
            if (e.key().equals(key))
                return e.faces();
        return 0;
    }

    /** The faces of a bit set, in {@link Direction} order (down, up, north, south, west, east). */
    public static List<Direction> directions(int faces) {
        List<Direction> out = new ArrayList<>();
        for (Direction d : Direction.values())
            if ((faces & (1 << d.get3DDataValue())) != 0)
                out.add(d);
        return out;
    }
}
