package com.lumina.twinstar.algorithm.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Our {@link Plan} in the shape AE2's confirm screen understands (CraftConfirmMenu and
 * CraftingPlanSummary only use ICraftingPlan). AE2 only knows longs: amounts beyond that saturate
 * here, the real plan keeps the exact values. Build on the main thread.
 */
public final class PlanWrapper implements ICraftingPlan {
    private final Plan plan;
    private final Snapshot snapshot;
    private final ItemIndex index;
    private final GenericStack finalOutput;
    private final KeyCounter used = new KeyCounter();
    private final KeyCounter emitted = new KeyCounter();
    private final KeyCounter missing = new KeyCounter();
    private final Map<IPatternDetails, Long> patternTimes = new LinkedHashMap<>();

    public PlanWrapper(Plan plan, Snapshot snap, ItemIndex index) {
        this.plan = plan;
        this.snapshot = snap;
        this.index = index;
        this.finalOutput = new GenericStack(index.keyOf(plan.target), plan.amount.saturatedLong());
        fill(used, plan.pulled, index);
        fill(missing, plan.missing, index);
        plan.patternTimes.forEach((p, runs) -> {
            Pattern pat = snap.pattern(p);
            if (pat != null && pat.source() instanceof IPatternDetails d)
                patternTimes.merge(d, runs.saturatedLong(), PlanWrapper::saturatedAdd);
        });
    }

    private static void fill(KeyCounter out, AmountMap from, ItemIndex index) {
        from.forEach((id, amount) -> {
            AEKey key = index.keyOf(id);
            if (key != null)
                out.add(key, amount.saturatedLong());
        });
    }

    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        return ((a ^ r) & (b ^ r)) < 0 ? Long.MAX_VALUE : r;
    }

    public Plan plan() {
        return plan;
    }

    public ItemIndex index() {
        return index;
    }

    /** The theory version the plan was made on; the job keeps it (patterns may change meanwhile). */
    public Snapshot snapshot() {
        return snapshot;
    }

    @Override
    public GenericStack finalOutput() {
        return finalOutput;
    }

    @Override
    public long bytes() {
        return plan.usage.saturatedLong();
    }

    @Override
    public boolean simulation() {
        return !plan.isComplete();
    }

    @Override
    public boolean multiplePaths() {
        return false;
    }

    @Override
    public KeyCounter usedItems() {
        return used;
    }

    @Override
    public KeyCounter emittedItems() {
        return emitted;
    }

    @Override
    public KeyCounter missingItems() {
        return missing;
    }

    @Override
    public Map<IPatternDetails, Long> patternTimes() {
        return patternTimes;
    }

    /** Usage as an exact amount (AE2's {@link #bytes()} saturates). */
    public Amount usage() {
        return plan.usage;
    }
}
