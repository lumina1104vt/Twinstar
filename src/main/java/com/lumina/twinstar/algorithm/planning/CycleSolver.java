package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.cache.Scc;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.Rational;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * D — policy cycles. To B a cycle is one macro pattern: per-run external inputs plus a fixed set of
 * startup seeds. The firing order is not fixed at planning time; the executor's rules ("run what
 * can run, protect the seeds") handle it. When one batch simulation of those rules gets stuck, the
 * state equation proves impossibility, or a budgeted A* looks for an order (an execution contract).
 */
public final class CycleSolver {
    private static final int MAX_FIX_ROUNDS = 64;
    private static final int MAX_SWEEPS = 10_000;
    private static final int A_STAR_BUDGET = 20_000;

    private CycleSolver() {
    }

    /**
     * Runs of each loop pattern so the loop adds at least {@code demand[i]} of member i:
     * minimise Σ runs subject to net · runs ≥ demand (exact), then round up and fix rounding.
     * Null if the loop cannot produce that.
     */
    public static Amount @Nullable [] runs(Scc s, Amount[] demand) {
        int k = s.memberCount();
        int n = s.patternCount();
        Rational[][] a = new Rational[k][n];
        Rational[] b = new Rational[k];
        Rational[] c = new Rational[n];
        Arrays.fill(c, Rational.ONE);
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < n; j++)
                a[i][j] = s.net(i, j);
            b[i] = Rational.of(demand[i]);
        }
        Rational[] x = ExactSimplex.minimize(c, a, b);
        if (x == null)
            return null;
        Amount[] runs = new Amount[n];
        for (int j = 0; j < n; j++)
            runs[j] = Amount.of(x[j].ceil().max(BigInteger.ZERO));
        // rounding up one pattern can eat another member's margin: top up with that member's own pattern
        for (int round = 0; round < MAX_FIX_ROUNDS; round++) {
            boolean ok = true;
            for (int i = 0; i < k; i++) {
                Rational have = Rational.ZERO;
                for (int j = 0; j < n; j++)
                    have = have.add(s.net(i, j).mul(Rational.of(runs[j])));
                Rational shortBy = Rational.of(demand[i]).sub(have);
                if (shortBy.signum() <= 0)
                    continue;
                ok = false;
                int j = columnOf(s, s.policy(i));
                if (s.net(i, j).signum() <= 0)
                    return null;
                runs[j] = runs[j].add(Amount.of(shortBy.div(s.net(i, j)).ceil()));
            }
            if (ok)
                return runs;
        }
        return null;
    }

    private static int columnOf(Scc s, int pattern) {
        for (int j = 0; j < s.patternCount(); j++)
            if (s.pattern(j) == pattern)
                return j;
        throw new IllegalStateException("policy pattern not in the loop");
    }

    /** Outcome of checking that a loop can actually be run with the seeds the plan holds. */
    public sealed interface Check permits Check.Runs, Check.Contract, Check.Impossible {
        record Runs() implements Check {
        }

        record Contract(CycleContract contract) implements Check {
        }

        /** Proven (state equation) or not found within budget; the seed shortfall is a shortage. */
        record Impossible() implements Check {
        }
    }

    /**
     * One batch simulation of the executor rules with {@code seeds}; if it gets stuck, the state
     * equation (exact) decides impossibility, otherwise A* searches for a firing order.
     */
    public static Check verify(Snapshot snap, Scc s, Amount[] runs, AmountMap seeds, Amount[] demand, Deadline dl) {
        int k = s.memberCount();
        Amount[] inv = new Amount[k];
        for (int i = 0; i < k; i++)
            inv[i] = seeds.get(s.member(i));
        Amount[] remaining = runs.clone();
        if (simulate(snap, s, inv.clone(), remaining.clone()))
            return new Check.Runs();

        // state equation: stock + net · x ≥ demand, x ≥ 0 (fractions allowed). No solution → proven impossible.
        Rational[][] a = new Rational[k][s.patternCount()];
        Rational[] b = new Rational[k];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < s.patternCount(); j++)
                a[i][j] = s.net(i, j);
            b[i] = Rational.of(demand[i]).sub(Rational.of(inv[i]));
        }
        if (!ExactSimplex.feasible(a, b))
            return new Check.Impossible();

        CycleContract c = aStar(snap, s, inv, remaining, dl);
        return c != null ? new Check.Contract(c) : new Check.Impossible();
    }

    /** "Run what can run": each sweep fires every ready pattern as a batch; seeds come back, so batches grow 1 → 2 → 4. */
    static boolean simulate(Snapshot snap, Scc s, Amount[] inv, Amount[] remaining) {
        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            boolean done = true;
            boolean progress = false;
            for (int j = 0; j < s.patternCount(); j++) {
                if (remaining[j].signum() <= 0)
                    continue;
                done = false;
                Amount batch = supportable(snap, s, j, inv).min(remaining[j]);
                if (batch.signum() <= 0)
                    continue;
                fire(snap, s, j, batch, inv);
                remaining[j] = remaining[j].sub(batch);
                progress = true;
            }
            if (done)
                return true;
            if (!progress)
                return false;
        }
        return true;    // still making progress after many sweeps: growth loop, assume it finishes
    }

    /** How many runs of loop pattern j the member stock supports (inputs from outside are B's job). */
    static Amount supportable(Snapshot snap, Scc s, int j, Amount[] inv) {
        Pattern p = snap.pattern(s.pattern(j));
        Amount max = null;
        for (int slot = 0; slot < p.inputCount(); slot++) {
            int i = s.memberIndex(p.inputItem(slot));
            if (i < 0)
                continue;
            Amount have = inv[i];
            Amount lim;
            switch (p.inputKind(slot)) {
                case CONSUME -> lim = have.div(p.inputAmount(slot));
                case TOOL -> lim = p.inputDurability(slot) > 0
                        ? have.mul(p.inputDurability(slot))
                        : have.div(p.inputAmount(slot));
                default -> {
                    if (have.compareTo(p.inputAmount(slot)) < 0)
                        return Amount.ZERO;
                    continue;
                }
            }
            max = max == null ? lim : max.min(lim);
        }
        return max == null ? Amount.of(Long.MAX_VALUE).mul(Amount.of(Long.MAX_VALUE)) : max;
    }

    static void fire(Snapshot snap, Scc s, int j, Amount batch, Amount[] inv) {
        Pattern p = snap.pattern(s.pattern(j));
        for (int slot = 0; slot < p.inputCount(); slot++) {
            int i = s.memberIndex(p.inputItem(slot));
            if (i < 0)
                continue;
            if (p.inputKind(slot) == InputKind.CONSUME)
                inv[i] = inv[i].sub(p.inputAmount(slot).mul(batch));
            else if (p.inputKind(slot) == InputKind.TOOL)
                inv[i] = inv[i].sub(p.inputDurability(slot) > 0
                        ? batch.ceilDiv(p.inputDurability(slot))
                        : p.inputAmount(slot).mul(batch));
        }
        for (int o = 0; o < p.outputCount(); o++) {
            int i = s.memberIndex(p.outputItem(o));
            if (i >= 0)
                inv[i] = inv[i].add(p.sureOutputAmount(o).mul(batch));
        }
    }

    private record Node(Amount[] inv, Amount[] remaining, int g, int h, Node parent, int via, Amount batch) {
    }

    /**
     * A visited state: equal when both amount arrays are equal. Compares the amounts themselves;
     * the former key, both arrays turned into decimal text, took 75% of a real pack's slow orders
     * (1e100 amounts) for the same answer.
     */
    private record Seen(Amount[] inv, Amount[] remaining) {
        @Override
        public boolean equals(Object o) {
            return o instanceof Seen k && Arrays.equals(inv, k.inv) && Arrays.equals(remaining, k.remaining);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(inv) + Arrays.hashCode(remaining);
        }
    }

    /** Budgeted A*: moves are "fire pattern j as a full batch" or "fire it once"; h = patterns still to run. */
    private static @Nullable CycleContract aStar(Snapshot snap, Scc s, Amount[] inv0, Amount[] rem0, Deadline dl) {
        PriorityQueue<Node> open = new PriorityQueue<>((x, y) -> x.g + x.h != y.g + y.h
                ? Integer.compare(x.g + x.h, y.g + y.h) : Integer.compare(y.g, x.g));
        Set<Seen> seen = new HashSet<>();
        open.add(new Node(inv0, rem0, 0, heuristic(rem0), null, -1, Amount.ZERO));
        int expanded = 0;
        while (!open.isEmpty() && expanded < A_STAR_BUDGET && !dl.softPassed()) {
            Node cur = open.poll();
            if (cur.h == 0)
                return contract(s, cur);
            if (!seen.add(new Seen(cur.inv, cur.remaining)))
                continue;
            expanded++;
            for (int j = 0; j < s.patternCount(); j++) {
                if (cur.remaining[j].signum() <= 0)
                    continue;
                Amount max = supportable(snap, s, j, cur.inv).min(cur.remaining[j]);
                if (max.signum() <= 0)
                    continue;
                for (Amount batch : max.compareTo(Amount.ONE) > 0 ? List.of(max, Amount.ONE) : List.of(max)) {
                    Amount[] inv = cur.inv.clone();
                    Amount[] rem = cur.remaining.clone();
                    fire(snap, s, j, batch, inv);
                    rem[j] = rem[j].sub(batch);
                    open.add(new Node(inv, rem, cur.g + 1, heuristic(rem), cur, j, batch));
                }
            }
        }
        return null;
    }

    private static int heuristic(Amount[] remaining) {
        int h = 0;
        for (Amount r : remaining)
            if (r.signum() > 0)
                h++;
        return h;
    }

    private static CycleContract contract(Scc s, Node end) {
        List<Integer> pats = new ArrayList<>();
        List<Amount> batches = new ArrayList<>();
        for (Node n = end; n.parent != null; n = n.parent) {
            pats.add(0, s.pattern(n.via));
            batches.add(0, n.batch);
        }
        return new CycleContract(s.index, pats.stream().mapToInt(Integer::intValue).toArray(), batches.toArray(new Amount[0]));
    }
}
