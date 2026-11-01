package com.lumina.twinstar.algorithm.model;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/**
 * One order's own temporary prices and marks; the theory layer is never touched. Few entries, so a
 * map is fine.
 */
public final class Overlay {
    /** log10 price per item. Missing = theory price; −∞ = free ("隨便用"); +∞ = used up. */
    public final Map<Integer, Double> logPrice = new HashMap<>();
    /** Confirm-screen "保留" marks: avoid routes that use these, but still use them when unavoidable. */
    public final BitSet reserved = new BitSet();
    /** Patterns this order cannot use at all (a machine pattern whose machine is not on the grid). */
    public final BitSet disabled = new BitSet();

    public boolean isEmpty() {
        return logPrice.isEmpty() && reserved.isEmpty();
    }

    /** Confirm screen: 保留 = try to avoid. */
    public void markReserved(int item) {
        reserved.set(item);
        logPrice.remove(item);
    }

    /** Confirm screen: 隨便用 = treat as free. */
    public void markFree(int item) {
        reserved.clear(item);
        logPrice.put(item, LogMath.ZERO);
    }

    /** Confirm screen: back to automatic prices. */
    public void markAuto(int item) {
        reserved.clear(item);
        logPrice.remove(item);
    }

    public Overlay copy() {
        Overlay o = new Overlay();
        o.logPrice.putAll(logPrice);
        o.reserved.or(reserved);
        o.disabled.or(disabled);
        return o;
    }
}
