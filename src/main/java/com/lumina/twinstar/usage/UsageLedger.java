package com.lumina.twinstar.usage;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * What every source (a machine, as an interface feeds it) used up lately, in a ring of time buckets: a bucket holds the
 * amounts of one stretch of time (default 10 seconds), the ring keeps the last 30 minutes. Nothing is visited every tick:
 * a record is one hash lookup and one small array scan, and a bucket is cleared only when the ring comes round to it again.
 * Pure Java ({@code UsageLedgerTest}); the game side only picks what is a source and what is a key.
 * <p>
 * Memory is bounded three ways: a source remembers at most {@code maxKeys} kinds of material at once (the rest is added up as
 * "other"), the ledger holds at most {@code maxSources} sources (a newer one is counted in {@link #droppedSources} and not
 * kept), and at most {@code maxEntries} (bucket, material) pairs over all sources (beyond it new materials go to "other").
 * Server thread only.
 *
 * @param <S> what an amount is used by (hashable)
 * @param <K> the material (hashable)
 */
public final class UsageLedger<S, K> {
    public static final int BUCKET_TICKS = 200;
    public static final int BUCKETS = 180;
    public static final int MAX_KEYS = 16;
    public static final int MAX_SOURCES = 512;
    public static final int MAX_ENTRIES = 100_000;

    private final int bucketTicks, buckets, maxKeys, maxSources, maxEntries;
    private final long startTick;
    private final Map<S, Series<K>> series = new HashMap<>();
    private int entries;
    private long droppedSources;

    public UsageLedger(long startTick) {
        this(startTick, BUCKET_TICKS, BUCKETS, MAX_KEYS, MAX_SOURCES, MAX_ENTRIES);
    }

    public UsageLedger(long startTick, int bucketTicks, int buckets, int maxKeys, int maxSources, int maxEntries) {
        this.startTick = startTick;
        this.bucketTicks = bucketTicks;
        this.buckets = buckets;
        this.maxKeys = maxKeys;
        this.maxSources = maxSources;
        this.maxEntries = maxEntries;
    }

    public int bucketTicks() {
        return bucketTicks;
    }

    public int buckets() {
        return buckets;
    }

    /** How many buckets a window of {@code ticks} covers (at least one, at most the ring). */
    public int bucketsFor(int ticks) {
        return Math.max(1, Math.min(buckets, (ticks + bucketTicks - 1) / bucketTicks));
    }

    /** Sources that were not kept because the ledger was full (since it began). */
    public long droppedSources() {
        return droppedSources;
    }

    public int sourceCount() {
        return series.size();
    }

    /** (bucket, material) pairs kept over all sources. */
    public int entryCount() {
        return entries;
    }

    public java.util.Set<S> sources() {
        return series.keySet();
    }

    public boolean has(S source) {
        return series.containsKey(source);
    }

    /** Changes what is kept with a source that is known (nothing for one that is not). */
    public void setLabel(S source, @Nullable String label) {
        Series<K> s = series.get(source);
        if (s != null)
            s.label = label;
    }

    // ───────────────────────────── recording ─────────────────────────────

    /**
     * {@code amount} of {@code key} was used by {@code source} at game tick {@code tick}. {@code kind} is a bit that tells
     * what kind of use it was (the source remembers the OR of all of them); {@code label} is anything the game side wants
     * kept with the source (the latest one wins; null leaves it).
     */
    public void add(S source, K key, long amount, long tick, int kind, @Nullable String label) {
        if (amount <= 0)
            return;
        Series<K> s = series.get(source);
        if (s == null) {
            if (series.size() >= maxSources) {
                droppedSources++;
                return;
            }
            s = new Series<>(buckets);
            series.put(source, s);
        }
        long bi = Math.floorDiv(tick, (long) bucketTicks);
        s.kinds |= kind;
        if (label != null)
            s.label = label;
        s.lastBucket = Math.max(s.lastBucket, bi);
        if (bi != s.sweptAt) {
            sweep(s, bi);
            s.sweptAt = bi;
        }
        int slot = (int) Math.floorMod(bi, (long) buckets);
        if (s.index[slot] != bi) {
            if (s.index[slot] > bi)
                return;                 // the clock went back: the bucket there is newer, so this late one is dropped
            clear(s, slot);
            s.index[slot] = bi;
        }
        Object[] ks = s.keys[slot];
        long[] am = s.amounts[slot];
        int n = s.count[slot];
        for (int i = 0; i < n; i++)
            if (ks[i].equals(key)) {
                am[i] = sat(am[i], amount);
                return;
            }
        // a new material in this bucket: kept when the source knows it already or has room for another kind
        boolean known = s.refs.containsKey(key);
        if (!(known || s.refs.size() < maxKeys) || entries >= maxEntries) {
            s.other[slot] = sat(s.other[slot], amount);
            return;
        }
        if (ks == null) {
            ks = s.keys[slot] = new Object[2];
            am = s.amounts[slot] = new long[2];
        }
        else if (n == ks.length) {
            ks = s.keys[slot] = java.util.Arrays.copyOf(ks, n * 2);
            am = s.amounts[slot] = java.util.Arrays.copyOf(am, n * 2);
        }
        ks[n] = key;
        am[n] = amount;
        s.count[slot] = n + 1;
        s.refs.merge(key, 1, Integer::sum);
        entries++;
    }

    private static long sat(long a, long b) {
        long r = a + b;
        return r < 0 ? Long.MAX_VALUE : r;
    }

    /** Clears the buckets of {@code s} that are older than the ring (nothing came for them to be overwritten). */
    private void sweep(Series<K> s, long nowBucket) {
        long oldest = nowBucket - buckets;
        for (int slot = 0; slot < buckets; slot++)
            if (s.index[slot] >= 0 && s.index[slot] <= oldest)
                clear(s, slot);
    }

    @SuppressWarnings("unchecked")
    private void clear(Series<K> s, int slot) {
        int n = s.count[slot];
        Object[] ks = s.keys[slot];
        for (int i = 0; i < n; i++) {
            K k = (K) ks[i];
            Integer c = s.refs.get(k);
            if (c != null) {
                if (c <= 1)
                    s.refs.remove(k);
                else
                    s.refs.put(k, c - 1);
            }
            ks[i] = null;
        }
        entries -= n;
        s.count[slot] = 0;
        s.other[slot] = 0;
        s.index[slot] = -1;
        s.keys[slot] = null;
        s.amounts[slot] = null;
    }

    /** Forgets the sources that used nothing for the whole ring (call now and then, e.g. every ten seconds). */
    public void prune(long nowTick) {
        long nowBucket = Math.floorDiv(nowTick, (long) bucketTicks);
        for (Iterator<Map.Entry<S, Series<K>>> it = series.entrySet().iterator(); it.hasNext(); ) {
            Series<K> s = it.next().getValue();
            if (s.lastBucket <= nowBucket - buckets) {
                for (int slot = 0; slot < buckets; slot++)
                    if (s.index[slot] >= 0)
                        clear(s, slot);
                it.remove();
            }
            else
                sweep(s, nowBucket);
        }
    }

    public void clearAll() {
        series.clear();
        entries = 0;
        droppedSources = 0;
    }

    // ───────────────────────────── reading ─────────────────────────────

    /** What one source used in several windows (each ending now): per kind of material one amount per window. */
    public record Usage<K>(List<K> keys, long[][] amounts, long[] other, int kinds, @Nullable String label) {
        /** the amounts of the material at {@code i} in all windows */
        public long[] of(int i) {
            return amounts[i];
        }
    }

    /**
     * What {@code source} used in each window ({@code windowBuckets[w]} buckets ending with the one now), or null when it is not
     * known. The materials come in order of their amount in the widest window, the biggest first.
     */
    @SuppressWarnings("unchecked")
    public @Nullable Usage<K> usage(S source, long nowTick, int[] windowBuckets) {
        Series<K> s = series.get(source);
        if (s == null)
            return null;
        long nowBucket = Math.floorDiv(nowTick, (long) bucketTicks);
        int wins = windowBuckets.length;
        Map<K, long[]> sums = new HashMap<>();
        long[] other = new long[wins];
        for (int slot = 0; slot < buckets; slot++) {
            long bi = s.index[slot];
            if (bi < 0 || bi > nowBucket)
                continue;
            long age = nowBucket - bi;          // 0 = the bucket now
            int n = s.count[slot];
            for (int w = 0; w < wins; w++) {
                if (age >= windowBuckets[w])
                    continue;
                other[w] = sat(other[w], s.other[slot]);
                for (int i = 0; i < n; i++) {
                    long[] sum = sums.computeIfAbsent((K) s.keys[slot][i], k -> new long[wins]);
                    sum[w] = sat(sum[w], s.amounts[slot][i]);
                }
            }
        }
        int widest = 0;
        for (int w = 1; w < wins; w++)
            if (windowBuckets[w] > windowBuckets[widest])
                widest = w;
        int sortBy = widest;
        List<Map.Entry<K, long[]>> list = new ArrayList<>(sums.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue()[sortBy], a.getValue()[sortBy]));
        List<K> keys = new ArrayList<>(list.size());
        long[][] amounts = new long[list.size()][];
        for (int i = 0; i < list.size(); i++) {
            keys.add(list.get(i).getKey());
            amounts[i] = list.get(i).getValue();
        }
        return new Usage<>(keys, amounts, other, s.kinds, s.label);
    }

    /**
     * How many ticks a window of {@code windowBuckets} really covers at {@code nowTick}: the buckets before the one now count whole,
     * the one now only as far as it has come, and never more than the time since the ledger began (so a rate is not too low just
     * after a start).
     */
    public long spanTicks(long nowTick, int windowBuckets) {
        long inBucket = Math.floorMod(nowTick, (long) bucketTicks) + 1;
        long span = (long) (windowBuckets - 1) * bucketTicks + inBucket;
        return Math.max(1, Math.min(span, nowTick - startTick + 1));
    }

    // ───────────────────────────── memory ─────────────────────────────

    /** A rough size in bytes (compressed references): the rings, the arrays of the buckets that are in use, the key tables. */
    public long memoryBytes() {
        long b = 64;
        for (Series<K> s : series.values()) {
            b += 120 + (long) buckets * (8 + 4 + 8 + 4 + 4 + 4) + 40 + 48L * s.refs.size();   // fields, ring arrays, key table
            for (int slot = 0; slot < buckets; slot++)
                if (s.keys[slot] != null)
                    b += 2 * 16 + (long) s.keys[slot].length * 12;
        }
        return b;
    }

    private static final class Series<K> {
        /** the number of the bucket each slot holds (time / bucket length), -1 = empty */
        final long[] index;
        final Object[][] keys;
        final long[][] amounts;
        final int[] count;
        /** what the bucket's materials that did not fit came to */
        final long[] other;
        /** how many buckets hold each material (the materials the source knows) */
        final Map<K, Integer> refs = new HashMap<>(8);
        int kinds;
        @Nullable String label;
        long lastBucket = Long.MIN_VALUE / 2;
        long sweptAt = Long.MIN_VALUE;

        Series(int buckets) {
            index = new long[buckets];
            java.util.Arrays.fill(index, -1);
            keys = new Object[buckets][];
            amounts = new long[buckets][];
            count = new int[buckets];
            other = new long[buckets];
        }
    }
}
