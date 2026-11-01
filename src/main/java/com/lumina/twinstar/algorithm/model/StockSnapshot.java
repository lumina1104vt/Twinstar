package com.lumina.twinstar.algorithm.model;

import java.util.BitSet;
import java.util.Map;
import java.util.Set;

/**
 * Network stock taken on the main thread; the planner (background thread) only reads this, never
 * AE2's storage.
 */
public final class StockSnapshot {
    private final AmountArray stock;
    private final BitSet infinite;

    public StockSnapshot(AmountArray stock, BitSet infinite) {
        this.stock = stock;
        this.infinite = infinite;
    }

    public static StockSnapshot empty() {
        return new StockSnapshot(new AmountArray(0), new BitSet());
    }

    public Amount stock(int item) {
        return stock.get(item);
    }

    public boolean isInfinite(int item) {
        return infinite.get(item);
    }

    public int size() {
        return stock.size();
    }

    /**
     * The stock with some kept out of the plan (the plan screen's "keep" mark): {@code keep} amounts
     * come off the item (never below zero; an infinite source stays infinite), {@code keepAll} items
     * count as having none.
     */
    public StockSnapshot withKept(Map<Integer, Amount> keep, Set<Integer> keepAll) {
        if (keep.isEmpty() && keepAll.isEmpty())
            return this;
        int size = stock.size();
        for (int id : keep.keySet())
            size = Math.max(size, id + 1);
        for (int id : keepAll)
            size = Math.max(size, id + 1);
        AmountArray s = stock.resized(size);
        BitSet inf = (BitSet) infinite.clone();
        keep.forEach((id, a) -> {
            if (!inf.get(id))
                s.set(id, s.get(id).compareTo(a) > 0 ? s.get(id).sub(a) : Amount.ZERO);
        });
        for (int id : keepAll) {
            s.set(id, Amount.ZERO);
            inf.clear(id);
        }
        return new StockSnapshot(s, inf);
    }

    /** A working copy sized for {@code items} item ids (the item index may have grown since). */
    public AmountArray copyStock(int items) {
        return stock.resized(Math.max(items, stock.size()));
    }
}
