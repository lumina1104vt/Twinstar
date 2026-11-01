package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.allocation.RouteCeiling;
import com.lumina.twinstar.algorithm.cache.Scc;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * B — one demand pass. Top level first: an item is handled only after every consumer has added its
 * demand, so a shared intermediate is counted (and taken from stock) once, and the work does not
 * depend on the order amount. An item can be reopened; only the new difference is handled (that is
 * how demand coming back over a back edge is caught).
 * <p>
 * The fields are the working area of one order; C (allocation) reads them, and C1 runs throw-away
 * copies in {@code policyOnly} mode.
 */
public final class DemandPass {
    final Snapshot snap;
    final StockSnapshot stock;
    final Overlay overlay;
    final Deadline dl;
    /** C1 trial runs: always the first route (the policy), no splitting, no repair. */
    final boolean policyOnly;
    /** a leftover trial ({@link #leftoverTrial}): runs none of its own and stops at the soft limit */
    private boolean nested;
    /** leftover trials this pass ran; each is a pass over the whole tree below an item */
    private int leftoverTrials;
    private static final int MAX_LEFTOVER_TRIALS = 2;
    /** each may use this much of the time before the soft limit */
    private static final long LEFTOVER_TRIAL_NANOS = 50_000_000L;
    /** C2 repair results: item → fixed allocation over routes. */
    final Map<Integer, List<Split>> forced;
    /** C1 trial runs: the route the trial starts with for its target. */
    final int forcedFirstItem;
    final int forcedFirstPattern;
    /**
     * The trial's route is for its first demand only. Demand that comes back to the item (a loop
     * through it) follows the normal rules - loop solving and the back-edge forbidden list - as the
     * design has it; forcing it again bypassed that list (white dye + any wool → white wool lists
     * white wool first: each forced run asked for one more white wool, until the safety net).
     */
    private boolean forcedUsed;

    public final AmountArray avail;     // network stock still free in this plan
    public final AmountArray ledger;    // made but not yet used: ceil rounding and extra outputs
    /**
     * Where ledger amounts came from: item → pattern that made it → amount (what {@link #fire} adds;
     * a loop solve's own accounting stays untagged). An item a pattern gives back to its own inputs
     * (the bucket of a water bucket, back from what the water bucket makes) cannot serve the steps
     * that pattern waits for: it is there only after the pattern has run.
     */
    final Map<Integer, AmountMap> lots;
    /**
     * Items used again through a loop, counted over the whole order (an item reopens with small
     * extra demand; the seed is for the whole loop, not per reopening): the seed planned so far, the
     * demand the loop has taken, what it has given back to it, and the patterns that give it back.
     */
    final AmountMap recycleSeed = new AmountMap();
    final AmountMap recycleDemand = new AmountMap();
    final AmountMap recycleReused = new AmountMap();
    final Map<Integer, Set<Integer>> recycleFrom = new HashMap<>();
    /**
     * (item, pattern) → is the item upstream of the pattern (can any recipe turn it into one of its
     * inputs)? Depends on the snapshot only, so C1's trial passes share the order's map.
     */
    private Map<Long, Boolean> upstreamMemo = new HashMap<>();
    public final AmountArray need;
    public final AmountArray done;
    final AmountArray hold;             // catalysts / containers: max over all uses
    final AmountArray substNeed;        // part of need[y] coming from slots that allow substitution
    final Map<Integer, LinkedHashSet<Integer>> substAlternatives = new HashMap<>();
    final BitSet[] ctx;                 // per item: back-edge forbidden list (union of all sources)

    public final AmountMap patternTimes = new AmountMap();
    public final AmountMap pulled = new AmountMap();
    /** part of {@link #pulled} from infinite sources (drawn while the job runs) */
    final AmountMap drawLater = new AmountMap();
    public final AmountMap missing = new AmountMap();
    final AmountMap returned = new AmountMap();     // seeds pulled for loops, given back at the end
    final AmountMap seedHeld = new AmountMap();
    /** routes chosen per item (C2 reads them) */
    public final Map<Integer, List<Split>> chosen = new HashMap<>();
    /** demand an item had when its routes were split (C2 reads it) */
    public final AmountMap splitDemand = new AmountMap();
    final Map<Integer, CycleContract> contracts = new HashMap<>();
    /** the seed rotation chosen per loop (kept when the loop is reopened) */
    final Map<Integer, AmountMap> loopSeeds = new HashMap<>();
    /** per pattern run for outputs that come with a probability: how much of them the plan counts on */
    final Map<Integer, AmountMap> chanceNeed = new HashMap<>();
    /** per pattern with such outputs: runs fired for outputs that always come (they all run) */
    final AmountMap sureRuns = new AmountMap();

    private final PriorityQueue<Integer> queue;
    private final BitSet inQueue = new BitSet();
    private final Map<Long, Boolean> closureMemo = new HashMap<>();
    /** The ordered item: the order itself is always crafted, never taken from storage (as in AE2). */
    private int rootItem = -1;
    private Amount rootLeft = Amount.ZERO;
    private int pops;
    private final int popLimit;

    DemandPass(Snapshot snap, StockSnapshot stock, AmountArray avail, AmountArray ledger, Map<Integer, AmountMap> lots,
               Overlay overlay, Deadline dl, boolean policyOnly, Map<Integer, List<Split>> forced, int forcedFirstItem,
               int forcedFirstPattern) {
        this.snap = snap;
        this.stock = stock;
        this.overlay = overlay;
        this.dl = dl;
        this.policyOnly = policyOnly;
        this.forced = forced;
        this.forcedFirstItem = forcedFirstItem;
        this.forcedFirstPattern = forcedFirstPattern;
        int n = Math.max(snap.itemCount(), stock.size());
        this.avail = avail.size() >= n ? avail : avail.resized(n);
        this.ledger = ledger.size() >= n ? ledger : ledger.resized(n);
        this.lots = lots;
        this.need = new AmountArray(n);
        this.done = new AmountArray(n);
        this.hold = new AmountArray(n);
        this.substNeed = new AmountArray(n);
        this.ctx = new BitSet[n];
        // max-heap by level; same level by id so equal inputs always give equal plans
        this.queue = new PriorityQueue<>(Comparator.<Integer>comparingInt(i -> -snap.level(i)).thenComparingInt(i -> i));
        this.popLimit = 64 * (n + snap.patternCount() + 16);
    }

    /** A fresh pass over the whole order. */
    static DemandPass start(Snapshot snap, StockSnapshot stock, Overlay overlay, Deadline dl, Map<Integer, List<Split>> forced) {
        int n = Math.max(snap.itemCount(), stock.size());
        return new DemandPass(snap, stock, stock.copyStock(n), new AmountArray(n), new HashMap<>(), overlay, dl, false, forced, -1, -1);
    }

    /**
     * C1: a throw-away pass on copies of this pass's stock and ledger, making {@code n} of {@code x}
     * with {@code p} first and the policy below. True if nothing is missing.
     */
    public boolean trial(int x, Amount n, int p) {
        Map<Integer, AmountMap> lotsCopy = new HashMap<>();
        lots.forEach((item, byPattern) -> lotsCopy.put(item, byPattern.copy()));
        DemandPass t = new DemandPass(snap, stock, avail.copy(), ledger.copy(), lotsCopy, overlay, dl, true, Map.of(), x, p);
        t.upstreamMemo = upstreamMemo;
        t.push(x, n, ctx[x]);
        try {
            t.run();
        } catch (TrialStopped e) {
            return false;       // out of time for improving: this route proves nothing
        }
        return t.missing.isEmpty();
    }

    /**
     * C1, what no route's own ceiling covered: the whole order below {@code x} with every route, not
     * only the policy, after the routes already chosen. It sees stock deeper down than the ceilings
     * do (blocks of compressed blocks in storage). Not run inside another leftover trial, and at
     * most {@link #MAX_LEFTOVER_TRIALS} per pass, each within {@link #LEFTOVER_TRIAL_NANOS}.
     */
    public boolean leftoverTrial(List<Split> prior, int x, Amount n, int p) {
        if (nested || policyOnly || leftoverTrials >= MAX_LEFTOVER_TRIALS)
            return false;
        leftoverTrials++;
        DemandPass t = trialPass(prior, x, n, p, dl.within(LEFTOVER_TRIAL_NANOS));
        t.nested = true;
        try {
            t.run();
        } catch (TrialStopped e) {
            return false;
        }
        return t.missing.isEmpty();
    }

    private DemandPass trialPass(List<Split> prior, int x, Amount n, int p, Deadline deadline) {
        Map<Integer, AmountMap> lotsCopy = new HashMap<>();
        lots.forEach((item, byPattern) -> lotsCopy.put(item, byPattern.copy()));
        List<Split> all = new ArrayList<>(prior);
        all.add(whole(p, x, n));
        Amount total = n;
        for (Split sp : prior)
            total = total.add(sp.used());
        DemandPass t = new DemandPass(snap, stock, avail.copy(), ledger.copy(), lotsCopy, overlay, deadline, false,
                Map.of(x, all), -1, -1);
        t.upstreamMemo = upstreamMemo;
        t.push(x, total, ctx[x]);
        return t;
    }

    /**
     * Trials only improve the split, so they stop at the soft limit like every improvement: one
     * trial on a deep tree can outlast it (a real pack: trial runs alone hit the 1 s hard limit).
     */
    private static final class TrialStopped extends RuntimeException {
        static final TrialStopped INSTANCE = new TrialStopped();

        private TrialStopped() {
            super("trial past the soft limit", null, false, false);
        }
    }

    public Snapshot snapshot() {
        return snap;
    }

    public Deadline deadline() {
        return dl;
    }

    public boolean isInfinite(int k) {
        return stock.isInfinite(k);
    }

    /** Free network stock left for {@code k} (infinite sources never run out). */
    public Amount available(int k) {
        if (stock.isInfinite(k))
            return Amount.of(Long.MAX_VALUE).mul(Amount.of(Long.MAX_VALUE));
        return avail.get(k).add(ledger.get(k).clampNonNegative());
    }

    /** The order: the amount asked for is crafted even when storage already has some. */
    void pushOrder(int target, Amount amount) {
        rootItem = target;
        rootLeft = amount;
        push(target, amount, null);
    }

    /** Part of {@code d} that must be crafted, not taken from storage (the order's own amount). */
    private Amount mustMake(int x, Amount d) {
        if (x != rootItem || rootLeft.signum() <= 0)
            return Amount.ZERO;
        Amount m = rootLeft.min(d);
        rootLeft = rootLeft.sub(m);
        return m;
    }

    void push(int y, Amount q, @Nullable BitSet ctx2) {
        if (q.signum() <= 0)
            return;
        need.add(y, q);
        if (ctx2 != null && !ctx2.isEmpty()) {
            if (ctx[y] == null)
                ctx[y] = (BitSet) ctx2.clone();
            else
                ctx[y].or(ctx2);    // the union only gets stricter, still never loops back
        }
        if (!inQueue.get(y)) {
            inQueue.set(y);
            queue.add(y);
        }
    }

    void run() {
        while (!queue.isEmpty()) {
            dl.checkHard();
            if ((policyOnly || nested) && dl.softPassed())
                throw TrialStopped.INSTANCE;
            if (nested && !missing.isEmpty())
                throw TrialStopped.INSTANCE;    // a leftover trial fails at its first shortage
            int x = queue.poll();
            inQueue.clear(x);
            if (++pops > popLimit) {
                // safety net; should never trigger: report what is left as missing instead of hanging
                Amount left = need.get(x).sub(done.get(x));
                if (left.signum() > 0) {
                    done.set(x, need.get(x));
                    missing.add(x, left);
                }
                continue;
            }
            int s = snap.scc(x);
            if (s != -1 && !(policyOnly && x == forcedFirstItem && !forcedUsed)) {
                runMacro(snap.sccAt(s));
                continue;
            }
            Amount d = need.get(x).sub(done.get(x));
            if (d.signum() <= 0)
                continue;
            done.set(x, need.get(x));

            Amount fromStorage = d.sub(mustMake(x, d));
            // what a later pattern gives back serves all but one round: only the seed is still to find
            Amount reused = recycle(x, fromStorage);
            fromStorage = fromStorage.sub(reused);
            d = d.sub(reused);
            Amount took = takeStock(x, fromStorage);
            if (took.compareTo(fromStorage) < 0)
                took = took.add(takeSubstitutes(x, fromStorage.sub(took)));
            d = d.sub(took);
            if (d.signum() > 0)
                d = d.sub(takeLedger(x, d));
            if (d.signum() <= 0)
                continue;

            int[] routes = withoutGivers(x, routes(x));
            if (routes.length == 0) {
                missing.add(x, d);
                continue;
            }
            List<Split> splits = split(x, d, routes);
            chosen.computeIfAbsent(x, k -> new ArrayList<>()).addAll(splits);
            splitDemand.add(x, d);
            for (Split sp : splits)
                fire(sp.pattern(), sp.runs(), x, sp.used());
        }
    }

    private List<Split> split(int x, Amount d, int[] routes) {
        if (policyOnly && x == forcedFirstItem && forcedFirstPattern >= 0 && !forcedUsed) {
            forcedUsed = true;
            return List.of(whole(forcedFirstPattern, x, d));
        }
        List<Split> f = forced.get(x);
        if (f != null)
            return applyForced(f, x, d);
        if (!policyOnly && snap.isMined(x)) {
            // mined (raw iron): stock is taken already; convert what the recipes can cover (take
            // raw iron blocks apart) and the rest is missing as the item itself, not as a block
            List<Split> conv = RouteCeiling.greedySplit(this, x, d, routes, false);
            Amount covered = Amount.ZERO;
            for (Split sp : conv)
                covered = covered.add(sp.used());
            if (covered.compareTo(d) < 0)
                missing.add(x, d.sub(covered));
            return conv;
        }
        if (policyOnly || routes.length == 1)
            return List.of(whole(routes[0], x, d));
        return RouteCeiling.greedySplit(this, x, d, routes);
    }

    Split whole(int p, int x, Amount d) {
        return new Split(p, snap.pattern(p).runsFor(x, d), d);
    }

    /** C2's fixed allocation, cut or topped up to the demand this pass actually has. */
    private List<Split> applyForced(List<Split> f, int x, Amount d) {
        List<Split> out = new ArrayList<>();
        Amount left = d;
        for (Split sp : f) {
            if (left.signum() <= 0)
                break;
            Amount use = sp.used().min(left);
            if (use.signum() <= 0)
                continue;
            out.add(whole(sp.pattern(), x, use));
            left = left.sub(use);
        }
        if (left.signum() > 0) {
            int p = f.isEmpty() ? routes(x)[0] : f.get(0).pattern();
            out = mergeInto(out, p, x, left);
        }
        return out;
    }

    /** Adds {@code extra} demand to route {@code p} (merging with an existing split of p). */
    public List<Split> mergeInto(List<Split> splits, int p, int x, Amount extra) {
        List<Split> out = new ArrayList<>(splits);
        for (int i = 0; i < out.size(); i++)
            if (out.get(i).pattern() == p) {
                out.set(i, whole(p, x, out.get(i).used().add(extra)));
                return out;
            }
        out.add(0, whole(p, x, extra));
        return out;
    }

    private Amount takeStock(int x, Amount d) {
        if (stock.isInfinite(x)) {
            pulled.add(x, d);
            drawLater.add(x, d);
            return d;
        }
        Amount t = avail.get(x).min(d).clampNonNegative();
        if (t.signum() > 0) {
            avail.sub(x, t);
            pulled.add(x, t);
        }
        return t;
    }

    /** Slots that allow substitution: other accepted items, most stocked first. */
    private Amount takeSubstitutes(int x, Amount d) {
        LinkedHashSet<Integer> alts = substAlternatives.get(x);
        Amount allowed = substNeed.get(x).min(d);
        if (alts == null || allowed.signum() <= 0)
            return Amount.ZERO;
        List<Integer> order = new ArrayList<>(alts);
        order.sort(Comparator.<Integer, Amount>comparing(this::available).reversed().thenComparingInt(i -> i));
        Amount taken = Amount.ZERO;
        for (int alt : order) {
            Amount want = allowed.sub(taken);
            if (want.signum() <= 0)
                break;
            taken = taken.add(takeStock(alt, want));
        }
        substNeed.sub(x, taken);
        return taken;
    }

    /**
     * From the ledger: what nobody made for a pattern of this plan first (a loop solve's own), then
     * what a pattern made that cannot come back to its own inputs. An amount that does come back
     * is left to {@link #recycle}.
     */
    private Amount takeLedger(int x, Amount d) {
        AmountMap byPattern = lots.get(x);
        Amount tagged = byPattern == null ? Amount.ZERO : byPattern.total();
        Amount taken = ledger.get(x).sub(tagged).min(d).clampNonNegative();
        if (byPattern != null)
            for (int p : byPattern.keys()) {
                if (taken.compareTo(d) >= 0)
                    break;
                if (upstreamOf(x, p))
                    continue;
                Amount u = byPattern.get(p).min(d.sub(taken));
                byPattern.sub(p, u);
                taken = taken.add(u);
            }
        if (taken.signum() > 0)
            ledger.sub(x, taken);
        return taken;
    }

    /**
     * Demand for x that patterns of this plan give back: x is upstream of them, so what they give
     * back comes only after they have run. One round is planned and the rest is used again while
     * the job runs: over all of the order's demand D for x through the loop, with L given back by
     * patterns run R times (the fewest runs among them, the safe side), seed = max(c, D − L + b)
     * where c = min(D, L) / R and b = L / R (both rounded up). A reopening adds only what the seed
     * grows by. Returns the part of {@code d} the loop serves; the seed is found like any demand.
     */
    private Amount recycle(int x, Amount d) {
        AmountMap byPattern = lots.get(x);
        if (byPattern == null || d.signum() <= 0)
            return Amount.ZERO;
        List<Integer> back = new ArrayList<>();
        Amount given = Amount.ZERO;
        Amount runs = null;
        for (int p : byPattern.keys())
            if (upstreamOf(x, p)) {
                back.add(p);
                given = given.add(byPattern.get(p));
                Amount r = patternTimes.get(p);
                runs = runs == null ? r : runs.min(r);
            }
        if (back.isEmpty() || given.signum() <= 0 || runs.signum() <= 0)
            return Amount.ZERO;
        Amount demand = recycleDemand.get(x).add(d);
        Amount givenTotal = given.add(recycleReused.get(x));     // given back over the whole order
        Amount c = demand.min(givenTotal).ceilDiv(runs);
        Amount b = givenTotal.ceilDiv(runs);
        Amount seedTotal = c.max(demand.sub(givenTotal).add(b)).min(demand);
        Amount seed = seedTotal.sub(recycleSeed.get(x)).clampNonNegative().min(d);
        Amount reused = d.sub(seed).min(given);
        seed = d.sub(reused);
        recycleDemand.add(x, d);
        if (reused.signum() <= 0)
            return Amount.ZERO;
        Amount left = reused;
        for (int p : back) {
            Amount u = byPattern.get(p).min(left);
            byPattern.sub(p, u);
            left = left.sub(u);
        }
        ledger.sub(x, reused);
        recycleReused.add(x, reused);
        recycleSeed.add(x, seed);
        recycleFrom.computeIfAbsent(x, k -> new HashSet<>()).addAll(back);
        return reused;
    }

    /**
     * A pattern that gives x back to its own inputs cannot make more x for them either: that x would
     * wait for the pattern too (the seed of a loop is made outside it, as for policy cycles).
     */
    private int[] withoutGivers(int x, int[] routes) {
        AmountMap byPattern = lots.get(x);
        Set<Integer> givers = recycleFrom.get(x);
        if ((byPattern == null || byPattern.isEmpty()) && givers == null)
            return routes;
        int kept = 0;
        int[] out = new int[routes.length];
        for (int q : routes) {
            boolean gives = (givers != null && givers.contains(q))
                    || (byPattern != null && byPattern.get(q).signum() > 0 && upstreamOf(x, q));
            if (!gives)
                out[kept++] = q;
        }
        return kept == routes.length ? routes : Arrays.copyOf(out, kept);
    }

    /** Can x, through any recipe, become an input of pattern p (then p's x waits for p)? */
    private boolean upstreamOf(int x, int p) {
        long key = ((long) x << 32) | (p & 0xffffffffL);
        Boolean memo = upstreamMemo.get(key);
        if (memo != null)
            return memo;
        Pattern target = snap.pattern(p);
        BitSet inputs = new BitSet();
        for (int s = 0; s < target.inputCount(); s++)
            inputs.set(target.inputItem(s));
        BitSet seen = new BitSet();
        ArrayDeque<Integer> todo = new ArrayDeque<>();
        todo.add(x);
        seen.set(x);
        boolean hit = false;
        while (!todo.isEmpty() && !hit) {
            int u = todo.poll();
            if (inputs.get(u)) {
                hit = true;
                break;
            }
            for (int q : snap.consumers(u)) {
                Pattern pat = snap.pattern(q);
                if (pat == null || !pat.hasInput(u))
                    continue;   // a substitute only: taken from storage, never made into something
                for (int o = 0; o < pat.outputCount(); o++)
                    if (!pat.isByproduct(o) && !seen.get(pat.outputItem(o))) {
                        seen.set(pat.outputItem(o));
                        todo.add(pat.outputItem(o));
                    }
            }
        }
        upstreamMemo.put(key, hit);
        return hit;
    }

    /**
     * Patterns for x in route order (with this order's overlay applied). With a forbidden list,
     * only routes that never touch it: first those whose whole policy subtree is clear (the "safe"
     * patterns of the design), falling back to a direct check of their inputs.
     */
    int[] routes(int x) {
        return routes(x, ctx[x]);
    }

    /** The producers of {@code x} this order may use (not {@link Overlay#disabled}). */
    public int[] usableProducers(int x) {
        int[] all = snap.producers(x);
        if (overlay.disabled.isEmpty())
            return all;
        return java.util.Arrays.stream(all).filter(q -> !overlay.disabled.get(q)).toArray();
    }

    private int[] routes(int x, @Nullable BitSet forbid) {
        int[] base = usableProducers(x);
        if (base.length == 0)
            return base;
        int[] routes = overlay.isEmpty() ? base : sortWithOverlay(x, base);
        if (forbid == null || forbid.isEmpty())
            return routes;
        List<Integer> direct = new ArrayList<>();
        List<Integer> safe = new ArrayList<>();
        for (int q : routes) {
            Pattern p = snap.pattern(q);
            boolean ok = true;
            for (int s = 0; s < p.inputCount() && ok; s++)
                if (forbid.get(p.inputItem(s)))
                    ok = false;
            if (!ok)
                continue;
            direct.add(q);
            if (!touches(q, forbid))
                safe.add(q);
        }
        List<Integer> use = safe.isEmpty() ? direct : safe;
        return use.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Does the policy subtree under pattern q reach any forbidden item? */
    private boolean touches(int q, BitSet forbid) {
        long key = ((long) q << 32) | forbid.hashCode();
        Boolean memo = closureMemo.get(key);
        if (memo != null)
            return memo;
        ArrayList<Integer> stack = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        Pattern root = snap.pattern(q);
        for (int s = 0; s < root.inputCount(); s++)
            stack.add(root.inputItem(s));
        boolean hit = false;
        while (!stack.isEmpty() && !hit) {
            int y = stack.remove(stack.size() - 1);
            if (!seen.add(y))
                continue;
            if (forbid.get(y)) {
                hit = true;
                break;
            }
            int b = snap.best(y);
            if (b < 0)
                continue;
            Pattern p = snap.pattern(b);
            for (int s = 0; s < p.inputCount(); s++)
                stack.add(p.inputItem(s));
        }
        closureMemo.put(key, hit);
        return hit;
    }

    /** Route key with this order's prices: cost = BOM × overlay price; "保留" materials sort after the rest. */
    private int[] sortWithOverlay(int x, int[] base) {
        Integer[] boxed = Arrays.stream(base).boxed().toArray(Integer[]::new);
        Map<Integer, Double> cost = new HashMap<>();
        Map<Integer, Integer> reserved = new HashMap<>();
        for (int q : base) {
            cost.put(q, overlayUnitCost(q, x));
            reserved.put(q, usesReserved(q) ? 1 : 0);
        }
        Arrays.sort(boxed, (a, b) -> {
            double ca = cost.get(a);
            double cb = cost.get(b);
            boolean fa = ca != LogMath.INF;
            boolean fb = cb != LogMath.INF;
            if (fa != fb)
                return fa ? -1 : 1;
            Pattern pa = snap.pattern(a);
            Pattern pb = snap.pattern(b);
            if (pa.priority != pb.priority)
                return pa.priority > pb.priority ? -1 : 1;
            int c = Integer.compare(reserved.get(a), reserved.get(b));
            if (c != 0)
                return c;
            c = Double.compare(ca, cb);
            if (c != 0)
                return c;
            if (a == snap.best(x) || b == snap.best(x))
                return a == snap.best(x) ? -1 : 1;     // ties keep the policy first (as the theory layer does)
            c = Integer.compare(pa.where.ordinal(), pb.where.ordinal());
            return c != 0 ? c : Integer.compare(a, b);
        });
        return Arrays.stream(boxed).mapToInt(Integer::intValue).toArray();
    }

    /** log10 cost of one x through q at this order's prices (raws from the BOM). */
    public double overlayUnitCost(int q, int x) {
        if (snap.unitLogCost(q, x) == LogMath.INF)
            return LogMath.INF;
        int[] ks = snap.patBomItems(q);
        double[] vs = snap.patBomLog(q);
        double sum = LogMath.ZERO;
        for (int i = 0; i < ks.length; i++) {
            Double o = overlay.logPrice.get(ks[i]);
            double price = o != null ? o : snap.logRawPrice(ks[i]);
            sum = LogMath.add(sum, LogMath.mul(vs[i], price));
        }
        if (sum == LogMath.ZERO || sum == LogMath.INF)
            return sum;
        return sum - snap.pattern(q).mainOutputLog10(x);
    }

    private boolean usesReserved(int q) {
        if (overlay.reserved.isEmpty())
            return false;
        Pattern p = snap.pattern(q);
        for (int s = 0; s < p.inputCount(); s++)
            if (overlay.reserved.get(p.inputItem(s)))
                return true;
        for (int k : snap.patBomItems(q))
            if (overlay.reserved.get(k))
                return true;
        return false;
    }

    /** Runs pattern p {@code runs} times for x; {@code used} of the outputs serves x, the rest goes to the ledger. */
    void fire(int p, Amount runs, int x, Amount used) {
        fire(p, runs, x, used, ctx[x]);
    }

    private void fire(int p, Amount runs, int x, Amount used, @Nullable BitSet cx) {
        Pattern pat = snap.pattern(p);
        patternTimes.add(p, runs);
        if (pat.chanceOf(x) < 1) {
            // run for an output that comes with a probability: the runs cover `used` of it and the
            // job may stop once it has that, so nothing else of the runs is counted on
            chanceNeed.computeIfAbsent(p, k -> new AmountMap()).add(x, used);
            ledger.add(x, used);
            lots.computeIfAbsent(x, k -> new AmountMap()).add(p, used);
        } else {
            if (pat.hasChanceOutput())
                sureRuns.add(p, runs);
            for (int o = 0; o < pat.outputCount(); o++) {
                if (pat.isChance(o))
                    continue;   // comes with a probability: nothing counted on
                Amount made = pat.outputAmount(o).mul(runs);
                ledger.add(pat.outputItem(o), made);
                lots.computeIfAbsent(pat.outputItem(o), k -> new AmountMap()).add(p, made);
            }
        }
        ledger.sub(x, used);
        lots.get(x).sub(p, used);
        for (int s = 0; s < pat.inputCount(); s++) {
            int y = pat.inputItem(s);
            Amount q = inputDemand(pat, s, runs, y);
            if (q.signum() <= 0)
                continue;
            if (pat.inputKind(s) == InputKind.CONSUME && pat.allowSubst && pat.alternativeCount(s) > 0) {
                substNeed.add(y, q);
                LinkedHashSet<Integer> alts = substAlternatives.computeIfAbsent(y, k -> new LinkedHashSet<>());
                for (int j = 0; j < pat.alternativeCount(s); j++)
                    alts.add(pat.alternative(s, j));
            }
            BitSet ctx2 = cx;
            if (snap.level(y) >= snap.level(x)) {
                // back edge: the forbidden list grows downwards
                ctx2 = cx == null ? new BitSet() : (BitSet) cx.clone();
                ctx2.set(x);
            }
            push(y, q, ctx2);
        }
    }

    /** Demand one slot adds. Catalysts and containers are holdings: one copy, max over all uses. */
    private Amount inputDemand(Pattern pat, int s, Amount runs, int y) {
        switch (pat.inputKind(s)) {
            case CONSUME:
                return pat.inputAmount(s).mul(runs);
            case TOOL:
                return pat.inputDurability(s) > 0 ? runs.ceilDiv(pat.inputDurability(s)) : pat.inputAmount(s).mul(runs);
            default:
                Amount want = pat.inputAmount(s);
                Amount had = hold.get(y);
                if (want.compareTo(had) <= 0)
                    return Amount.ZERO;
                hold.set(y, want);
                return want.sub(had);
        }
    }

    // ───────────────────────────── D: policy cycles ─────────────────────────────

    /**
     * The whole cycle as one node: gather every member's demand, solve the runs, push the external
     * inputs, hold the seeds, and check the loop can really be run.
     */
    private void runMacro(Scc s) {
        int k = s.memberCount();
        Amount[] demand = new Amount[k];
        Amount[] fromStock = new Amount[k];
        boolean any = false;
        BitSet cx = null;
        for (int i = 0; i < k; i++) {
            int m = s.member(i);
            Amount d = need.get(m).sub(done.get(m));
            demand[i] = Amount.ZERO;
            fromStock[i] = Amount.ZERO;
            if (d.signum() <= 0)
                continue;
            done.set(m, need.get(m));
            fromStock[i] = takeStock(m, d.sub(mustMake(m, d)));
            d = d.sub(fromStock[i]);
            if (d.signum() > 0)
                d = d.sub(takeLedger(m, d));
            demand[i] = d.clampNonNegative();
            if (demand[i].signum() > 0)
                any = true;
            if (ctx[m] != null) {
                if (cx == null)
                    cx = new BitSet();
                cx.or(ctx[m]);
            }
        }
        // a member the stock of another member can make without a round of the loop (crush the
        // purified crystals in stock for dust): that part of the loop only, no seed needed
        for (int i = 0; i < k; i++)
            if (demand[i].signum() > 0 && fromUpstream(s, i, demand[i]))
                demand[i] = Amount.ZERO;
        any = false;
        for (int i = 0; i < k; i++)
            any |= demand[i].signum() > 0;
        if (!any)
            return;

        // seeds are a holding: independent of the amount, given back at the end. The loop can start
        // at any of its patterns; the rotation the stock covers best is taken
        AmountMap pick = pickSeeds(s, fromStock);
        AmountMap seeds = new AmountMap();
        AmountMap shortBy = new AmountMap();
        for (int y : pick.keys()) {
            Amount want = pick.get(y);
            Amount have = seedHeld.get(y);
            if (want.compareTo(have) > 0) {
                Amount delta = want.sub(have);
                Amount got = takeStock(y, delta);
                if (got.compareTo(delta) < 0)
                    got = got.add(takeLedger(y, delta.sub(got)));
                // a member taken from stock for the demand serves better as a seed: the loop makes its replacement
                int mi = s.memberIndex(y);
                if (got.compareTo(delta) < 0 && mi >= 0 && fromStock[mi].signum() > 0) {
                    Amount reclaim = fromStock[mi].min(delta.sub(got));
                    fromStock[mi] = fromStock[mi].sub(reclaim);
                    demand[mi] = demand[mi].add(reclaim);
                    got = got.add(reclaim);
                }
                if (got.compareTo(delta) < 0)
                    got = got.add(craftSeed(s, y, delta.sub(got)));
                seedHeld.add(y, got);
                returned.add(y, got);
                if (got.compareTo(delta) < 0)
                    shortBy.add(y, delta.sub(got));
            }
            seeds.set(y, seedHeld.get(y));
        }

        Amount[] runs = CycleSolver.runs(s, demand);
        if (runs == null) {
            for (int i = 0; i < k; i++)
                missing.add(s.member(i), demand[i]);
            return;
        }
        Set<Integer> members = new HashSet<>();
        for (int i = 0; i < k; i++)
            members.add(s.member(i));
        int anchor = s.member(0);
        for (int j = 0; j < s.patternCount(); j++) {
            if (runs[j].signum() <= 0)
                continue;
            int p = s.pattern(j);
            Pattern pat = snap.pattern(p);
            patternTimes.add(p, runs[j]);
            for (int o = 0; o < pat.outputCount(); o++)
                ledger.add(pat.outputItem(o), pat.sureOutputAmount(o).mul(runs[j]));
            for (int slot = 0; slot < pat.inputCount(); slot++) {
                int y = pat.inputItem(slot);
                if (members.contains(y)) {
                    // used inside the loop: the solve already counted it
                    if (pat.inputKind(slot) == InputKind.CONSUME)
                        ledger.sub(y, pat.inputAmount(slot).mul(runs[j]));
                    else if (pat.inputKind(slot) == InputKind.TOOL)
                        ledger.sub(y, pat.inputDurability(slot) > 0 ? runs[j].ceilDiv(pat.inputDurability(slot))
                                : pat.inputAmount(slot).mul(runs[j]));
                    continue;
                }
                Amount q = inputDemand(pat, slot, runs[j], y);
                BitSet ctx2 = cx;
                if (snap.level(y) >= snap.level(anchor)) {
                    ctx2 = cx == null ? new BitSet() : (BitSet) cx.clone();
                    for (int m : members)
                        ctx2.set(m);
                }
                push(y, q, ctx2);
            }
        }
        for (int i = 0; i < k; i++)
            ledger.sub(s.member(i), demand[i]);

        if (policyOnly && shortBy.isEmpty())
            return;
        CycleSolver.Check check = CycleSolver.verify(snap, s, runs, seeds, demand, dl);
        if (check instanceof CycleSolver.Check.Contract c)
            contracts.put(s.index, c.contract());
        else if (check instanceof CycleSolver.Check.Impossible)
            shortBy.forEach(missing::add);
    }

    /** One pattern run on an open path through a loop: {@code runs} of {@code pattern}, {@code used} of it for {@code item}. */
    private record PathStep(int pattern, Amount runs, int item, Amount used) {
    }

    /**
     * Serves {@code d} of member {@code mi} by its own loop pattern from what the stock has of the
     * members before it, walking back along the loop (dust ← purified crystal ← seed) but never
     * round it. All or nothing: planned only when the stock covers the whole path; then true.
     */
    private boolean fromUpstream(Scc s, int mi, Amount d) {
        List<PathStep> steps = new ArrayList<>();
        AmountMap take = new AmountMap();
        if (!pathFeasible(s, mi, d, true, new boolean[s.memberCount()], take, steps))
            return false;
        for (int y : take.keys())
            takeStock(y, take.get(y));
        BitSet cx = ctx[s.member(mi)];
        for (PathStep st : steps) {
            Pattern pat = snap.pattern(st.pattern());
            patternTimes.add(st.pattern(), st.runs());
            for (int o = 0; o < pat.outputCount(); o++) {
                Amount made = pat.sureOutputAmount(o).mul(st.runs());
                ledger.add(pat.outputItem(o), made);
                lots.computeIfAbsent(pat.outputItem(o), key -> new AmountMap()).add(st.pattern(), made);
            }
            ledger.sub(st.item(), st.used());
            lots.get(st.item()).sub(st.pattern(), st.used());
            for (int slot = 0; slot < pat.inputCount(); slot++) {
                int y = pat.inputItem(slot);
                if (s.memberIndex(y) >= 0)
                    continue;       // a member before it: from the stock or the step after this one
                Amount q = inputDemand(pat, slot, st.runs(), y);
                if (q.signum() > 0)
                    push(y, q, cx);
            }
        }
        return true;
    }

    /** Can {@code n} of member {@code mi} come from the stock (except the first) or from members before it? */
    private boolean pathFeasible(Scc s, int mi, Amount n, boolean first, boolean[] visited, AmountMap take, List<PathStep> steps) {
        int m = s.member(mi);
        if (!first) {
            Amount have = stock.isInfinite(m) ? n : avail.get(m).sub(take.get(m)).clampNonNegative();
            Amount t = have.min(n);
            if (t.signum() > 0)
                take.add(m, t);
            n = n.sub(t);
            if (n.signum() <= 0)
                return true;
        }
        if (visited[mi])
            return false;       // round the loop: that is the loop's own work
        visited[mi] = true;
        int q = s.policy(mi);
        Pattern pat = snap.pattern(q);
        Amount out = pat == null ? Amount.ZERO : pat.sureOutputAmountOf(m);
        if (out.signum() <= 0)
            return false;
        Amount runs = n.ceilDiv(out);
        steps.add(new PathStep(q, runs, m, n));
        for (int slot = 0; slot < pat.inputCount(); slot++) {
            int yi = s.memberIndex(pat.inputItem(slot));
            if (yi < 0)
                continue;
            if (pat.inputKind(slot) != InputKind.CONSUME)
                return false;
            if (!pathFeasible(s, yi, pat.inputAmount(slot).mul(runs), false, visited, take, steps))
                return false;
        }
        return true;
    }

    /** The seed rotation with the smallest shortfall against what this plan can still take; kept once chosen. */
    private AmountMap pickSeeds(Scc s, Amount[] fromStock) {
        AmountMap chosenSeeds = loopSeeds.get(s.index);
        if (chosenSeeds != null)
            return chosenSeeds;
        AmountMap best = null;
        Amount bestShort = null;
        for (int o = 0; o < s.seedOptionCount(); o++) {
            AmountMap opt = s.seedOption(o);
            Amount shortBy = Amount.ZERO;
            for (int y : opt.keys()) {
                Amount have = seedHeld.get(y).add(available(y));
                int mi = s.memberIndex(y);
                if (mi >= 0)
                    have = have.add(fromStock[mi]);
                shortBy = shortBy.add(opt.get(y).sub(have).clampNonNegative());
            }
            if (bestShort == null || shortBy.compareTo(bestShort) < 0) {
                best = opt;
                bestShort = shortBy;
            }
        }
        loopSeeds.put(s.index, best);
        return best;
    }

    /**
     * A loop cannot start itself: a seed the stock does not have is made by a route outside the
     * loop (a pattern that takes no loop member), and what comes out is held as the seed. Returns
     * the amount planned (0 when there is no such route; then it stays a shortage).
     */
    private Amount craftSeed(Scc s, int y, Amount d) {
        BitSet forbid = ctx[y] == null ? new BitSet() : (BitSet) ctx[y].clone();
        for (int i = 0; i < s.memberCount(); i++)
            forbid.set(s.member(i));
        int[] outside = routes(y, forbid);
        if (outside.length == 0)
            return Amount.ZERO;
        Split sp = whole(outside[0], y, d);
        fire(sp.pattern(), sp.runs(), y, d, forbid);
        return d;
    }

    // ───────────────────────────── result ─────────────────────────────

    Plan toPlan(int target, Amount amount) {
        AmountMap leftover = new AmountMap();
        for (int i = 0; i < ledger.size(); i++)
            if (ledger.signum(i) > 0)
                leftover.add(i, ledger.get(i));
        for (int i = 0; i < hold.size(); i++)
            if (hold.signum(i) > 0)
                leftover.add(i, hold.get(i));
        returned.forEach(leftover::add);
        // black-hole capacity as AE2 counts a CPU's bytes: every amount the plan asks for (from
        // stock or made), one byte per run, eight per node (item asked for or pattern used)
        Amount usage = Amount.ZERO;
        int nodes = patternTimes.size();
        for (int i = 0; i < need.size(); i++)
            if (need.signum(i) > 0) {
                usage = usage.add(snap.units().bytes(i, need.get(i)));
                nodes++;
            }
        for (var e : patternTimes.entries())
            usage = usage.add(e.getValue());
        usage = usage.add(Amount.of(8L * nodes));
        Map<Integer, Plan.ChanceNeed> chances = new HashMap<>();
        chanceNeed.forEach((p, items) -> chances.put(p, new Plan.ChanceNeed(items.copy(), sureRuns.get(p))));
        return new Plan(snap.version(), target, amount, patternTimes.copy(), pulled.copy(), drawLater.copy(), missing.copy(),
                leftover, usage, contracts, loopSeeds, recycles(), chances);
    }

    /**
     * The loops of {@link #recycle}: per item, the seed and the patterns of this plan on the way
     * from the item back to the patterns that give it back (they may use the seed while it is held).
     */
    private List<Plan.Recycle> recycles() {
        if (recycleSeed.isEmpty())
            return List.of();
        Map<Integer, List<Integer>> usedBy = new HashMap<>();
        Map<Integer, List<Integer>> madeBy = new HashMap<>();
        for (int q : patternTimes.keys()) {
            Pattern pat = snap.pattern(q);
            for (int s = 0; s < pat.inputCount(); s++)
                usedBy.computeIfAbsent(pat.inputItem(s), k -> new ArrayList<>()).add(q);
            for (int o = 0; o < pat.outputCount(); o++)
                if (!pat.isByproduct(o))
                    madeBy.computeIfAbsent(pat.outputItem(o), k -> new ArrayList<>()).add(q);
        }
        List<Plan.Recycle> out = new ArrayList<>();
        for (int x : recycleSeed.keys()) {
            // downstream of x in this plan ...
            Set<Integer> down = new HashSet<>();
            ArrayDeque<Integer> items = new ArrayDeque<>(List.of(x));
            BitSet seenItems = new BitSet();
            seenItems.set(x);
            while (!items.isEmpty())
                for (int q : usedBy.getOrDefault(items.poll(), List.of()))
                    if (down.add(q)) {
                        Pattern pat = snap.pattern(q);
                        for (int o = 0; o < pat.outputCount(); o++)
                            if (!pat.isByproduct(o) && !seenItems.get(pat.outputItem(o))) {
                                seenItems.set(pat.outputItem(o));
                                items.add(pat.outputItem(o));
                            }
                    }
            // ... and upstream of the patterns that give it back
            Set<Integer> givers = recycleFrom.get(x);
            Set<Integer> up = new HashSet<>(givers);
            ArrayDeque<Integer> todo = new ArrayDeque<>(givers);
            while (!todo.isEmpty()) {
                Pattern pat = snap.pattern(todo.poll());
                for (int s = 0; s < pat.inputCount(); s++)
                    for (int q : madeBy.getOrDefault(pat.inputItem(s), List.of()))
                        if (up.add(q))
                            todo.add(q);
            }
            Set<Integer> loop = new java.util.TreeSet<>(givers);
            for (int q : down)
                if (up.contains(q))
                    loop.add(q);
            out.add(new Plan.Recycle(x, recycleSeed.get(x), loop.stream().mapToInt(Integer::intValue).toArray()));
        }
        return out;
    }
}
