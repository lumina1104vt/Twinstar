package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.ae2.PlanWrapper;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a twin-star plan into its {@link PlanGraph}: items in the order they are reached from the
 * target (target first), the plan's patterns, and its loops (strongly connected items: made, in
 * the end, from themselves), with the seeds the plan starts each loop from.
 */
public final class PlanGraphBuilder {
    /** items and recipes sent at most each */
    public static final int MAX_NODES = 400;

    private PlanGraphBuilder() {
    }

    public static PlanGraph build(PlanWrapper pw) {
        return build(pw.plan(), pw.snapshot(), pw.index());
    }

    public static PlanGraph build(Plan plan, Snapshot snap, ItemIndex index) {
        boolean truncated = false;

        Map<Integer, List<Integer>> producedBy = new TreeMap<>();
        for (int p : plan.patternTimes.keys()) {
            Pattern pat = snap.pattern(p);
            if (pat != null)
                for (int o = 0; o < pat.outputCount(); o++)
                    producedBy.computeIfAbsent(pat.outputItem(o), k -> new ArrayList<>()).add(p);
        }

        // items from the target down, then the plan's patterns in the order their outputs were reached
        Map<Integer, Integer> itemAt = new LinkedHashMap<>();       // engine item id → graph index
        Map<Integer, Integer> recipeAt = new LinkedHashMap<>();     // pattern id → graph index
        Deque<Integer> queue = new ArrayDeque<>();
        itemAt.put(plan.target, 0);
        queue.add(plan.target);
        while (!queue.isEmpty()) {
            int x = queue.poll();
            for (int p : producedBy.getOrDefault(x, List.of())) {
                if (recipeAt.containsKey(p))
                    continue;
                if (recipeAt.size() >= MAX_NODES) {
                    truncated = true;
                    continue;
                }
                recipeAt.put(p, recipeAt.size());
                Pattern pat = snap.pattern(p);
                for (int s = 0; s < pat.inputCount(); s++)
                    truncated |= reach(pat.inputItem(s), itemAt, queue);
                for (int o = 0; o < pat.outputCount(); o++)
                    truncated |= reach(pat.outputItem(o), itemAt, queue);
            }
        }

        // flows per recipe, and the item graph (product → material) for the loops
        int n = itemAt.size();
        List<List<Integer>> materials = new ArrayList<>();
        for (int i = 0; i < n; i++)
            materials.add(new ArrayList<>());
        List<List<PlanGraph.Flow>> ins = new ArrayList<>(), outs = new ArrayList<>();
        for (int p : recipeAt.keySet()) {
            Pattern pat = snap.pattern(p);
            BigInteger runs = plan.patternTimes.get(p).bigValue();
            Map<Long, BigInteger> in = new LinkedHashMap<>();       // (item, kind) → total
            for (int s = 0; s < pat.inputCount(); s++) {
                Integer y = itemAt.get(pat.inputItem(s));
                if (y == null)
                    continue;
                InputKind kind = pat.inputKind(s);
                BigInteger each = pat.inputAmount(s).bigValue();
                BigInteger total = switch (kind) {
                    case CONSUME -> each.multiply(runs);
                    case CATALYST, CONTAINER -> each;
                    case TOOL -> pat.inputDurability(s) > 0
                            ? each.multiply(ceilDiv(runs, BigInteger.valueOf(pat.inputDurability(s))))
                            : each.multiply(runs);
                };
                in.merge(((long) y << 8) | kind.ordinal(), total, BigInteger::add);
            }
            Map<Long, BigInteger> out = new LinkedHashMap<>();
            for (int o = 0; o < pat.outputCount(); o++) {
                Integer x = itemAt.get(pat.outputItem(o));
                if (x != null)
                    out.merge(((long) x << 8) | (pat.isByproduct(o) ? 1 : 0),
                            pat.outputAmount(o).bigValue().multiply(runs), BigInteger::add);
            }
            ins.add(flows(in));
            outs.add(flows(out));
            for (long xo : out.keySet())
                for (long yi : in.keySet())
                    materials.get((int) (xo >> 8)).add((int) (yi >> 8));
        }

        int[] loopOf = loops(materials);
        int loopCount = Arrays.stream(loopOf).max().orElse(-1) + 1;

        // seeds: the plan's per-cycle seeds and the recycled items, on the loop of the item
        List<List<PlanGraph.Flow>> seeds = new ArrayList<>();
        for (int l = 0; l < loopCount; l++)
            seeds.add(new ArrayList<>());
        plan.loopSeeds.values().forEach(m -> m.forEach((id, a) -> {
            Integer x = itemAt.get(id);
            if (x != null && loopOf[x] >= 0 && a.signum() > 0)
                seeds.get(loopOf[x]).add(new PlanGraph.Flow(x, PlanTree.text(a), 0));
        }));
        for (Plan.Recycle r : plan.recycles) {
            Integer x = itemAt.get(r.item());
            if (x != null && loopOf[x] >= 0 && r.seed().signum() > 0)
                seeds.get(loopOf[x]).add(new PlanGraph.Flow(x, PlanTree.text(r.seed()), 0));
        }

        List<PlanGraph.Item> items = new ArrayList<>(n);
        for (Map.Entry<Integer, Integer> e : itemAt.entrySet())
            items.add(item(plan, snap, index, producedBy, e.getKey(), loopOf[e.getValue()]));

        List<PlanGraph.Recipe> recipes = new ArrayList<>(recipeAt.size());
        int r = 0;
        for (int p : recipeAt.keySet()) {
            Pattern pat = snap.pattern(p);
            int loop = -1;
            for (PlanGraph.Flow o : outs.get(r))
                for (PlanGraph.Flow i : ins.get(r))
                    if (loop < 0 && loopOf[o.item()] >= 0 && loopOf[o.item()] == loopOf[i.item()])
                        loop = loopOf[o.item()];
            Integer missingMachine = plan.missingMachines.get(p);
            int machineId = pat.machine >= 0 ? pat.machine : missingMachine != null ? missingMachine : -1;
            AEKey machine = machineId >= 0 ? index.keyOf(machineId) : null;
            recipes.add(new PlanGraph.Recipe(PlanTree.text(plan.patternTimes.get(p)), machine, missingMachine != null,
                    loop, ins.get(r), outs.get(r)));
            r++;
        }

        List<PlanGraph.Loop> loops = new ArrayList<>(loopCount);
        for (List<PlanGraph.Flow> s : seeds)
            loops.add(new PlanGraph.Loop(s));
        return new PlanGraph(items, recipes, loops, truncated);
    }

    /** Adds the item if it is new; true if it had to be left out. */
    private static boolean reach(int id, Map<Integer, Integer> itemAt, Deque<Integer> queue) {
        if (itemAt.containsKey(id))
            return false;
        if (itemAt.size() >= MAX_NODES)
            return true;
        itemAt.put(id, itemAt.size());
        queue.add(id);
        return false;
    }

    private static PlanGraph.Item item(Plan plan, Snapshot snap, ItemIndex index, Map<Integer, List<Integer>> producedBy,
                                       int x, int loop) {
        BigInteger produced = BigInteger.ZERO;
        for (int p : producedBy.getOrDefault(x, List.of()))
            produced = produced.add(plan.patternTimes.get(p).bigValue().multiply(snap.pattern(p).outputAmountOf(x).bigValue()));
        BigInteger pulled = plan.pulled.get(x).bigValue();
        BigInteger later = plan.drawLater.get(x).bigValue();
        BigInteger missing = plan.missing.get(x).bigValue();
        BigInteger leftover = plan.leftover.get(x).bigValue();
        BigInteger used = pulled.add(missing).add(produced).subtract(leftover).max(BigInteger.ZERO);
        return new PlanGraph.Item(index.keyOf(x), PlanTree.text(used), PlanTree.text(produced),
                PlanTree.text(pulled.subtract(later)), later.signum() > 0, PlanTree.text(missing), PlanTree.text(leftover), loop);
    }

    private static List<PlanGraph.Flow> flows(Map<Long, BigInteger> m) {
        List<PlanGraph.Flow> flows = new ArrayList<>(m.size());
        m.forEach((k, v) -> flows.add(new PlanGraph.Flow((int) (k >> 8), PlanTree.text(v), (int) (k & 0xFF))));
        return flows;
    }

    private static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        BigInteger[] qr = a.divideAndRemainder(b);
        return qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
    }

    /**
     * Loop index of each item, −1 if it is in none: Tarjan's strongly connected components of the
     * product → material graph, keeping those of more than one item or with an item made from itself.
     */
    public static int[] loops(List<List<Integer>> materials) {
        int n = materials.size();
        int[] order = new int[n], low = new int[n], loopOf = new int[n];
        Arrays.fill(order, -1);
        Arrays.fill(loopOf, -1);
        boolean[] onStack = new boolean[n];
        Deque<Integer> stack = new ArrayDeque<>();
        int[] counter = {0, 0};     // visit order, loops found
        Map<Integer, Integer> next = new HashMap<>();   // iterative DFS: the next edge of each open item
        for (int root = 0; root < n; root++) {
            if (order[root] >= 0)
                continue;
            Deque<Integer> dfs = new ArrayDeque<>();
            dfs.push(root);
            order[root] = low[root] = counter[0]++;
            stack.push(root);
            onStack[root] = true;
            while (!dfs.isEmpty()) {
                int v = dfs.peek();
                int e = next.getOrDefault(v, 0);
                List<Integer> out = materials.get(v);
                if (e < out.size()) {
                    next.put(v, e + 1);
                    int w = out.get(e);
                    if (order[w] < 0) {
                        order[w] = low[w] = counter[0]++;
                        stack.push(w);
                        onStack[w] = true;
                        dfs.push(w);
                    } else if (onStack[w])
                        low[v] = Math.min(low[v], order[w]);
                    continue;
                }
                dfs.pop();
                if (!dfs.isEmpty())
                    low[dfs.peek()] = Math.min(low[dfs.peek()], low[v]);
                if (low[v] != order[v])
                    continue;
                List<Integer> component = new ArrayList<>();
                int w;
                do {
                    w = stack.pop();
                    onStack[w] = false;
                    component.add(w);
                } while (w != v);
                if (component.size() > 1 || materials.get(v).contains(v)) {
                    for (int c : component)
                        loopOf[c] = counter[1];
                    counter[1]++;
                }
            }
        }
        return loopOf;
    }
}
