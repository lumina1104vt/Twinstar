package com.lumina.twinstar.algorithm.allocation;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import com.lumina.twinstar.algorithm.planning.DemandPass;
import com.lumina.twinstar.algorithm.planning.Split;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * C2 — a shortage that switching routes might fix. Works on this order's own price layer: start
 * prices from how scarce each raw was in the greedy result, then hand out small integer steps to
 * the cheapest route, raising the price of what it used and setting used-up raws to ∞. Prices only
 * rise, so a cost computed earlier is a lower bound: a lazily recomputed heap skips routes that
 * cannot win. Always integer and makeable, not guaranteed cheapest.
 * <p>
 * Research (10-17) found this still reports a false shortage in 11–15 % of deliberately tight cases;
 * the LP-guided replacement is not decided yet.
 */
public final class Repair {
    /** Steps per item: each step hands out about 1/K of what is left. */
    public static final int K = 100;
    /**
     * Price growth per use: × (1 + ε · used / stock), so using up a whole stock multiplies the price
     * by about e^ε. Raw price tiers are ×10 apart, so ε must clearly exceed ln 10 or a cheaper tier
     * is never given up (with ε = 1 the design's own P1/P2 example still comes out short). 【待定】
     */
    public static final double GROWTH_EPS = 10.0;
    /** Lower bound of the scarcity factor for the start price. 【待定】 */
    public static final double SCARCITY_FLOOR = 0.01;

    private Repair() {
    }

    /**
     * Worth trying: some item that (through its BOM, so ancestors count too) uses a missing raw k has
     * another route that uses less k and whose other raws still had stock left after the greedy pass.
     */
    public static boolean repairable(DemandPass pass) {
        Snapshot snap = pass.snapshot();
        for (int k : pass.missing.keys()) {
            for (Map.Entry<Integer, List<Split>> e : pass.chosen.entrySet()) {
                int x = e.getKey();
                for (Split sp : e.getValue()) {
                    double useK = snap.patBomUnitLog(sp.pattern(), x, k);
                    if (useK == LogMath.ZERO)
                        continue;
                    for (int p2 : pass.usableProducers(x)) {
                        if (p2 == sp.pattern())
                            continue;
                        if (snap.patBomUnitLog(p2, x, k) >= useK)
                            continue;           // ① must use less k per item
                        if (othersLeft(pass, p2, k))
                            return true;        // ② its other raws are not used up
                    }
                }
            }
        }
        return false;
    }

    private static boolean othersLeft(DemandPass pass, int p, int k) {
        for (int j : pass.snapshot().patBomItems(p))
            if (j != k && pass.available(j).signum() <= 0)
                return false;
        return true;
    }

    /**
     * New fixed allocations for the items whose routes use a missing raw; B is then run again with
     * them (which also handles the rounding). Null when there is nothing to change.
     */
    public static @Nullable Map<Integer, List<Split>> repair(DemandPass pass, StockSnapshot stock, Deadline dl) {
        Snapshot snap = pass.snapshot();
        TreeSet<Integer> missingRaws = new TreeSet<>();
        for (int k : pass.missing.keys())
            missingRaws.add(k);

        // the region: items with more than one route whose chosen routes use a missing raw
        List<Integer> region = new ArrayList<>();
        for (Map.Entry<Integer, List<Split>> e : new TreeMap<>(pass.chosen).entrySet()) {
            int x = e.getKey();
            if (pass.usableProducers(x).length < 2)
                continue;
            boolean uses = false;
            for (Split sp : e.getValue())
                for (int k : snap.patBomItems(sp.pattern()))
                    if (missingRaws.contains(k))
                        uses = true;
            if (uses)
                region.add(x);
        }
        if (region.isEmpty())
            return null;

        // raws left for the region = stock − what the rest of the plan took (real numbers: estimates)
        Map<Integer, Double> regionUse = new HashMap<>();
        for (int x : region)
            for (Split sp : pass.chosen.get(x))
                addUse(regionUse, snap, sp.pattern(), x, sp.used());
        Map<Integer, Double> left = new HashMap<>();
        Map<Integer, Double> stockOf = new HashMap<>();
        Map<Integer, Double> price = new HashMap<>();
        for (int x : region)
            for (int p : pass.usableProducers(x))
                for (int k : snap.patBomItems(p)) {
                    if (left.containsKey(k))
                        continue;
                    double st = stock.isInfinite(k) ? Double.POSITIVE_INFINITY : real(stock.stock(k));
                    double pulled = real(pass.pulled.get(k));
                    double l = Math.max(0, st - Math.max(0, pulled - regionUse.getOrDefault(k, 0.0)));
                    left.put(k, l);
                    stockOf.put(k, st);
                    // ① start price from scarcity, once (up or down)
                    double demand = pulled + real(pass.missing.get(k));
                    double base = snap.logRawPrice(k);
                    if (stock.isInfinite(k))
                        price.put(k, LogMath.ZERO);
                    else if (st <= 0)
                        price.put(k, LogMath.INF);
                    else
                        price.put(k, base + Math.log10(Math.max(demand / st, SCARCITY_FLOOR)));
                }

        Map<Integer, Amount> remaining = new LinkedHashMap<>();
        for (int x : region)
            remaining.put(x, pass.splitDemand.get(x));
        Map<Integer, Map<Integer, Amount>> alloc = new LinkedHashMap<>();

        record Cand(double cost, int x, int p) {
        }
        PriorityQueue<Cand> heap = new PriorityQueue<>((a, b) -> a.cost != b.cost ? Double.compare(a.cost, b.cost)
                : a.x != b.x ? Integer.compare(a.x, b.x) : Integer.compare(a.p, b.p));
        for (int x : region)
            for (int p : pass.usableProducers(x))
                if (snap.unitLogCost(p, x) != LogMath.INF)
                    heap.add(new Cand(routeCost(snap, p, x, price), x, p));

        while (!heap.isEmpty() && !dl.softPassed()) {
            // ② lazy recompute: prices only rise, so if the recomputed top is still the smallest it is the cheapest
            Cand top;
            double c;
            while (true) {
                top = heap.poll();
                c = routeCost(snap, top.p, top.x, price);
                Cand next = heap.peek();
                if (next == null || c < next.cost || (c == next.cost && (top.x < next.x || (top.x == next.x && top.p <= next.p))))
                    break;
                heap.add(new Cand(c, top.x, top.p));
            }
            if (c == LogMath.INF)
                break;      // everything left needs a used-up raw: a real shortage
            Amount rem = remaining.get(top.x);
            if (rem.signum() <= 0)
                continue;
            Amount canMake = canMake(snap, top.p, top.x, left);
            if (canMake.signum() <= 0) {
                // ③ used up: price ∞ at once
                for (int k : snap.patBomItems(top.p))
                    if (left.get(k) < Math.pow(10, snap.patBomUnitLog(top.p, top.x, k)))
                        price.put(k, LogMath.INF);
                heap.add(new Cand(routeCost(snap, top.p, top.x, price), top.x, top.p));
                continue;
            }
            Amount step = rem.ceilDiv(K).max(Amount.ONE).min(canMake).min(rem);
            alloc.computeIfAbsent(top.x, k -> new LinkedHashMap<>()).merge(top.p, step, Amount::add);
            double stepReal = real(step);
            for (int k : snap.patBomItems(top.p)) {
                double use = Math.pow(10, snap.patBomUnitLog(top.p, top.x, k)) * stepReal;
                left.put(k, Math.max(0, left.get(k) - use));
                double st = stockOf.get(k);
                if (price.get(k) != LogMath.ZERO && st > 0 && Double.isFinite(st))
                    price.put(k, price.get(k) + Math.log10(1 + GROWTH_EPS * use / st));   // only rises
            }
            remaining.put(top.x, rem.sub(step));
            if (rem.sub(step).signum() > 0)
                heap.add(new Cand(routeCost(snap, top.p, top.x, price), top.x, top.p));
        }

        Map<Integer, List<Split>> forced = new HashMap<>();
        for (int x : region) {
            List<Split> list = new ArrayList<>();
            Map<Integer, Amount> a = alloc.getOrDefault(x, Map.of());
            for (Map.Entry<Integer, Amount> e : a.entrySet())
                list.add(pass.mergeInto(List.of(), e.getKey(), x, e.getValue()).get(0));
            Amount rem = remaining.get(x);
            int[] usable = pass.usableProducers(x);
            if (rem.signum() > 0 && usable.length > 0)
                list = pass.mergeInto(list, usable[0], x, rem);
            forced.put(x, list);
        }
        return forced;
    }

    private static void addUse(Map<Integer, Double> use, Snapshot snap, int p, int x, Amount amount) {
        double a = real(amount);
        for (int k : snap.patBomItems(p))
            use.merge(k, Math.pow(10, snap.patBomUnitLog(p, x, k)) * a, Double::sum);
    }

    /** Route cost at this order's prices: BOM × price (no incremental propagation). */
    private static double routeCost(Snapshot snap, int p, int x, Map<Integer, Double> price) {
        double sum = LogMath.ZERO;
        for (int k : snap.patBomItems(p))
            sum = LogMath.add(sum, LogMath.mul(snap.patBomUnitLog(p, x, k), price.getOrDefault(k, snap.logRawPrice(k))));
        return sum;
    }

    private static Amount canMake(Snapshot snap, int p, int x, Map<Integer, Double> left) {
        double best = Double.POSITIVE_INFINITY;
        for (int k : snap.patBomItems(p)) {
            double l = left.get(k);
            double perLog = snap.patBomUnitLog(p, x, k);
            if (perLog == LogMath.ZERO)
                continue;
            best = Math.min(best, l <= 0 ? LogMath.ZERO : Math.log10(l) - perLog);
        }
        if (best == Double.POSITIVE_INFINITY)
            return Amount.of(Long.MAX_VALUE);
        return Amount.floorOfLog10(best + 1e-12);
    }

    private static double real(Amount a) {
        return a.signum() <= 0 ? 0 : Math.pow(10, a.log10());
    }
}
