package com.lumina.twinstar.algorithm.model;

import java.math.BigInteger;
import java.util.HashMap;

/**
 * One amount per item id (B's need / done / avail / ledger, a cycle's stock, ...). Callers only see
 * {@link Amount}; inside, a {@code long[]} is the fast path and slots that overflowed are kept aside.
 */
public final class AmountArray {
    private final long[] small;
    private HashMap<Integer, BigInteger> big;   // null until some slot overflows; holds only overflowed slots

    public AmountArray(int size) {
        this.small = new long[size];
    }

    private AmountArray(long[] small, HashMap<Integer, BigInteger> big) {
        this.small = small;
        this.big = big;
    }

    public int size() {
        return small.length;
    }

    public boolean isBig(int i) {
        return big != null && big.containsKey(i);
    }

    public Amount get(int i) {
        if (i < 0 || i >= small.length)
            return Amount.ZERO;
        if (big != null) {
            BigInteger b = big.get(i);
            if (b != null)
                return Amount.of(b);
        }
        return Amount.of(small[i]);
    }

    public int signum(int i) {
        if (i < 0 || i >= small.length)
            return 0;
        if (big != null) {
            BigInteger b = big.get(i);
            if (b != null)
                return b.signum();
        }
        return Long.signum(small[i]);
    }

    public void set(int i, Amount v) {
        if (v.isBig()) {
            if (big == null)
                big = new HashMap<>();
            big.put(i, v.bigValue());
            small[i] = 0;
        } else {
            if (big != null)
                big.remove(i);
            small[i] = v.longValue();
        }
    }

    public void add(int i, Amount v) {
        if (!v.isBig() && !isBig(i)) {
            long a = small[i];
            long b = v.longValue();
            long r = a + b;
            if (((a ^ r) & (b ^ r)) >= 0) {
                small[i] = r;
                return;
            }
        }
        set(i, get(i).add(v));
    }

    public void sub(int i, Amount v) {
        set(i, get(i).sub(v));
    }

    public AmountArray copy() {
        return new AmountArray(small.clone(), big == null ? null : new HashMap<>(big));
    }

    /** A copy resized to {@code size}; new slots are zero, slots past {@code size} are dropped. */
    public AmountArray resized(int size) {
        AmountArray r = new AmountArray(size);
        int n = Math.min(size, small.length);
        System.arraycopy(small, 0, r.small, 0, n);
        if (big != null)
            big.forEach((k, v) -> {
                if (k < size)
                    r.set(k, Amount.of(v));
            });
        return r;
    }
}
