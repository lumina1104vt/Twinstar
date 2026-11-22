package com.lumina.twinstar.client.hover;

import appeng.api.stacks.AEKey;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The squares a hand-drawn screen drew this frame and the material in each: it adds one for every
 * material it draws and asks which one is under the mouse. What was added last lies on top.
 */
public final class KeyHits {
    private final List<KeyHit> hits = new ArrayList<>();

    /** Forgets everything (start of a frame). */
    public void clear() {
        hits.clear();
    }

    public void add(AEKey key, int x, int y, int w, int h) {
        if (w > 0 && h > 0)
            hits.add(new KeyHit(key, x, y, w, h));
    }

    /** The topmost square holding the point, or null. */
    public @Nullable KeyHit at(double px, double py) {
        for (int i = hits.size() - 1; i >= 0; i--) {
            KeyHit h = hits.get(i);
            if (px >= h.x() && px < h.x() + h.w() && py >= h.y() && py < h.y() + h.h())
                return h;
        }
        return null;
    }

    public int size() {
        return hits.size();
    }
}
