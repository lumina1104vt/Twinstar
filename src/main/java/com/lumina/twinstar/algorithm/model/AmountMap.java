package com.lumina.twinstar.algorithm.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Sparse id → amount (missing list, a job's inventory, ...). Zero entries are removed. Iteration is
 * in ascending id order so the same input always gives the same output.
 */
public final class AmountMap {
    private final TreeMap<Integer, Amount> map = new TreeMap<>();

    public Amount get(int id) {
        return map.getOrDefault(id, Amount.ZERO);
    }

    public void set(int id, Amount v) {
        if (v.isZero())
            map.remove(id);
        else
            map.put(id, v);
    }

    public void add(int id, Amount v) {
        if (!v.isZero())
            set(id, get(id).add(v));
    }

    public void sub(int id, Amount v) {
        if (!v.isZero())
            set(id, get(id).sub(v));
    }

    public Amount remove(int id) {
        Amount v = map.remove(id);
        return v == null ? Amount.ZERO : v;
    }

    public boolean contains(int id) {
        return map.containsKey(id);
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public int size() {
        return map.size();
    }

    public int[] keys() {
        return map.keySet().stream().mapToInt(Integer::intValue).toArray();
    }

    public void forEach(BiConsumer<Integer, Amount> action) {
        map.forEach(action);
    }

    public Iterable<Map.Entry<Integer, Amount>> entries() {
        return map.entrySet();
    }

    public Amount total() {
        Amount t = Amount.ZERO;
        for (Amount v : map.values())
            t = t.add(v);
        return t;
    }

    public AmountMap copy() {
        AmountMap r = new AmountMap();
        r.map.putAll(map);
        return r;
    }

    public void clear() {
        map.clear();
    }

    @Override
    public String toString() {
        return map.toString();
    }
}
