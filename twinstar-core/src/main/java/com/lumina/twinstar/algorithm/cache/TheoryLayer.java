package com.lumina.twinstar.algorithm.cache;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.ByteUnits;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.Mem;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import com.lumina.twinstar.algorithm.planning.ExactSimplex;
import com.lumina.twinstar.metrics.Alloc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntFunction;

/**
 * A — the theory layer. Keeps, for every item, the cheapest way to make it in theory (cost, best
 * pattern, flattened BOM, level, policy cycles), independent of any order. Stock only enters through
 * raw prices, which change when a raw's stock crosses a power of ten.
 * <p>
 * Works on the components of the cost graph (Tarjan: an input whose cost matters → the pattern's
 * main products), kept between flushes, sources first. A component is always computed from scratch
 * (old values inside a loop cannot be trusted); a change recomputes the components it touches and
 * goes downstream component by component, stopping where nothing a consumer reads changed. A full
 * rebuild is the same with every component marked, so the two give the same result. Pattern
 * changes run Tarjan again (linear) and recompute every component that is new, split or merged.
 * <p>
 * A component that is a loop is solved by {@link PolicyCycles}, on this class's arrays.
 * <p>
 * Not thread-safe by itself: every public method is synchronized. {@link #snapshot()} returns the
 * last published immutable version without locking.
 */
public final class TheoryLayer {
    /** log10 of the price of a raw with no stock (10: using it always means a shortage). */
    public static final double LOG_HIGH = 1.0;
    /** A tier only drops once the stock falls to 0.8 × 10^tier (no flapping at the boundary). */
    public static final double HYSTERESIS = 0.8;
    /** Relative change treated as "unchanged" (stops propagation; policy switches need more). 【待定】 */
    public static final double EPS = 1e-9;
    /** Shared by every item and pattern with nothing in the list (arrays are never written, only replaced). */
    static final int[] EMPTY = new int[0];
    static final double[] EMPTY_LOG = new double[0];
    /** The BOM amounts of a raw: itself, log10(1) = 0. One array for all raws instead of one each. */
    static final double[] RAW_LOG = {0};

    // ── patterns (index = id) ──
    final ArrayList<Pattern> patterns = new ArrayList<>();
    private double[] patRunLogCost = new double[0];
    private int[][] patBomItems = new int[0][];
    private double[][] patBomLog = new double[0][];

    // ── items (index = id) ──
    private int items;
    int[] level = new int[0];
    int[] scc = new int[0];
    double[] logCost = new double[0];
    int[] best = new int[0];
    int[][] bomItems = new int[0][];
    double[][] bomLog = new double[0][];
    int[][] producers = new int[0][];
    private int[][] consumers = new int[0][];
    private int[] priceTier = new int[0];
    final BitSet noStock = new BitSet();
    final BitSet infinite = new BitSet();
    /**
     * Items only loops without an outside input make, loops that do not grow (raw iron ⇄ raw iron
     * block, wheat ⇄ hay bale): they are mined, so they are priced like raws by their own stock;
     * their recipes stay as conversions. Decided by a rebuild only.
     */
    final BitSet mined = new BitSet();
    /** a pattern was added or removed, or new items appeared, since the last flush */
    private boolean patternsChanged;
    /** the policy cycles of every component, in component order (rebuilt at publish) */
    private final ArrayList<Scc> sccs = new ArrayList<>();

    // ── components of the cost graph, sources first, kept between flushes ──
    private List<int[]> comps = new ArrayList<>();
    int[] compOf = new int[0];                  // item → its component; −1 until the next flush
    private List<List<Scc>> compSccs = new ArrayList<>();   // per component, its policy cycles
    /** items whose component must be recomputed at the next flush */
    private final BitSet pending = new BitSet();
    /** the policy cycles of the component being computed */
    List<Scc> building = new ArrayList<>();

    private boolean needRebuild = true;
    private long version;
    private volatile Snapshot published = null;
    private ByteUnits units = ByteUnits.ITEMS;
    /** a price tier, "none in stock" or "infinite" changed since the last version */
    private boolean stockMoved;
    private volatile FlushStats lastFlush = new FlushStats(0, false, 0, 0);

    // ─────────────────────────────── public API ───────────────────────────────

    /** How amounts count against the black-hole capacity; published with the next version. */
    public synchronized void setUnits(ByteUnits units) {
        this.units = units;
    }

    /** The last published version (null before the first flush). */
    public Snapshot snapshot() {
        return published;
    }

    public synchronized void addPattern(Pattern p) {
        ensurePatterns(p.id + 1);
        if (patterns.get(p.id) != null)
            removePattern(p.id);
        int maxItem = -1;
        for (int i = 0; i < p.inputCount(); i++) {
            maxItem = Math.max(maxItem, p.inputItem(i));
            for (int j = 0; j < p.alternativeCount(i); j++)
                maxItem = Math.max(maxItem, p.alternative(i, j));
        }
        for (int i = 0; i < p.outputCount(); i++)
            maxItem = Math.max(maxItem, p.outputItem(i));
        ensureItems(maxItem + 1);
        patterns.set(p.id, p);
        for (int i = 0; i < p.outputCount(); i++)
            if (!p.isByproduct(i))      // a byproduct is no route to the item
                producers[p.outputItem(i)] = append(producers[p.outputItem(i)], p.id);
        for (int in : distinctInputs(p))
            consumers[in] = append(consumers[in], p.id);
        patternsChanged = true;
        for (int i = 0; i < p.outputCount(); i++)
            if (!p.isByproduct(i))
                pending.set(p.outputItem(i));
    }

    public synchronized void removePattern(int id) {
        if (id < 0 || id >= patterns.size() || patterns.get(id) == null)
            return;
        Pattern p = patterns.get(id);
        for (int i = 0; i < p.outputCount(); i++)
            if (!p.isByproduct(i))
                producers[p.outputItem(i)] = without(producers[p.outputItem(i)], id);
        for (int in : distinctInputs(p))
            consumers[in] = without(consumers[in], id);
        patterns.set(id, null);
        patBomItems[id] = EMPTY;
        patBomLog[id] = EMPTY_LOG;
        patternsChanged = true;
        for (int i = 0; i < p.outputCount(); i++)
            if (!p.isByproduct(i))
                pending.set(p.outputItem(i));
    }

    /** Reads a whole stock snapshot; raws whose price tier changed become dirty, and loop members whose "any in stock" did. */
    public synchronized void applyStock(StockSnapshot s) {
        ensureItems(s.size());
        for (int k = 0; k < items; k++)
            onStockChanged(k, s.stock(k), s.isInfinite(k));
    }

    /** Price tiers have a buffer: up at 10^(t+1), down only at 0.8 × 10^t. */
    public synchronized void onStockChanged(int k, Amount stock, boolean isInfinite) {
        ensureItems(k + 1);
        boolean changed = false;
        boolean have = false;     // "any in stock" flipped: a seed of a loop came or ran out
        if (isInfinite != infinite.get(k)) {
            infinite.set(k, isInfinite);
            changed = have = true;
        }
        boolean none = stock.signum() <= 0;
        if (none != noStock.get(k)) {
            noStock.set(k, none);
            changed = have = true;
        }
        if (!none) {
            double lg = stock.log10();
            int t = priceTier[k];
            while (lg >= t + 1)
                t++;
            while (t >= 1 && lg <= Math.log10(HYSTERESIS) + t)
                t--;
            if (t != priceTier[k]) {
                priceTier[k] = t;
                changed = true;
            }
        }
        if (changed)
            stockMoved = true;      // the snapshot's raw prices change even when no component does
        if (changed && (producers[k].length == 0 || mined.get(k)))
            pending.set(k);
        else if (have && inLoop(k))
            pending.set(k);         // a loop without seeds is priced as out of stock (PolicyCycles)
    }

    /** {@code k} is a member of a loop as of the last flush. */
    private boolean inLoop(int k) {
        if (k >= compOf.length || compOf[k] < 0 || compOf[k] >= comps.size())
            return false;
        return comps.get(compOf[k]).length > 1 || selfLoop(k);
    }

    /**
     * Applies all pending changes and publishes a new version (the last one again when nothing
     * changed since): the components the changes touch
     * are recomputed, sources first, and so is everything downstream of a component whose result
     * changed. A rebuild marks every component; otherwise the same steps.
     */
    public synchronized Snapshot flush() {
        long t0 = System.nanoTime();
        long a0 = Alloc.threadBytes();
        if (published != null && !needRebuild && !patternsChanged && !stockMoved && pending.isEmpty()) {
            // nothing changed since the last version (the stock sync runs every second): keep it
            lastFlush = new FlushStats(published.version(), false, 0, System.nanoTime() - t0);
            return published;
        }
        boolean rebuilt = needRebuild;
        java.util.TreeSet<Integer> dirty = new java.util.TreeSet<>();
        if (needRebuild || patternsChanged) {
            BitSet minedBefore = (BitSet) mined.clone();
            computeMined();
            regroup(dirty, rebuilt);
            // mined items are priced by their stock: a change moves everything that uses them
            if (!rebuilt) {
                minedBefore.xor(mined);
                for (int x = minedBefore.nextSetBit(0); x >= 0; x = minedBefore.nextSetBit(x + 1))
                    dirty.add(compOf[x]);
            }
        }
        if (!rebuilt)
            for (int x = pending.nextSetBit(0); x >= 0; x = pending.nextSetBit(x + 1))
                dirty.add(compOf[x]);
        pending.clear();
        patternsChanged = false;
        needRebuild = false;
        stockMoved = false;

        int processed = 0;
        while (!dirty.isEmpty()) {
            int c = dirty.pollFirst();
            int[] comp = comps.get(c);
            Before before = new Before(comp);
            computeComponent(c);
            processed++;
            // a consumer reads cost, BOM, level and the route: when any of them moved, its component is next
            for (int i = 0; i < comp.length; i++) {
                int m = comp[i];
                if (before.same(i, m))
                    continue;
                for (int q : consumers[m]) {
                    Pattern p = patterns.get(q);
                    for (int o = 0; o < p.outputCount(); o++)
                        if (!p.isByproduct(o) && compOf[p.outputItem(o)] > c)
                            dirty.add(compOf[p.outputItem(o)]);
                }
            }
        }
        Snapshot s = publish();
        long took = System.nanoTime() - t0;
        FlushStats st = new FlushStats(s.version(), rebuilt, processed, took, Alloc.since(a0));
        lastFlush = st;
        notePerf(st);
        return s;
    }

    /**
     * What the last flush did, for logs.
     *
     * @param rebuilt   built everything again
     * @param processed components computed (all of them for a rebuild)
     */
    public record FlushStats(long version, boolean rebuilt, int processed, long nanos, long allocBytes) {
        public FlushStats(long version, boolean rebuilt, int processed, long nanos) {
            this(version, rebuilt, processed, nanos, -1);
        }
    }

    /**
     * Speed of the updates that changed something, for the viewer's memory and speed report.
     *
     * @param incrMaxNanos the slowest of the last {@link #RECENT_INCREMENTAL} incremental updates
     */
    public record FlushPerf(long flushes, long rebuilds, long lastNanos, boolean lastRebuilt, long lastRebuildNanos,
                            long incrMaxNanos, long lastAlloc) {
        public static final FlushPerf NONE = new FlushPerf(0, 0, 0, false, 0, 0, -1);
    }

    private static final int RECENT_INCREMENTAL = 8;
    private final long[] recentIncremental = new long[RECENT_INCREMENTAL];
    private long perfFlushes, perfRebuilds, perfLastRebuild;
    private volatile FlushPerf perf = FlushPerf.NONE;

    /** An update that did something is counted; one that found nothing to do is not (it would hide the real ones). */
    private void notePerf(FlushStats st) {
        if (!st.rebuilt() && st.processed() == 0 && perfFlushes > 0)
            return;
        if (st.rebuilt()) {
            perfRebuilds++;
            perfLastRebuild = st.nanos();
        } else {
            recentIncremental[(int) (perfFlushes % RECENT_INCREMENTAL)] = st.nanos();
        }
        perfFlushes++;
        long max = 0;
        for (long n : recentIncremental)
            max = Math.max(max, n);
        perf = new FlushPerf(perfFlushes, perfRebuilds, st.nanos(), st.rebuilt(), perfLastRebuild, max, st.allocBytes());
    }

    /** Speed numbers of the updates so far (never blocks). */
    public FlushPerf perf() {
        return perf;
    }

    /** What a component's members were before it is recomputed: unchanged ones stop the change here. */
    private final class Before {
        private final double[] cost;
        private final int[] route;
        private final int[] lvl;
        private final int[][] bomK;
        private final double[][] bomV;

        Before(int[] comp) {
            int n = comp.length;
            cost = new double[n];
            route = new int[n];
            lvl = new int[n];
            bomK = new int[n][];
            bomV = new double[n][];
            for (int i = 0; i < n; i++) {
                int m = comp[i];
                cost[i] = logCost[m];
                route[i] = best[m];
                lvl[i] = level[m];
                bomK[i] = bomItems[m];
                bomV[i] = bomLog[m];
            }
        }

        /** cost within ε, the same best route, the same level and the BOM within ε item by item */
        boolean same(int i, int m) {
            if (route[i] != best[m] || lvl[i] != level[m] || !LogMath.sameWithin(cost[i], logCost[m], EPS))
                return false;
            if (!Arrays.equals(bomK[i], bomItems[m]))
                return false;
            for (int j = 0; j < bomV[i].length; j++)
                if (!LogMath.sameWithin(bomV[i][j], bomLog[m][j], EPS))
                    return false;
            return true;
        }
    }

    public FlushStats lastFlushStats() {
        return lastFlush;
    }

    /** Throws the incremental state away and builds everything again (tests compare the two). */
    public synchronized Snapshot rebuild() {
        needRebuild = true;
        return flush();
    }

    // ─────────────────────────────── storage ───────────────────────────────

    private void ensurePatterns(int n) {
        while (patterns.size() < n)
            patterns.add(null);
        if (patRunLogCost.length < n) {
            int old = patRunLogCost.length;
            patRunLogCost = Arrays.copyOf(patRunLogCost, n);
            patBomItems = Arrays.copyOf(patBomItems, n);
            patBomLog = Arrays.copyOf(patBomLog, n);
            for (int i = old; i < n; i++) {
                patRunLogCost[i] = LogMath.INF;
                patBomItems[i] = EMPTY;
                patBomLog[i] = EMPTY_LOG;
            }
        }
    }

    private void ensureItems(int n) {
        if (n <= items)
            return;
        int old = items;
        items = n;
        level = Arrays.copyOf(level, n);
        scc = Arrays.copyOf(scc, n);
        logCost = Arrays.copyOf(logCost, n);
        best = Arrays.copyOf(best, n);
        bomItems = Arrays.copyOf(bomItems, n);
        bomLog = Arrays.copyOf(bomLog, n);
        producers = Arrays.copyOf(producers, n);
        consumers = Arrays.copyOf(consumers, n);
        priceTier = Arrays.copyOf(priceTier, n);
        compOf = Arrays.copyOf(compOf, n);
        patternsChanged = true;     // new items have no component yet: Tarjan runs again
        for (int i = old; i < n; i++) {
            compOf[i] = -1;
            scc[i] = -1;
            best[i] = -1;
            producers[i] = EMPTY;
            consumers[i] = EMPTY;
            noStock.set(i);
            logCost[i] = logRawPrice(i);
            bomItems[i] = new int[]{i};
            bomLog[i] = RAW_LOG;
        }
    }

    private Snapshot publish() {
        // policy cycles are numbered in component order, as a rebuild creates them
        sccs.clear();
        for (List<Scc> list : compSccs)
            for (int j = 0; j < list.size(); j++) {
                Scc c = list.get(j);
                if (c.index != sccs.size()) {
                    c = c.withIndex(sccs.size());
                    list.set(j, c);
                }
                for (int i = 0; i < c.memberCount(); i++)
                    scc[c.member(i)] = c.index;
                sccs.add(c);
            }
        version++;
        Snapshot s = new Snapshot(version, items, level.clone(), scc.clone(), logCost.clone(), rawPrices(), best.clone(),
                bomItems.clone(), bomLog.clone(), producers.clone(), consumers.clone(),
                patterns.toArray(new Pattern[0]), Arrays.copyOf(patRunLogCost, patterns.size()),
                Arrays.copyOf(patBomItems, patterns.size()), Arrays.copyOf(patBomLog, patterns.size()),
                sccs.toArray(new Scc[0]), units);
        s.workingBytes = workingBytes();
        published = s;
        return s;
    }

    /**
     * Java memory of what the layer keeps for itself beside the published version (see {@link Mem}): its own
     * copies of the per-item and per-pattern outer arrays (the rows inside are shared with the version),
     * the price tiers, the components and the bit sets.
     */
    private long workingBytes() {
        long b = Mem.obj(120);
        int n = items;
        b += Mem.ints(level) + Mem.ints(scc) + Mem.doubles(logCost) + Mem.ints(best) + Mem.ints(priceTier) + Mem.ints(compOf);
        b += 4L * Mem.refs(n) + Mem.doubles(patRunLogCost) + 2L * Mem.refs(patBomItems.length);
        b += Mem.arrayList(patterns.size()) + Mem.arrayList(sccs.size()) + Mem.arrayList(comps.size()) + Mem.arrayList(compSccs.size());
        for (int[] c : comps)
            b += Mem.ints(c);
        for (List<Scc> l : compSccs)
            b += Mem.arrayList(l.size());
        b += Mem.bitSet(noStock) + Mem.bitSet(infinite) + Mem.bitSet(mined) + Mem.bitSet(pending);
        return b;
    }

    private double[] rawPrices() {
        double[] r = new double[items];
        for (int i = 0; i < items; i++)
            r[i] = logRawPrice(i);
        return r;
    }

    double logRawPrice(int k) {
        if (infinite.get(k))
            return LogMath.ZERO;
        if (noStock.get(k))
            return LOG_HIGH;
        return -priceTier[k];
    }

    // ─────────────────────────────── costs & BOM ───────────────────────────────

    /** log10 of the material cost of one run: consumed inputs + tools ÷ durability; catalysts and containers are free. */
    void computeRunCost(int q) {
        Pattern p = patterns.get(q);
        double sum = LogMath.ZERO;
        for (int i = 0; i < p.inputCount(); i++) {
            int y = p.inputItem(i);
            double term;
            switch (p.inputKind(i)) {
                case CONSUME -> term = LogMath.mul(p.inputAmount(i).log10(), logCost[y]);
                case TOOL -> term = toolDurability(p, i) > 0
                        ? LogMath.mul(logCost[y], -Math.log10(toolDurability(p, i)))
                        : LogMath.mul(p.inputAmount(i).log10(), logCost[y]);
                default -> term = LogMath.ZERO;
            }
            sum = LogMath.add(sum, term);
        }
        patRunLogCost[q] = sum;
    }

    private static int toolDurability(Pattern p, int slot) {
        return p.inputDurability(slot);
    }

    double unitCost(int q, int x) {
        double out = patterns.get(q).mainOutputLog10(x);
        if (out == LogMath.ZERO)
            return LogMath.INF;
        double c = patRunLogCost[q];
        if (c == LogMath.ZERO || c == LogMath.INF)
            return c;
        return c - out;
    }

    /** bom_p for one run: Σ consumed (amount × bom[input]) + Σ tools (bom[tool] ÷ durability). */
    void computePatBom(int q) {
        Pattern p = patterns.get(q);
        TreeMap<Integer, Double> acc = new TreeMap<>();
        for (int i = 0; i < p.inputCount(); i++) {
            int y = p.inputItem(i);
            double scale;
            switch (p.inputKind(i)) {
                case CONSUME -> scale = p.inputAmount(i).log10();
                case TOOL -> scale = toolDurability(p, i) > 0 ? -Math.log10(toolDurability(p, i)) : p.inputAmount(i).log10();
                default -> {
                    continue;
                }
            }
            int[] ks = bomItems[y];
            double[] vs = bomLog[y];
            for (int j = 0; j < ks.length; j++)
                acc.merge(ks[j], vs[j] + scale, LogMath::add);
        }
        patBomItems[q] = acc.keySet().stream().mapToInt(Integer::intValue).toArray();
        patBomLog[q] = acc.values().stream().mapToDouble(Double::doubleValue).toArray();
    }

    void setItemBomFrom(int x, int q) {
        double out = patterns.get(q).mainOutputLog10(x);
        int[] ks = patBomItems[q];
        double[] vs = patBomLog[q].clone();
        for (int j = 0; j < vs.length; j++)
            vs[j] -= out;
        bomItems[x] = ks;
        bomLog[x] = vs;
    }

    void setRaw(int x) {
        logCost[x] = logRawPrice(x);
        best[x] = -1;
        bomItems[x] = new int[]{x};
        bomLog[x] = RAW_LOG;
    }

    /** Route order: possible routes first, then provider priority, cost, processing steps, the current best, location, id. */
    private int compareRoutes(int a, int b, int x) {
        double ca = unitCost(a, x);
        double cb = unitCost(b, x);
        boolean fa = ca != LogMath.INF;
        boolean fb = cb != LogMath.INF;
        if (fa != fb)
            return fa ? -1 : 1;
        Pattern pa = patterns.get(a);
        Pattern pb = patterns.get(b);
        if (pa.priority != pb.priority)
            return pa.priority > pb.priority ? -1 : 1;
        int c = Double.compare(ca, cb);
        if (c != 0)
            return c;
        // same cost: fewer processing steps first (costs count only raws, not the steps)
        c = Integer.compare(maxInputLevel(a, x), maxInputLevel(b, x));
        if (c != 0)
            return c;
        // a tie keeps the current best first: reversible recipes (block ⇄ 9 ingots) cost exactly the
        // same as the real source, and the id must not put "take a block apart" ahead of smelting
        if (a == best[x] || b == best[x])
            return a == best[x] ? -1 : 1;
        c = Integer.compare(pa.where.ordinal(), pb.where.ordinal());
        if (c != 0)
            return c;
        return Integer.compare(a, b);
    }

    /** Policy switches need a real gain (ties never switch). */
    boolean strictlyBetter(int a, int b, int x) {
        if (b < 0)
            return true;
        double ca = unitCost(a, x);
        double cb = unitCost(b, x);
        boolean fa = ca != LogMath.INF;
        boolean fb = cb != LogMath.INF;
        if (fa != fb)
            return fa;
        if (!fa)
            return false;
        int prA = patterns.get(a).priority;
        int prB = patterns.get(b).priority;
        if (prA != prB)
            return prA > prB;
        if (cb == LogMath.ZERO)
            return false;
        return ca < cb + Math.log10(1 - EPS);
    }

    void sortProducers(int x) {
        int[] ps = producers[x];
        if (ps.length < 2)
            return;
        Integer[] boxed = new Integer[ps.length];
        for (int i = 0; i < ps.length; i++)
            boxed[i] = ps[i];
        Arrays.sort(boxed, (a, b) -> compareRoutes(a, b, x));
        int[] sorted = new int[ps.length];
        for (int i = 0; i < ps.length; i++)
            sorted[i] = boxed[i];
        producers[x] = sorted;
    }

    /**
     * Highest level among the inputs of {@code q} (made for {@code x}) in {@code x}'s component or an
     * earlier one. A catalyst or container from a later component is a back edge: its level is not
     * known yet, and a rebuild would not know it either.
     */
    int maxInputLevel(int q, int x) {
        Pattern p = patterns.get(q);
        int m = 0;
        for (int i = 0; i < p.inputCount(); i++)
            if (compOf[p.inputItem(i)] <= compOf[x])
                m = Math.max(m, level[p.inputItem(i)]);
        return m;
    }

    // ─────────────────────────────── full build ───────────────────────────────

    /**
     * Runs Tarjan on the cost graph again (every pattern; linear) and marks the components that are
     * not exactly a component of before (new, split or merged), or all of them for a rebuild. The
     * policy cycles of an unchanged component carry over.
     */
    private void regroup(java.util.TreeSet<Integer> dirty, boolean all) {
        int[] nodes = new int[items];
        for (int i = 0; i < items; i++)
            nodes[i] = i;
        List<int[]> oldComps = comps;
        List<List<Scc>> oldSccs = compSccs;
        int[] oldCompOf = compOf.clone();
        comps = Tarjan.components(nodes, this::successors);
        compSccs = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) {
            int[] comp = comps.get(c);
            for (int m : comp)
                compOf[m] = c;
            int was = oldCompOf[comp[0]];
            boolean same = !all && was >= 0 && Arrays.equals(oldComps.get(was), comp);
            compSccs.add(same ? oldSccs.get(was) : new ArrayList<>());
            if (!same)
                dirty.add(c);
        }
        if (all)
            return;
        // a catalyst or container counts (for levels and the loop guesses) only when its component
        // comes first: where that flipped, the result depends on it, so the component is redone
        for (int x = 0; x < items; x++) {
            if (oldCompOf[x] < 0)
                continue;
            for (int q : producers[x]) {
                Pattern p = patterns.get(q);
                for (int i = 0; i < p.inputCount(); i++) {
                    InputKind kind = p.inputKind(i);
                    int y = p.inputItem(i);
                    if (kind == InputKind.CONSUME || kind == InputKind.TOOL || oldCompOf[y] < 0)
                        continue;
                    if ((oldCompOf[y] <= oldCompOf[x]) != (compOf[y] <= compOf[x]))
                        dirty.add(compOf[x]);
                }
            }
        }
    }

    /**
     * Computes one component from scratch, its inputs from earlier components final: a single item
     * from its cheapest route, a loop by policy iteration. Nothing depends on what the members were.
     */
    private void computeComponent(int c) {
        int[] comp = comps.get(c);
        for (int m : comp) {
            // the order of the routes must not depend on history either: start from id order
            producers[m] = producers[m].clone();
            Arrays.sort(producers[m]);
            scc[m] = -1;
            level[m] = 0;
            best[m] = -1;
            logCost[m] = LogMath.INF;
        }
        building = new ArrayList<>();
        if (comp.length == 1 && !selfLoop(comp[0]))
            processSingle(comp[0]);
        else
            new PolicyCycles(this).policyIteration(comp);
        compSccs.set(c, building);
        // B and C1 take the first route as the policy: policy iteration picks best[] on its own
        // (ties never switch), so put it in front where the sorted order differs
        for (int m : comp)
            bestFirst(m);
    }

    private void bestFirst(int x) {
        int[] ps = producers[x];
        int b = best[x];
        if (b < 0 || ps.length < 2 || ps[0] == b)
            return;
        int[] out = new int[ps.length];
        out[0] = b;
        int k = 1;
        for (int q : ps)
            if (q != b)
                out[k++] = q;
        producers[x] = out;
    }

    /**
     * Which items are mined, from the recipes alone (prices never change it, so stock changes stay
     * incremental). Starting from the raws, everything a pattern makes from items already
     * obtainable is obtainable. What is left is only reachable through loops; the loops among it
     * that nothing else left feeds are the cause. A loop that grows (1 X → 2 X, seed + dirt →
     * 2 seeds) stays a growth loop needing a seed; one that does not (raw iron ⇄ raw iron block)
     * was mined. Both count as obtainable from then on, and the pass goes on until all is settled.
     */
    private void computeMined() {
        mined.clear();
        BitSet ok = new BitSet(items);
        int[] waiting = new int[patterns.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int q = 0; q < patterns.size(); q++)
            if (patterns.get(q) != null)
                waiting[q] = distinctInputs(patterns.get(q)).length;
        for (int i = 0; i < items; i++)
            if (producers[i].length == 0) {
                ok.set(i);
                queue.add(i);
            }
        for (int q = 0; q < patterns.size(); q++)
            if (patterns.get(q) != null && waiting[q] == 0)
                obtain(patterns.get(q), ok, queue);
        while (true) {
            while (!queue.isEmpty())
                for (int q : consumers[queue.poll()])
                    if (--waiting[q] == 0)
                        obtain(patterns.get(q), ok, queue);
            List<Integer> rest = new ArrayList<>();
            for (int i = 0; i < items; i++)
                if (!ok.get(i))
                    rest.add(i);
            if (rest.isEmpty())
                return;
            List<int[]> loops = sourceLoops(rest);
            if (loops.isEmpty())
                return;     // cannot happen: a source that is no loop would have only obtainable inputs
            for (int[] loop : loops) {
                if (!grows(loop, ok))
                    for (int m : loop)
                        mined.set(m);
                for (int m : loop) {
                    ok.set(m);
                    queue.add(m);
                }
            }
        }
    }

    private static void obtain(Pattern p, BitSet ok, ArrayDeque<Integer> queue) {
        for (int o = 0; o < p.outputCount(); o++)
            if (!ok.get(p.outputItem(o))) {
                ok.set(p.outputItem(o));
                queue.add(p.outputItem(o));
            }
    }

    /**
     * Does the loop grow: is there a mix of its recipes (those whose inputs are members or
     * obtainable) after which no member is down and the members together are up? Exact (a small
     * linear feasibility problem in fractions), so branching loops (crystal ⇄ block ⇄ cut block ⇄
     * smooth block) are judged by what they can do, not by one chosen order. 1 X → 2 X and
     * seed + dirt → 2 seeds grow; raw iron ⇄ raw iron block does not.
     */
    private boolean grows(int[] loop, BitSet ok) {
        Map<Integer, Integer> row = new HashMap<>();
        for (int i = 0; i < loop.length; i++)
            row.put(loop[i], i);
        LinkedHashSet<Integer> usable = new LinkedHashSet<>();
        for (int m : loop)
            for (int q : producers[m]) {
                Pattern p = patterns.get(q);
                boolean all = true;
                for (int s = 0; s < p.inputCount() && all; s++)
                    all = row.containsKey(p.inputItem(s)) || ok.get(p.inputItem(s));
                if (all)
                    usable.add(q);
            }
        if (usable.isEmpty())
            return false;
        int k = loop.length;
        int n = usable.size();
        Rational[][] a = new Rational[k + 1][n];
        Rational[] b = new Rational[k + 1];
        for (Rational[] r : a)
            Arrays.fill(r, Rational.ZERO);
        int j = 0;
        for (int q : usable) {
            Pattern p = patterns.get(q);
            for (int o = 0; o < p.outputCount(); o++) {
                Integer i = row.get(p.outputItem(o));
                if (i != null)
                    a[i][j] = a[i][j].add(Rational.of(p.sureOutputAmount(o)));
            }
            for (int s = 0; s < p.inputCount(); s++) {
                Integer i = row.get(p.inputItem(s));
                if (i == null)
                    continue;
                if (p.inputKind(s) == InputKind.CONSUME || (p.inputKind(s) == InputKind.TOOL && p.inputDurability(s) <= 0))
                    a[i][j] = a[i][j].sub(Rational.of(p.inputAmount(s)));
                else if (p.inputKind(s) == InputKind.TOOL)
                    a[i][j] = a[i][j].sub(Rational.of(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(p.inputDurability(s))));
            }
            for (int i = 0; i < k; i++)
                a[k][j] = a[k][j].add(a[i][j]);
            j++;
        }
        Arrays.fill(b, Rational.ZERO);
        b[k] = Rational.ONE;        // scale-free: any growing mix can be scaled to a total of 1
        return ExactSimplex.feasible(a, b);
    }

    /**
     * Edges of the graph the components are built on: only what carries cost. A consumed or tool
     * input (the slot's first item, the one costs use) leads to the pattern's main products;
     * substitutes, catalysts and containers never change a cost, and a byproduct is no route.
     */
    private int[] successors(int u) {
        LinkedHashSet<Integer> out = new LinkedHashSet<>();
        for (int q : consumers[u]) {
            Pattern p = patterns.get(q);
            if (!costInput(p, u))
                continue;
            for (int i = 0; i < p.outputCount(); i++)
                if (!p.isByproduct(i))
                    out.add(p.outputItem(i));
        }
        // sorted: Tarjan's order (and so which components count as earlier) must not depend on the
        // order patterns were added in
        return out.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private boolean selfLoop(int u) {
        for (int q : consumers[u])
            if (costInput(patterns.get(q), u) && patterns.get(q).producesMain(u))
                return true;
        return false;
    }

    /** Does the cost of {@code p} depend on {@code u}: a consumed or tool slot whose item is {@code u}. */
    private static boolean costInput(Pattern p, int u) {
        for (int i = 0; i < p.inputCount(); i++)
            if (p.inputItem(i) == u && (p.inputKind(i) == InputKind.CONSUME || p.inputKind(i) == InputKind.TOOL))
                return true;
        return false;
    }

    private void processSingle(int x) {
        if (producers[x].length == 0 || mined.get(x)) {
            setRaw(x);      // a mined item is priced by its stock, like a raw (its recipes are conversions)
            level[x] = 0;
            return;
        }
        for (int q : producers[x]) {
            computeRunCost(q);
            computePatBom(q);
        }
        sortProducers(x);
        int p = producers[x][0];
        best[x] = p;
        logCost[x] = unitCost(p, x);
        level[x] = 1 + maxInputLevel(p, x);
        setItemBomFrom(x, p);
    }

    /**
     * Loops among {@code impossible} (edges input → output over every pattern) that no other
     * item of it feeds.
     */
    private List<int[]> sourceLoops(List<Integer> impossible) {
        Set<Integer> set = new HashSet<>(impossible);
        IntFunction<int[]> succ = u -> {
            LinkedHashSet<Integer> out = new LinkedHashSet<>();
            for (int q : consumers[u]) {
                Pattern p = patterns.get(q);
                for (int i = 0; i < p.outputCount(); i++)
                    if (set.contains(p.outputItem(i)))
                        out.add(p.outputItem(i));
            }
            return out.stream().mapToInt(Integer::intValue).toArray();
        };
        int[] nodes = impossible.stream().mapToInt(Integer::intValue).toArray();
        List<int[]> comps = Tarjan.components(nodes, succ);
        Map<Integer, Integer> compOf = new HashMap<>();
        for (int c = 0; c < comps.size(); c++)
            for (int m : comps.get(c))
                compOf.put(m, c);
        boolean[] fed = new boolean[comps.size()];
        for (int u : nodes)
            for (int v : succ.apply(u))
                if (!compOf.get(u).equals(compOf.get(v)))
                    fed[compOf.get(v)] = true;
        List<int[]> out = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) {
            int[] members = comps.get(c);
            boolean loop = members.length > 1 || Arrays.stream(succ.apply(members[0])).anyMatch(v -> v == members[0]);
            if (!fed[c] && loop)
                out.add(members);
        }
        return out;
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private static int[] distinctInputs(Pattern p) {
        LinkedHashSet<Integer> s = new LinkedHashSet<>();
        for (int i = 0; i < p.inputCount(); i++) {
            s.add(p.inputItem(i));
            for (int j = 0; j < p.alternativeCount(i); j++)
                s.add(p.alternative(i, j));
        }
        return s.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] append(int[] a, int v) {
        for (int x : a)
            if (x == v)
                return a;
        int[] r = Arrays.copyOf(a, a.length + 1);
        r[a.length] = v;
        return r;
    }

    private static int[] without(int[] a, int v) {
        int n = 0;
        for (int x : a)
            if (x != v)
                n++;
        if (n == a.length)
            return a;
        int[] r = new int[n];
        int k = 0;
        for (int x : a)
            if (x != v)
                r[k++] = x;
        return r;
    }
}
