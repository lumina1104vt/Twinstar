package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.ae2.PlanWrapper;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.corelib.util.BigAmountFormat;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Turns a twin-star plan into the plan screen's tree (as EngineLog's crafting tree, for players). */
public final class PlanTree {
    /** rows sent at most; a pack's deepest plans stay well below */
    public static final int MAX_ROWS = 400;

    private PlanTree() {
    }

    public static PlanView build(PlanWrapper pw, long planNanos, @Nullable BigInteger freeCapacity, @Nullable BigInteger parallel) {
        Plan plan = pw.plan();
        Snapshot snap = pw.snapshot();
        ItemIndex index = pw.index();

        // which patterns of the plan make each item
        Map<Integer, List<Integer>> producedBy = new TreeMap<>();
        plan.patternTimes.forEach((p, runs) -> {
            Pattern pat = snap.pattern(p);
            if (pat != null)
                for (int o = 0; o < pat.outputCount(); o++)
                    producedBy.computeIfAbsent(pat.outputItem(o), k -> new ArrayList<>()).add(p);
        });

        List<PlanView.Row> rows = new ArrayList<>();
        boolean[] truncated = {false};
        node(plan, snap, index, producedBy, plan.target, 0, new HashSet<>(), rows, truncated);

        List<PlanView.Missing> missing = new ArrayList<>();
        // machines first, each once
        new LinkedHashSet<>(plan.missingMachines.values()).forEach(m -> {
            AEKey key = index.keyOf(m);
            if (key != null && missing.size() < MAX_ROWS)
                missing.add(new PlanView.Missing(key, "", true));
        });
        plan.missing.forEach((id, a) -> {
            AEKey key = index.keyOf(id);
            if (key != null && missing.size() < MAX_ROWS)
                missing.add(new PlanView.Missing(key, text(a.bigValue()), false));
        });

        AEKey target = index.keyOf(plan.target);
        return new PlanView(target, text(plan.amount.bigValue()), plan.patternTimes.size(), planNanos,
                text(plan.usage.bigValue()), freeCapacity == null ? PlanView.UNLIMITED : text(freeCapacity),
                parallel == null ? PlanView.UNLIMITED : text(parallel), plan.isComplete(),
                freeCapacity == null || plan.usage.bigValue().compareTo(freeCapacity) <= 0, rows, truncated[0], missing,
                PlanGraphBuilder.build(pw));
    }

    private static void node(Plan plan, Snapshot snap, ItemIndex index, Map<Integer, List<Integer>> producedBy, int x,
                             int depth, Set<Integer> listed, List<PlanView.Row> rows, boolean[] truncated) {
        if (rows.size() >= MAX_ROWS) {
            truncated[0] = true;
            return;
        }
        AEKey key = index.keyOf(x);
        if (key == null)
            return;
        List<Integer> ps = producedBy.getOrDefault(x, List.of());
        BigInteger runs = BigInteger.ZERO;
        BigInteger produced = BigInteger.ZERO;
        for (int p : ps) {
            BigInteger r = plan.patternTimes.get(p).bigValue();
            runs = runs.add(r);
            produced = produced.add(r.multiply(snap.pattern(p).outputAmountOf(x).bigValue()));
        }
        BigInteger pulled = plan.pulled.get(x).bigValue();
        BigInteger later = plan.drawLater.get(x).bigValue();
        BigInteger missing = plan.missing.get(x).bigValue();
        BigInteger used = pulled.add(missing).add(produced).subtract(plan.leftover.get(x).bigValue()).max(BigInteger.ZERO);
        boolean shared = !ps.isEmpty() && !listed.add(x);
        AEKey machine = null;
        for (int p : ps)
            if (machine == null && plan.missingMachines.containsKey(p))
                machine = index.keyOf(plan.missingMachines.get(p));
        rows.add(new PlanView.Row(depth, key, text(used), shared ? "" : text(runs),
                text(pulled.subtract(later)), later.signum() > 0, text(missing), shared, machine));
        if (shared)
            return;     // its inputs are listed under its first appearance
        LinkedHashSet<Integer> inputs = new LinkedHashSet<>();
        for (int p : ps) {
            Pattern pat = snap.pattern(p);
            for (int s = 0; s < pat.inputCount(); s++)
                if (pat.inputItem(s) != x)
                    inputs.add(pat.inputItem(s));
        }
        for (int y : inputs)
            node(plan, snap, index, producedBy, y, depth + 1, listed, rows, truncated);
    }

    /** Display text: plain digits up to a long (the client groups them), unit form beyond, "" for zero. */
    static String text(BigInteger v) {
        if (v.signum() <= 0)
            return "";
        return BigAmountFormat.fitsLong(v) ? v.toString() : BigAmountFormat.unitForm(v);
    }

    static String text(Amount a) {
        return text(a.bigValue());
    }
}
