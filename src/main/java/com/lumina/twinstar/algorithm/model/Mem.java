package com.lumina.twinstar.algorithm.model;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Map;
import java.util.BitSet;

/**
 * Java memory of our own data, estimated structure by structure (array length × element size +
 * object header + references) instead of walking the heap. Sizes are for a 64-bit HotSpot JVM with
 * compressed references (4-byte references, 12-byte object headers, 16-byte array headers, objects
 * aligned to 8 bytes), which is what a Minecraft server or client runs on. {@code MemTest} checks the
 * numbers against a real JVM (heap measured before and after a garbage collection).
 * <p>
 * Shared things are not counted twice by callers: {@link Amount}'s cached small values and
 * {@link Rational#ZERO}/{@link Rational#ONE} count as 0 here because every holder points to the same
 * object.
 */
public final class Mem {
    public static final int HEADER = 12;
    public static final int ARRAY_HEADER = 16;
    public static final int REF = 4;

    private Mem() {
    }

    /** Rounds up to the JVM's 8-byte object alignment. */
    public static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }

    /** An object with {@code fieldBytes} of fields (references count {@link #REF}). */
    public static long obj(int fieldBytes) {
        return align(HEADER + fieldBytes);
    }

    /** An array of {@code length} elements of {@code elemBytes} each, without what its elements point to. */
    public static long array(long length, int elemBytes) {
        return align(ARRAY_HEADER + length * elemBytes);
    }

    public static long ints(int[] a) {
        return a == null ? 0 : array(a.length, 4);
    }

    public static long doubles(double[] a) {
        return a == null ? 0 : array(a.length, 8);
    }

    public static long longs(long[] a) {
        return a == null ? 0 : array(a.length, 8);
    }

    /** An outer array of int arrays: its own slots plus every row (shared rows are counted by the caller if it knows). */
    public static long intRows(int[][] rows) {
        long b = array(rows.length, REF);
        for (int[] r : rows)
            b += ints(r);
        return b;
    }

    public static long doubleRows(double[][] rows) {
        long b = array(rows.length, REF);
        for (double[] r : rows)
            b += doubles(r);
        return b;
    }

    /** Just the outer array of an {@code T[]} of references. */
    public static long refs(int length) {
        return array(length, REF);
    }

    public static long bitSet(BitSet s) {
        return obj(8 + 4 + 1 + 4) + array(s.size() / 64, 8);
    }

    /** A {@code BigInteger} and its magnitude array (JDK 17+ layout: signum + four cached ints + mag). */
    public static long bigInteger(BigInteger v) {
        return obj(4 + 4 + 4 * 4) + array((v.bitLength() + 31) / 32, 4);
    }

    /** {@code java.util.ArrayList} of {@code size} elements with room for {@code capacity}: elements not counted. */
    public static long arrayList(int capacity) {
        return obj(4 + 4 + 4) + array(capacity, REF);
    }

    /** A boxed {@code Integer} outside the −128..127 cache. */
    public static long boxedInt(int v) {
        return v >= -128 && v <= 127 ? 0 : obj(4);
    }

    /** {@code java.util.HashMap} with {@code size} entries: table + one 32-byte node per entry; keys and values not counted. */
    public static long hashMap(int size) {
        if (size == 0)
            return obj(4 * 4 + 4 + 4 + 4 + 4 + 4);
        int table = 16;
        while (table * 3 / 4 < size)
            table <<= 1;
        return obj(4 * 4 + 4 + 4 + 4 + 4 + 4) + array(table, REF) + (long) size * obj(4 + 4 + 4 + 4);
    }

    /** {@code java.util.TreeMap} with {@code size} entries: one 40-byte entry each; keys and values not counted. */
    public static long treeMap(int size) {
        return obj(4 + 4 + 4 + 4 + 4) + (long) size * obj(5 * REF + 1);
    }

    /** {@code java.util.PriorityQueue} holding {@code size} elements. */
    public static long priorityQueue(int size) {
        return obj(4 + 4 + 4 + 4) + array(Math.max(11, size + (size >> 1)), REF);
    }

    /** Boxed keys of an {@code Integer}-keyed map. */
    public static long boxedKeys(Collection<Integer> keys) {
        long b = 0;
        for (int k : keys)
            b += boxedInt(k);
        return b;
    }

    /** An {@link Amount}: 0 when it is one of the shared cached small values. */
    public static long amount(Amount a) {
        return a.memoryBytes();
    }

    /** A {@code Map<Integer, Amount>} (HashMap): table, nodes, boxed keys and the amounts. */
    public static long hashMapOfAmounts(Map<Integer, Amount> m) {
        long b = hashMap(m.size());
        for (Map.Entry<Integer, Amount> e : m.entrySet())
            b += boxedInt(e.getKey()) + amount(e.getValue());
        return b;
    }

    /** A {@code Map<Integer, Integer>} (HashMap). */
    public static long hashMapOfInts(Map<Integer, Integer> m) {
        long b = hashMap(m.size());
        for (Map.Entry<Integer, Integer> e : m.entrySet())
            b += boxedInt(e.getKey()) + boxedInt(e.getValue());
        return b;
    }

    /** A {@code Map<Integer, Long>} (HashMap). */
    public static long hashMapOfLongs(Map<Integer, Long> m) {
        long b = hashMap(m.size());
        for (Map.Entry<Integer, Long> e : m.entrySet())
            b += boxedInt(e.getKey()) + obj(8);
        return b;
    }
}
