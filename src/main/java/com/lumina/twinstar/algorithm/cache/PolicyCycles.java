package com.lumina.twinstar.algorithm.cache;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.algorithm.model.SccKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The theory layer's policy cycles: a component of the cost graph that is a loop (two or more
 * items that make each other, or one that makes itself) is solved here, from scratch, by policy
 * iteration: cost, best route, level and BOM of every member, and the {@link Scc}s of the final
 * policy. Works on the theory layer's own arrays and its single-item helpers (cost of a route, BOM
 * of a pattern); one instance per component computed.
 */
final class PolicyCycles {
    private static final int MAX_POLICY_ITERATIONS = 64;
    private static final int[] EMPTY = new int[0];

    private final TheoryLayer layer;

    PolicyCycles(TheoryLayer layer) {
        this.layer = layer;
    }

    /**
     * A strongly connected part of the full graph. Old values inside it cannot be trusted (two
     * convertible items would prop each other up), so start from ∞ and iterate policies: evaluate the
     * current choice exactly (cycles solved as linear equations), then switch any member whose route
     * is strictly better. Ties never switch. Mined members stay raws throughout.
     */
    void policyIteration(int[] comp) {
        Set<Integer> in = new HashSet<>();
        for (int m : comp) {
            in.add(m);
            layer.logCost[m] = layer.mined.get(m) ? layer.logRawPrice(m) : LogMath.INF;
            layer.best[m] = -1;
            layer.scc[m] = -1;
        }
        // value iteration (Bellman–Ford) for a first policy: the cheapest finite route, which never loops
        int[] pol = new int[comp.length];
        Arrays.fill(pol, -1);
        for (int it = 0; it <= comp.length; it++) {
            boolean changed = false;
            for (int i = 0; i < comp.length; i++) {
                int m = comp[i];
                if (layer.mined.get(m))
                    continue;
                for (int q : layer.producers[m]) {
                    layer.computeRunCost(q);
                    double c = layer.unitCost(q, m);
                    if (c < layer.logCost[m]) {
                        layer.logCost[m] = c;
                        pol[i] = q;
                        changed = true;
                    }
                }
            }
            if (!changed)
                break;
        }
        // Bellman–Ford cannot enter a loop that only runs once it is seeded (growth loops). For
        // members still impossible, guess a route twice — cheapest with members counted as free,
        // and the one needing the fewest members per output (most likely to converge) — solve each
        // exactly, improve it by single switches, and keep the guess that makes more members possible
        boolean[] stuck = new boolean[comp.length];
        boolean anyStuck = false;
        for (int i = 0; i < comp.length; i++) {
            stuck[i] = layer.logCost[comp[i]] == LogMath.INF && layer.producers[comp[i]].length > 0;
            anyStuck |= stuck[i];
        }
        if (anyStuck) {
            int[] cheap = pol.clone();
            int[] lean = pol.clone();
            for (int i = 0; i < comp.length; i++) {
                if (!stuck[i])
                    continue;
                int m = comp[i];
                double bestC = LogMath.INF;
                double bestGain = Double.POSITIVE_INFINITY;
                double bestGainCost = LogMath.INF;
                for (int q : layer.producers[m]) {
                    double c = optimisticUnitCost(q, m, in);
                    if (c < bestC) {
                        bestC = c;
                        cheap[i] = q;
                    }
                    double g = memberInputsPerOutput(q, m, in);
                    if (c != LogMath.INF && (g < bestGain || (g == bestGain && c < bestGainCost))) {
                        bestGain = g;
                        bestGainCost = c;
                        lean[i] = q;
                    }
                }
                if (cheap[i] < 0)
                    cheap[i] = layer.producers[m][0];
                if (lean[i] < 0)
                    lean[i] = cheap[i];
            }
            // third guess: value iteration from below (stuck members start free); its argmin
            // policy usually enters the loops the two guesses above miss
            int[] below = pol.clone();
            for (int i = 0; i < comp.length; i++)
                if (stuck[i])
                    layer.logCost[comp[i]] = LogMath.ZERO;
            for (int sweep = 0; sweep < MAX_BELOW_SWEEPS; sweep++) {
                boolean changed = false;
                for (int i = 0; i < comp.length; i++) {
                    int m = comp[i];
                    if (layer.mined.get(m))
                        continue;
                    double bestC = LogMath.INF;
                    for (int q : layer.producers[m]) {
                        layer.computeRunCost(q);
                        double c = layer.unitCost(q, m);
                        if (c < bestC) {
                            bestC = c;
                            below[i] = q;
                        }
                    }
                    if (below[i] >= 0 && !LogMath.sameWithin(bestC, layer.logCost[m], 1e-12)) {
                        layer.logCost[m] = bestC;
                        changed = true;
                    }
                }
                if (!changed)
                    break;
            }
            int[][] guesses = {cheap, lean, below};
            int bestFinite = -1;
            for (int[] g : guesses) {
                evaluate(comp, in, g);
                seedLoops(comp, in, g);
                int f = countFinite(costsOf(comp));
                if (f > bestFinite) {
                    bestFinite = f;
                    pol = g;
                }
            }
        }
        evaluate(comp, in, pol);

        Set<Long> banned = new HashSet<>();
        Set<String> seen = new HashSet<>();
        seen.add(Arrays.toString(pol));
        for (int it = 0; it < MAX_POLICY_ITERATIONS; it++) {
            int[] prev = pol.clone();
            double[] prevCost = costsOf(comp);
            boolean changed = false;
            for (int i = 0; i < comp.length; i++) {
                int m = comp[i];
                if (layer.producers[m].length == 0 || layer.mined.get(m))
                    continue;
                for (int q : layer.producers[m])
                    layer.computeRunCost(q);
                layer.sortProducers(m);
                int cand = firstAllowed(m, i, banned);
                if (cand >= 0 && cand != pol[i] && layer.strictlyBetter(cand, pol[i], m)) {
                    pol[i] = cand;
                    changed = true;
                }
            }
            if (!changed)
                break;
            evaluate(comp, in, pol);
            if (regressed(comp, prevCost)) {
                // switching together closed a loop that cannot run: retry the switches one by one;
                // one that makes something impossible on its own is banned — but only while the rest
                // of the policy stays as it is (any accepted switch lifts the old bans)
                int[] next = pol;
                pol = prev.clone();
                evaluate(comp, in, pol);
                boolean accepted = false;
                Set<Long> rejected = new HashSet<>();
                for (int i = 0; i < comp.length; i++) {
                    if (next[i] == prev[i])
                        continue;
                    double[] before = costsOf(comp);
                    pol[i] = next[i];
                    evaluate(comp, in, pol);
                    if (regressed(comp, before)) {
                        pol[i] = prev[i];
                        rejected.add(((long) i << 32) | next[i]);
                        evaluate(comp, in, pol);
                    } else
                        accepted = true;
                }
                if (accepted)
                    banned.clear();
                banned.addAll(rejected);
            } else
                banned.clear();
            if (!seen.add(Arrays.toString(pol) + banned))
                break;      // been here with the same bans: no progress possible
        }
        finish(comp, in, pol);
    }

    private static final int MAX_SEED_EVALUATIONS = 2_000;
    private static final int MAX_BELOW_SWEEPS = 200;

    /**
     * The optimistic guess can pick a loop that does not converge. For members still impossible,
     * try their other routes one at a time and keep any switch that makes more members possible
     * (without losing any). Rare in real packs; bounded by {@link #MAX_SEED_EVALUATIONS}.
     */
    private void seedLoops(int[] comp, Set<Integer> in, int[] pol) {
        LocalEval local = new LocalEval(comp, pol);
        int evaluations = 0;
        boolean improved = true;
        while (improved && evaluations < MAX_SEED_EVALUATIONS) {
            improved = false;
            for (int i = 0; i < comp.length && evaluations < MAX_SEED_EVALUATIONS; i++) {
                int m = comp[i];
                if (layer.logCost[m] != LogMath.INF)
                    continue;
                // only m and what depends on it can change; the same set whichever route m takes
                int[] down = local.downstream(i);
                double[] before = local.costs(down);
                int finite = countFinite(before);
                int old = pol[i];
                boolean switched = false;
                for (int q : layer.producers[m]) {
                    if (q == old)
                        continue;
                    pol[i] = q;
                    local.evaluate(down);
                    evaluations++;
                    double[] now = local.costs(down);
                    if (!regressed(before, now) && countFinite(now) > finite) {
                        local.routeChanged(i, old);
                        improved = true;
                        switched = true;
                        break;
                    }
                }
                if (!switched) {
                    pol[i] = old;
                    local.restore(down, before);
                }
            }
        }
    }

    /**
     * Re-evaluates what one member's route switch can change: the member and what depends on it
     * along the policy, its downstream in the component. Nothing upstream depends on the switch, so
     * it keeps its value; groups are solved the same way as {@link #evaluate}, so the result is the
     * same as evaluating the whole component. Expects the component evaluated for {@code pol} when
     * created, and {@link #routeChanged} after every switch that is kept.
     */
    private final class LocalEval {
        private final int[] comp;
        private final int[] pol;
        private final Map<Integer, Integer> idx = new HashMap<>();
        /** per member (local index): the members whose route takes it as an input */
        private final List<List<Integer>> users = new ArrayList<>();

        LocalEval(int[] comp, int[] pol) {
            this.comp = comp;
            this.pol = pol;
            for (int i = 0; i < comp.length; i++) {
                idx.put(comp[i], i);
                users.add(new ArrayList<>());
            }
            for (int i = 0; i < comp.length; i++)
                link(i, pol[i], true);
        }

        private void link(int i, int q, boolean add) {
            if (q < 0)
                return;
            Pattern p = layer.patterns.get(q);
            for (int s = 0; s < p.inputCount(); s++) {
                Integer y = idx.get(p.inputItem(s));
                if (y == null)
                    continue;
                if (add)
                    users.get(y).add(i);
                else
                    users.get(y).remove(Integer.valueOf(i));
            }
        }

        /** Member {@code i} now keeps the route in {@code pol[i]} instead of {@code old}. */
        void routeChanged(int i, int old) {
            link(i, old, false);
            link(i, pol[i], true);
        }

        /** {@code i} and everything that depends on it along the policy, as sorted local indices. */
        int[] downstream(int i) {
            boolean[] seen = new boolean[comp.length];
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            seen[i] = true;
            queue.add(i);
            while (!queue.isEmpty())
                for (int v : users.get(queue.poll()))
                    if (!seen[v]) {
                        seen[v] = true;
                        queue.add(v);
                    }
            int n = 0;
            for (boolean b : seen)
                if (b)
                    n++;
            int[] out = new int[n];
            int k = 0;
            for (int j = 0; j < comp.length; j++)
                if (seen[j])
                    out[k++] = j;
            return out;
        }

        double[] costs(int[] down) {
            double[] c = new double[down.length];
            for (int j = 0; j < down.length; j++)
                c[j] = layer.logCost[comp[down[j]]];
            return c;
        }

        void restore(int[] down, double[] saved) {
            for (int j = 0; j < down.length; j++)
                layer.logCost[comp[down[j]]] = saved[j];
        }

        /** Groups the policy graph on {@code down} (edges from outside it are inputs, already final) and solves them in order. */
        void evaluate(int[] down) {
            int[] nodes = new int[down.length];
            Map<Integer, List<Integer>> succ = new HashMap<>();
            for (int j = 0; j < down.length; j++)
                nodes[j] = comp[down[j]];
            Set<Integer> part = new HashSet<>();
            for (int v : nodes)
                part.add(v);
            for (int j = 0; j < down.length; j++) {
                int q = pol[down[j]];
                if (q < 0)
                    continue;
                Pattern p = layer.patterns.get(q);
                for (int s = 0; s < p.inputCount(); s++) {
                    int y = p.inputItem(s);
                    if (part.contains(y))
                        succ.computeIfAbsent(y, k -> new ArrayList<>()).add(nodes[j]);
                }
            }
            for (int[] group : Tarjan.components(nodes, u -> {
                List<Integer> l = succ.get(u);
                return l == null ? EMPTY : l.stream().mapToInt(Integer::intValue).toArray();
            }))
                evaluateGroup(group, idx, pol);
        }
    }

    private static int countFinite(double[] costs) {
        int n = 0;
        for (double c : costs)
            if (c != LogMath.INF)
                n++;
        return n;
    }

    /** Members used up per output of {@code m}: a loop converges when this multiplies to less than 1 around it. */
    private double memberInputsPerOutput(int q, int m, Set<Integer> in) {
        Pattern p = layer.patterns.get(q);
        double sum = 0;
        for (int s = 0; s < p.inputCount(); s++) {
            if (!in.contains(p.inputItem(s)))
                continue;
            switch (p.inputKind(s)) {
                case CONSUME -> sum += Math.pow(10, p.inputAmount(s).log10());
                case TOOL -> sum += p.inputDurability(s) > 0 ? 1.0 / p.inputDurability(s) : Math.pow(10, p.inputAmount(s).log10());
                default -> {
                }
            }
        }
        return sum / Math.pow(10, p.mainOutputLog10(m));
    }

    /** Unit cost with every input inside the component taken as free (only outside inputs count). */
    private double optimisticUnitCost(int q, int m, Set<Integer> in) {
        Pattern p = layer.patterns.get(q);
        double sum = LogMath.ZERO;
        for (int s = 0; s < p.inputCount(); s++) {
            int y = p.inputItem(s);
            if (in.contains(y))
                continue;
            switch (p.inputKind(s)) {
                case CONSUME -> sum = LogMath.add(sum, LogMath.mul(p.inputAmount(s).log10(), layer.logCost[y]));
                case TOOL -> sum = LogMath.add(sum, p.inputDurability(s) > 0
                        ? LogMath.mul(layer.logCost[y], -Math.log10(p.inputDurability(s)))
                        : LogMath.mul(p.inputAmount(s).log10(), layer.logCost[y]));
                default -> {
                    // a catalyst or container from a later component has no value yet (nor in a rebuild)
                    if (layer.compOf[y] > layer.compOf[m] || layer.logCost[y] == LogMath.INF)
                        return LogMath.INF;
                }
            }
        }
        if (sum == LogMath.ZERO || sum == LogMath.INF)
            return sum;
        return sum - p.mainOutputLog10(m);
    }

    private int firstAllowed(int m, int i, Set<Long> banned) {
        for (int q : layer.producers[m])
            if (!banned.contains(((long) i << 32) | q))
                return q;
        return -1;
    }

    private double[] costsOf(int[] comp) {
        double[] c = new double[comp.length];
        for (int i = 0; i < comp.length; i++)
            c[i] = layer.logCost[comp[i]];
        return c;
    }

    /** Some member that could be made before can no longer be made. */
    private boolean regressed(int[] comp, double[] before) {
        for (int i = 0; i < comp.length; i++)
            if (before[i] != LogMath.INF && layer.logCost[comp[i]] == LogMath.INF)
                return true;
        return false;
    }

    private static boolean regressed(double[] before, double[] now) {
        for (int i = 0; i < before.length; i++)
            if (before[i] != LogMath.INF && now[i] == LogMath.INF)
                return true;
        return false;
    }

    /** Groups of the policy graph inside {@code comp}, sources first. */
    private List<int[]> policyGroups(int[] comp, Set<Integer> in, int[] pol) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < comp.length; i++)
            idx.put(comp[i], i);
        Map<Integer, List<Integer>> succ = new HashMap<>();
        for (int i = 0; i < comp.length; i++) {
            if (pol[i] < 0)
                continue;
            Pattern p = layer.patterns.get(pol[i]);
            for (int s = 0; s < p.inputCount(); s++) {
                int y = p.inputItem(s);
                if (in.contains(y))
                    succ.computeIfAbsent(y, k -> new ArrayList<>()).add(comp[i]);
            }
        }
        return Tarjan.components(comp, u -> {
            List<Integer> l = succ.get(u);
            return l == null ? EMPTY : l.stream().mapToInt(Integer::intValue).toArray();
        });
    }

    private void evaluate(int[] comp, Set<Integer> in, int[] pol) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < comp.length; i++)
            idx.put(comp[i], i);
        for (int[] group : policyGroups(comp, in, pol))
            evaluateGroup(group, idx, pol);
    }

    /** One group of the policy graph, its inputs from outside the group already final. */
    private void evaluateGroup(int[] group, Map<Integer, Integer> idx, int[] pol) {
        if (group.length == 1) {
            int m = group[0];
            int q = pol[idx.get(m)];
            if (q < 0) {
                layer.logCost[m] = layer.mined.get(m) ? layer.logRawPrice(m) : LogMath.INF;
                return;
            }
            if (!layer.patterns.get(q).hasInput(m)) {
                layer.computeRunCost(q);
                layer.logCost[m] = layer.unitCost(q, m);
                return;
            }
        }
        solveGroupCost(group, idx, pol);
        priceUnseeded(group, idx, pol);
    }

    /**
     * A loop of the policy that needs members up front (its seeds) and has none of any seed option:
     * none in stock, and none made by a route from outside the loop. It cannot start, so it is priced
     * like a raw out of stock (at least {@link TheoryLayer#LOG_HIGH}); a route that does not need it
     * wins. Coming into stock makes the member dirty ({@code TheoryLayer.onStockChanged}).
     */
    private void priceUnseeded(int[] group, Map<Integer, Integer> idx, int[] pol) {
        Set<Integer> members = new HashSet<>();
        for (int m : group)
            members.add(m);
        LinkedHashSet<Integer> distinct = new LinkedHashSet<>();
        for (int m : group) {
            int q = pol[idx.get(m)];
            if (q < 0)
                return;
            distinct.add(q);
        }
        int[] pats = distinct.stream().mapToInt(Integer::intValue).toArray();
        for (AmountMap option : seeds(pats, members).options())
            if (canSeed(option, members, distinct))
                return;
        for (int m : group)
            layer.logCost[m] = Math.max(layer.logCost[m], TheoryLayer.LOG_HIGH);
    }

    /** Every seed of {@code option} is in stock, or made by a possible route that takes nothing from the loop. */
    private boolean canSeed(AmountMap option, Set<Integer> members, Set<Integer> loopPatterns) {
        for (int y : option.keys()) {
            if (layer.infinite.get(y) || !layer.noStock.get(y))
                continue;
            boolean made = false;
            for (int q : layer.producers[y]) {
                if (loopPatterns.contains(q) || takesFrom(q, members))
                    continue;
                layer.computeRunCost(q);
                if (layer.unitCost(q, y) != LogMath.INF) {
                    made = true;
                    break;
                }
            }
            if (!made)
                return false;
        }
        return true;
    }

    private boolean takesFrom(int q, Set<Integer> members) {
        Pattern p = layer.patterns.get(q);
        for (int s = 0; s < p.inputCount(); s++)
            if (members.contains(p.inputItem(s)))
                return true;
        return false;
    }

    /**
     * Seeds of a loop: run each of its patterns once, in every rotation; what each rotation needs
     * of the members up front is an option. {@code best} needs the fewest; {@code growth}: with it
     * the loop ends up with more members than it started with.
     */
    private record Seeds(List<AmountMap> options, AmountMap best, boolean growth) {
    }

    private Seeds seeds(int[] pats, Set<Integer> members) {
        AmountMap bestSeeds = null;
        Amount bestTotal = null;
        boolean growth = false;
        List<AmountMap> options = new ArrayList<>();
        for (int r = 0; r < pats.length; r++) {
            Amount[] runs = roundRuns(pats, r, members);
            AmountMap seeds = new AmountMap();
            AmountMap inv = new AmountMap();
            for (int t = 0; t < pats.length; t++) {
                Pattern p = layer.patterns.get(pats[(r + t) % pats.length]);
                for (int s = 0; s < p.inputCount(); s++) {
                    int y = p.inputItem(s);
                    if (!members.contains(y))
                        continue;
                    Amount need = p.inputKind(s) == InputKind.TOOL ? Amount.ONE
                            : p.inputKind(s) == InputKind.CONSUME ? p.inputAmount(s).mul(runs[t]) : p.inputAmount(s);
                    Amount have = inv.get(y);
                    if (have.compareTo(need) < 0) {
                        seeds.add(y, need.sub(have));
                        inv.set(y, need);
                    }
                    if (p.inputKind(s) == InputKind.CONSUME)
                        inv.sub(y, need);
                }
                for (int o = 0; o < p.outputCount(); o++)
                    if (members.contains(p.outputItem(o)) && !p.isChance(o))
                        inv.add(p.outputItem(o), p.outputAmount(o).mul(runs[t]));
            }
            Amount total = seeds.total();
            if (options.stream().noneMatch(o -> o.toString().equals(seeds.toString())))
                options.add(seeds);
            if (bestTotal == null || total.compareTo(bestTotal) < 0) {
                bestTotal = total;
                bestSeeds = seeds;
                growth = inv.total().compareTo(total) > 0;
            }
        }
        return new Seeds(options, bestSeeds, growth);
    }

    /**
     * Runs of each pattern in one round starting at pattern {@code r}: the last runs once, each one
     * before it as often as the ones after it need of what it makes (8 dust for one run of the seed
     * pattern = 8 crushings). Running each pattern once would ask for too small a seed whenever a
     * pattern takes more of a member than the one before it makes.
     */
    private Amount[] roundRuns(int[] pats, int r, Set<Integer> members) {
        int n = pats.length;
        Amount[] runs = new Amount[n];
        AmountMap wanted = new AmountMap();     // members the later patterns of the round still need
        for (int t = n - 1; t >= 0; t--) {
            Pattern p = layer.patterns.get(pats[(r + t) % n]);
            Amount run = Amount.ONE;
            for (int o = 0; o < p.outputCount(); o++) {
                int y = p.outputItem(o);
                if (!members.contains(y) || p.isChance(o) || p.outputAmount(o).signum() <= 0 || wanted.get(y).signum() <= 0)
                    continue;
                run = run.max(wanted.get(y).ceilDiv(p.outputAmount(o)));
            }
            runs[t] = run;
            for (int o = 0; o < p.outputCount(); o++) {
                int y = p.outputItem(o);
                if (members.contains(y) && !p.isChance(o))
                    wanted.set(y, wanted.get(y).sub(p.outputAmount(o).mul(run)).clampNonNegative());
            }
            for (int s = 0; s < p.inputCount(); s++)
                if (members.contains(p.inputItem(s)) && p.inputKind(s) == InputKind.CONSUME)
                    wanted.add(p.inputItem(s), p.inputAmount(s).mul(run));
        }
        return runs;
    }

    /** c = A c + b for a cycle of the policy; no external source (or a non-convergent loop) → ∞. */
    private void solveGroupCost(int[] group, Map<Integer, Integer> idx, int[] pol) {
        int k = group.length;
        Map<Integer, Integer> local = new HashMap<>();
        for (int i = 0; i < k; i++)
            local.put(group[i], i);
        double[][] a = new double[k][k];
        double[] bLog = new double[k];
        boolean external = false;
        for (int i = 0; i < k; i++) {
            int m = group[i];
            int q = pol[idx.get(m)];
            if (q < 0) {
                setAll(group, LogMath.INF);
                return;
            }
            Pattern p = layer.patterns.get(q);
            double outLog = p.mainOutputLog10(m);
            double b = LogMath.ZERO;
            for (int s = 0; s < p.inputCount(); s++) {
                int y = p.inputItem(s);
                InputKind kind = p.inputKind(s);
                double perUnitLog;
                if (kind == InputKind.CONSUME || (kind == InputKind.TOOL && p.inputDurability(s) <= 0))
                    perUnitLog = p.inputAmount(s).log10() - outLog;
                else if (kind == InputKind.TOOL)
                    perUnitLog = -Math.log10(p.inputDurability(s)) - outLog;
                else
                    continue;
                Integer j = local.get(y);
                if (j != null)
                    a[i][j] += Math.pow(10, perUnitLog);
                else {
                    b = LogMath.add(b, LogMath.mul(perUnitLog, layer.logCost[y]));
                    external = true;
                }
            }
            bLog[i] = b;
        }
        if (!external) {
            setAll(group, LogMath.INF);     // a loop with no outside source at all cannot be made
            return;
        }
        double l0 = LogMath.ZERO;
        for (double b : bLog) {
            if (b == LogMath.INF) {
                setAll(group, LogMath.INF);
                return;
            }
            l0 = Math.max(l0, b);
        }
        double[][] mtx = new double[k][k];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++)
                mtx[i][j] = (i == j ? 1 : 0) - a[i][j];
        DenseLu lu = DenseLu.factor(mtx);
        if (lu == null) {
            setAll(group, LogMath.INF);
            return;
        }
        if (l0 == LogMath.ZERO) {
            setAll(group, LogMath.ZERO);   // everything external is free
            return;
        }
        double[] rhs = new double[k];
        for (int i = 0; i < k; i++)
            rhs[i] = bLog[i] == LogMath.ZERO ? 0 : Math.pow(10, bLog[i] - l0);
        double[] c = lu.solve(rhs);
        double maxAbs = 0;
        for (double v : c)
            maxAbs = Math.max(maxAbs, Math.abs(v));
        for (double v : c)
            if (!Double.isFinite(v) || v < -1e-9 * maxAbs) {
                setAll(group, LogMath.INF);
                return;
            }
        for (int i = 0; i < k; i++)
            layer.logCost[group[i]] = c[i] <= 0 ? LogMath.ZERO : Math.log10(c[i]) + l0;
    }

    private void setAll(int[] group, double v) {
        for (int m : group)
            layer.logCost[m] = v;
    }

    /** Writes best / level / BOM / policy cycles for the final policy of {@code comp}. */
    private void finish(int[] comp, Set<Integer> in, int[] pol) {
        Map<Integer, Integer> idx = new HashMap<>();
        for (int i = 0; i < comp.length; i++)
            idx.put(comp[i], i);
        for (int[] group : policyGroups(comp, in, pol)) {
            int first = group[0];
            boolean single = group.length == 1 && (pol[idx.get(first)] < 0 || !layer.patterns.get(pol[idx.get(first)]).hasInput(first));
            if (single) {
                int q = pol[idx.get(first)];
                if (q < 0) {
                    layer.setRaw(first);
                    if (!layer.mined.get(first))
                        layer.logCost[first] = LogMath.INF;
                    layer.level[first] = 0;
                    continue;
                }
                layer.best[first] = q;
                layer.level[first] = 1 + layer.maxInputLevel(q, first);
                layer.computeRunCost(q);
                layer.computePatBom(q);
                layer.setItemBomFrom(first, q);
                continue;
            }
            makeScc(group, idx, pol);
        }
        // every producer of a member now sees final member costs
        for (int m : comp) {
            for (int q : layer.producers[m]) {
                layer.computeRunCost(q);
                layer.computePatBom(q);
            }
            layer.sortProducers(m);
        }
    }

    private void makeScc(int[] group, Map<Integer, Integer> idx, int[] pol) {
        int k = group.length;
        Set<Integer> members = new HashSet<>();
        for (int m : group)
            members.add(m);
        int lvl = 0;
        int[] policy = new int[k];
        LinkedHashSet<Integer> distinct = new LinkedHashSet<>();
        for (int i = 0; i < k; i++) {
            policy[i] = pol[idx.get(group[i])];
            distinct.add(policy[i]);
            Pattern p = layer.patterns.get(policy[i]);
            for (int s = 0; s < p.inputCount(); s++)
                if (!members.contains(p.inputItem(s)) && layer.compOf[p.inputItem(s)] <= layer.compOf[group[i]])
                    lvl = Math.max(lvl, layer.level[p.inputItem(s)]);
        }
        lvl += 1;
        int index = -1;     // numbered at publish
        for (int i = 0; i < k; i++) {
            layer.level[group[i]] = lvl;
            layer.best[group[i]] = policy[i];
        }
        int[] pats = distinct.stream().mapToInt(Integer::intValue).toArray();

        // net[i][j]: what one run of pats[j] adds to member i, exactly
        Rational[][] net = new Rational[k][pats.length];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < pats.length; j++) {
                Pattern p = layer.patterns.get(pats[j]);
                Rational v = Rational.of(p.sureOutputAmountOf(group[i]));
                for (int s = 0; s < p.inputCount(); s++) {
                    if (p.inputItem(s) != group[i])
                        continue;
                    if (p.inputKind(s) == InputKind.CONSUME || (p.inputKind(s) == InputKind.TOOL && p.inputDurability(s) <= 0))
                        v = v.sub(Rational.of(p.inputAmount(s)));
                    else if (p.inputKind(s) == InputKind.TOOL)
                        v = v.sub(Rational.of(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(p.inputDurability(s))));
                }
                net[i][j] = v;
            }

        // seeds: run each pattern once, try every rotation, keep the one needing the fewest members up front
        Seeds found = seeds(pats, members);
        AmountMap bestSeeds = found.best();
        boolean growth = found.growth();
        List<AmountMap> options = new ArrayList<>(found.options());
        int[] seedItems = bestSeeds.keys();
        Amount[] seedAmounts = new Amount[seedItems.length];
        for (int i = 0; i < seedItems.length; i++)
            seedAmounts[i] = bestSeeds.get(seedItems[i]);
        AmountMap first = bestSeeds;
        options.remove(first);
        options.sort(java.util.Comparator.comparing(AmountMap::total));     // stable: rotation order on ties
        options.add(0, first);

        layer.building.add(new Scc(index, group.clone(), policy, pats, growth ? SccKind.GROWTH : SccKind.ENTANGLED,
                seedItems, seedAmounts, options.toArray(AmountMap[]::new), net));
        sccBom(group, policy, members);
    }

    /** BOM of cycle members: (I − A) B = B_ext, one raw at a time (estimates). */
    private void sccBom(int[] group, int[] policy, Set<Integer> members) {
        int k = group.length;
        Map<Integer, Integer> local = new HashMap<>();
        for (int i = 0; i < k; i++)
            local.put(group[i], i);
        double[][] a = new double[k][k];
        List<TreeMap<Integer, Double>> ext = new ArrayList<>();
        Set<Integer> raws = new java.util.TreeSet<>();
        for (int i = 0; i < k; i++) {
            Pattern p = layer.patterns.get(policy[i]);
            double outLog = p.mainOutputLog10(group[i]);
            TreeMap<Integer, Double> e = new TreeMap<>();
            for (int s = 0; s < p.inputCount(); s++) {
                InputKind kind = p.inputKind(s);
                double perUnitLog;
                if (kind == InputKind.CONSUME || (kind == InputKind.TOOL && p.inputDurability(s) <= 0))
                    perUnitLog = p.inputAmount(s).log10() - outLog;
                else if (kind == InputKind.TOOL)
                    perUnitLog = -Math.log10(p.inputDurability(s)) - outLog;
                else
                    continue;
                int y = p.inputItem(s);
                Integer j = local.get(y);
                if (j != null) {
                    a[i][j] += Math.pow(10, perUnitLog);
                    continue;
                }
                for (int t = 0; t < layer.bomItems[y].length; t++) {
                    e.merge(layer.bomItems[y][t], layer.bomLog[y][t] + perUnitLog, LogMath::add);
                    raws.add(layer.bomItems[y][t]);
                }
            }
            ext.add(e);
        }
        double[][] mtx = new double[k][k];
        for (int i = 0; i < k; i++)
            for (int j = 0; j < k; j++)
                mtx[i][j] = (i == j ? 1 : 0) - a[i][j];
        DenseLu lu = DenseLu.factor(mtx);
        List<TreeMap<Integer, Double>> result = new ArrayList<>();
        for (int i = 0; i < k; i++)
            result.add(new TreeMap<>());
        if (lu != null) {
            for (int raw : raws) {
                double l0 = LogMath.ZERO;
                for (TreeMap<Integer, Double> e : ext)
                    l0 = Math.max(l0, e.getOrDefault(raw, LogMath.ZERO));
                if (l0 == LogMath.ZERO)
                    continue;
                double[] rhs = new double[k];
                for (int i = 0; i < k; i++) {
                    double v = ext.get(i).getOrDefault(raw, LogMath.ZERO);
                    rhs[i] = v == LogMath.ZERO ? 0 : Math.pow(10, v - l0);
                }
                double[] sol = lu.solve(rhs);
                for (int i = 0; i < k; i++)
                    if (Double.isFinite(sol[i]) && sol[i] > 0)
                        result.get(i).put(raw, Math.log10(sol[i]) + l0);
            }
        }
        for (int i = 0; i < k; i++) {
            TreeMap<Integer, Double> r = result.get(i);
            layer.bomItems[group[i]] = r.keySet().stream().mapToInt(Integer::intValue).toArray();
            layer.bomLog[group[i]] = r.values().stream().mapToDouble(Double::doubleValue).toArray();
        }
        for (int q : policy)
            layer.computePatBom(q);
    }
}
