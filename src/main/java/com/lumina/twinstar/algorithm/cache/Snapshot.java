package com.lumina.twinstar.algorithm.cache;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.ByteUnits;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.Pattern;
import org.jetbrains.annotations.Nullable;

/**
 * One immutable version of the crafting graph and its theory values. Published by
 * {@link TheoryLayer}; a background planning run keeps reading the version it started with.
 * <p>
 * Inner arrays are shared between versions and never written after publishing; callers must not
 * modify the arrays they get either.
 */
public final class Snapshot {
    final long version;
    final int items;

    // ── items (index = item id) ──
    final int[] level;              // raw = 0
    final int[] scc;                // −1 = not in a policy cycle
    final double[] logCost;         // log10(theory unit cost); +∞ = cannot be made
    final double[] logRawPrice;     // log10(raw price) from the stock tier, used for raws and overlays
    final int[] best;               // best pattern; −1 = none (raw)
    final int[][] bomItems;         // sparse BOM: raws needed for ONE of the item
    final double[][] bomLog;        // parallel: log10(amount); estimates only, B computes exact amounts
    final int[][] producers;        // patterns making the item, in route order
    final int[][] consumers;        // patterns using the item

    // ── patterns (index = pattern id; null = removed) ──
    final Pattern[] patterns;
    final double[] patRunLogCost;   // log10(material cost of one run)
    final int[][] patBomItems;      // bom_p per RUN (divide by the output amount for one item)
    final double[][] patBomLog;

    // ── policy cycles ──
    final Scc[] sccs;
    private final ByteUnits units;

    Snapshot(long version, int items, int[] level, int[] scc, double[] logCost, double[] logRawPrice, int[] best,
             int[][] bomItems, double[][] bomLog, int[][] producers, int[][] consumers,
             Pattern[] patterns, double[] patRunLogCost, int[][] patBomItems, double[][] patBomLog, Scc[] sccs, ByteUnits units) {
        this.version = version;
        this.items = items;
        this.level = level;
        this.scc = scc;
        this.logCost = logCost;
        this.logRawPrice = logRawPrice;
        this.best = best;
        this.bomItems = bomItems;
        this.bomLog = bomLog;
        this.producers = producers;
        this.consumers = consumers;
        this.patterns = patterns;
        this.patRunLogCost = patRunLogCost;
        this.patBomItems = patBomItems;
        this.patBomLog = patBomLog;
        this.sccs = sccs;
        this.units = units;
    }

    /**
     * Only patterns, no theory values: what a job restored from a save runs on (its patterns by id,
     * {@code null} for ids it does not use).
     */
    public static Snapshot ofPatterns(Pattern[] patterns, ByteUnits units) {
        int n = patterns.length;
        return new Snapshot(-1, 0, new int[0], new int[0], new double[0], new double[0], new int[0],
                new int[0][], new double[0][], new int[0][], new int[0][], patterns, new double[n],
                new int[n][], new double[n][], new Scc[0], units);
    }

    /** How amounts count against the black-hole capacity (AE2's bytes). */
    public ByteUnits units() {
        return units;
    }

    public long version() {
        return version;
    }

    public int itemCount() {
        return items;
    }

    public int patternCount() {
        return patterns.length;
    }

    public @Nullable Pattern pattern(int p) {
        return p >= 0 && p < patterns.length ? patterns[p] : null;
    }

    public int level(int x) {
        return x < items ? level[x] : 0;
    }

    public int scc(int x) {
        return x < items ? scc[x] : -1;
    }

    public Scc sccAt(int s) {
        return sccs[s];
    }

    public int sccCount() {
        return sccs.length;
    }

    public double logCost(int x) {
        return x < items ? logCost[x] : TheoryLayer.LOG_HIGH;
    }

    public double logRawPrice(int x) {
        return x < items ? logRawPrice[x] : TheoryLayer.LOG_HIGH;
    }

    public int best(int x) {
        return x < items ? best[x] : -1;
    }

    /** A raw item: no pattern on the network makes it. */
    public boolean isRaw(int x) {
        return x >= items || producers[x].length == 0;
    }

    /**
     * Mined: only loops without an outside input make it (raw iron ⇄ raw iron block). Priced like a
     * raw by its stock; its recipes are conversions (take a block apart), and when neither stock nor
     * a conversion covers it, the item itself is missing.
     */
    public boolean isMined(int x) {
        return x < items && producers[x].length > 0 && best[x] < 0 && logCost[x] != LogMath.INF;
    }

    /** Patterns that make {@code x}, best route first. Read-only. */
    public int[] producers(int x) {
        return x < items ? producers[x] : EMPTY;
    }

    /** Patterns that use {@code x}. Read-only. */
    public int[] consumers(int x) {
        return x < items ? consumers[x] : EMPTY;
    }

    public int[] bomItems(int x) {
        return x < items ? bomItems[x] : new int[]{x};
    }

    public double[] bomLog(int x) {
        return x < items ? bomLog[x] : new double[]{0};
    }

    public int[] patBomItems(int p) {
        return patBomItems[p];
    }

    public double[] patBomLog(int p) {
        return patBomLog[p];
    }

    /** log10 of the raws one unit of {@code x} needs through pattern {@code p}, for raw {@code k}; −∞ if none. */
    public double patBomUnitLog(int p, int x, int k) {
        int[] ks = patBomItems[p];
        for (int i = 0; i < ks.length; i++)
            if (ks[i] == k)
                return patBomLog[p][i] - patterns[p].mainOutputLog10(x);
        return LogMath.ZERO;
    }

    public double patRunLogCost(int p) {
        return patRunLogCost[p];
    }

    /** log10 of the unit cost of making {@code x} with {@code p} (other outputs are not deducted). */
    public double unitLogCost(int p, int x) {
        double out = patterns[p].mainOutputLog10(x);
        if (out == LogMath.ZERO)
            return LogMath.INF;
        double c = patRunLogCost[p];
        if (c == LogMath.ZERO || c == LogMath.INF)
            return c;
        return c - out;
    }

    private static final int[] EMPTY = new int[0];
}
