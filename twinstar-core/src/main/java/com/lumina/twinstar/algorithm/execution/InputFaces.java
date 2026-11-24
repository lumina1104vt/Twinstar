package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.Face;

import java.util.ArrayList;
import java.util.List;

/** The faces of a pattern input, as a bit set (bit = the face's number). */
public final class InputFaces {
    private InputFaces() {
    }

    /** The faces of a bit set, in the order down, up, north, south, west, east. */
    public static List<Face> faces(int bits) {
        List<Face> out = new ArrayList<>();
        for (Face f : Face.values())
            if ((bits & (1 << f.ordinal())) != 0)
                out.add(f);
        return out;
    }
}
