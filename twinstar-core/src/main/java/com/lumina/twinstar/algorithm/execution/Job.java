package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.key.Key;
import com.lumina.twinstar.algorithm.cache.Scc;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Mem;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.CycleContract;
import com.lumina.twinstar.algorithm.planning.Plan;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * One running job. Everything it needs was pulled into its own isolated inventory at submit, so
 * jobs never fight over network stock and never re-plan. Progress is a set of tasks peeled off when
 * their runs reach zero; readiness is driven by inventory changes.
 */
public final class Job {
    public final long id;
    public final Plan plan;
    public final Snapshot snap;
    public final long startNanos;

    public final AmountMap inventory = new AmountMap();
    public final List<Task> tasks = new ArrayList<>();
    /** ready tasks, low level first so hub-internal chains finish within one tick */
    public final PriorityQueue<Task> ready = new PriorityQueue<>(
            Comparator.<Task>comparingInt(t -> t.level).thenComparingInt(t -> t.pattern));
    public final Map<Integer, List<Task>> waitingOn = new HashMap<>();
    /** tasks with materials but no free machine (or no power): tried again next tick */
    public final List<Task> retry = new ArrayList<>();
    /** expected returns: pushed to machines, not yet taken from the return pool */
    public final AmountMap outstanding = new AmountMap();
    /** last time each expected item decreased (or was first expected): stuck detection */
    public final Map<Integer, Long> outstandingSince = new HashMap<>();
    public final Map<Integer, StuckMark> stuck = new TreeMap<>();
    /** black-hole capacity still held (AE2's bytes); released as items leave the job and runs are done */
    public Amount occupancy;
    /** keys counted in fractions of a byte (fluids): amounts released so far and the whole bytes they made */
    public final AmountMap releasedUnits = new AmountMap();
    public final AmountMap releasedBytes = new AmountMap();
    public Amount delivered = Amount.ZERO;

    /** seeds of loops still running: other tasks may not eat them */
    public final AmountMap protectedSeeds = new AmountMap();
    public final Map<Integer, AmountMap> sccSeeds = new HashMap<>();
    /** seeds of items the plan uses again through a loop (Plan.recycles), still held for their loop */
    public final List<Plan.Recycle> recycleGuards = new ArrayList<>();
    public final Map<Integer, Integer> contractStep = new HashMap<>();
    public final Map<Integer, Amount> contractLeft = new HashMap<>();
    /** durability tools: runs used so far per tool item */
    public final AmountMap toolUses = new AmountMap();
    /** infinite-source items still to be drawn from the network as the job needs them */
    public final AmountMap drawLater = new AmountMap();
    /** patterns run for outputs that come with a probability (Plan.chanceNeeds); key = pattern */
    public final Map<Integer, ChanceProgress> chances = new HashMap<>();
    /**
     * what such patterns did not give after all their planned runs: the job waits for it (expected
     * like a machine's return, so a late arrival, a player's insertion or "pull from network" fills it)
     */
    public final AmountMap chanceShort = new AmountMap();
    /** runs per pattern lost with a machine that was taken away (its products are no longer waited for) */
    public final AmountMap machineLost = new AmountMap();
    /** patterns that had work but nobody offered them at the last try (等樣板) */
    public final java.util.Set<Integer> waitingPatterns = new java.util.HashSet<>();

    /**
     * A forced order (started although the plan lacked materials): what the job still lacks from the network. It is taken
     * from network storage as it appears; the job is not done while any is lacking.
     */
    public final AmountMap shortage = new AmountMap();
    /** started as a forced order */
    public boolean forced;
    /** cancelled because a forced order waited too long for its materials */
    public boolean timedOut;
    /** last time (nanos) the job made progress: ran a task, got a return or a material; for the forced order's time limit */
    public long lastProgress;
    /** last time a forced order looked in network storage for what it lacks */
    public long lastTopUp;
    private java.util.Set<Integer> virtualItems;

    /** Items of this job's patterns that are only marks (see {@link Pattern#isVirtualOutput}). */
    public java.util.Set<Integer> virtualItems() {
        if (virtualItems == null) {
            java.util.Set<Integer> set = new java.util.HashSet<>();
            for (Task t : tasks) {
                Pattern p = snap.pattern(t.pattern);
                if (p != null)
                    for (int item : p.virtualOutputItems())
                        set.add(item);
            }
            virtualItems = set.isEmpty() ? java.util.Set.of() : set;
        }
        return virtualItems;
    }

    public boolean suspended;
    public boolean unloaded;
    public boolean cancelled;
    public boolean finished;
    /** Where finished products go first (the requester behind AE2's crafting link); the rest goes to storage. */
    public @Nullable FinalSink sink;
    /** The player who ordered it, told when it is done; null when nobody did (an automated request). */
    public @Nullable java.util.UUID player;

    /** Takes up to {@code amount} of a finished product; returns how much it took. */
    @FunctionalInterface
    public interface FinalSink {
        long insert(Key key, long amount);
    }

    public void setFinalSink(@Nullable FinalSink sink) {
        this.sink = sink;
    }

    public Job(long id, Plan plan, Snapshot snap, long nowNanos) {
        this.id = id;
        this.plan = plan;
        this.snap = snap;
        this.startNanos = nowNanos;
        this.occupancy = plan.usage;
        plan.pulled.forEach(inventory::add);
        plan.drawLater.forEach((item, a) -> {
            inventory.sub(item, a);     // not pulled at submit: drawn while running
            drawLater.add(item, a);
        });
        plan.patternTimes.forEach((p, runs) -> {
            Pattern pat = snap.pattern(p);
            int level = 0;
            int s = -1;
            for (int o = 0; o < pat.outputCount(); o++) {
                level = Math.max(level, snap.level(pat.outputItem(o)));
                int so = snap.scc(pat.outputItem(o));
                if (so != -1 && inLoop(snap.sccAt(so), p))
                    s = so;
            }
            tasks.add(new Task(p, level, s, runs));
        });
        for (Task t : tasks)
            if (t.scc != -1 && !sccSeeds.containsKey(t.scc)) {
                Scc scc = snap.sccAt(t.scc);
                AmountMap planned = plan.loopSeeds.get(t.scc);
                AmountMap seeds = planned != null ? planned.copy() : new AmountMap();
                if (planned == null)
                    for (int i = 0; i < scc.seedCount(); i++)
                        seeds.add(scc.seedItem(i), scc.seedAmount(i));
                sccSeeds.put(t.scc, seeds);
                seeds.forEach(protectedSeeds::add);
            }
        for (Plan.Recycle r : plan.recycles) {
            recycleGuards.add(r);
            protectedSeeds.add(r.item(), r.seed());
        }
        plan.chanceNeeds.forEach((p, need) -> chances.put(p, new ChanceProgress(need)));
        for (Map.Entry<Integer, CycleContract> e : plan.contracts.entrySet()) {
            contractStep.put(e.getKey(), 0);
            contractLeft.put(e.getKey(), e.getValue().batches()[0]);
        }
    }

    /** A job read back from a save: everything but the plan is filled in by {@link JobStore}. */
    public Job(long id, Plan plan, Snapshot snap, long startNanos, Amount occupancy) {
        this.id = id;
        this.plan = plan;
        this.snap = snap;
        this.startNanos = startNanos;
        this.occupancy = occupancy;
    }

    private static boolean inLoop(Scc s, int p) {
        for (int j = 0; j < s.patternCount(); j++)
            if (s.pattern(j) == p)
                return true;
        return false;
    }

    // ── Java memory (estimated structure by structure, see Mem) ──

    /**
     * Java memory of this job's own data: its inventory and the other amount maps, tasks, queues and
     * the plan it runs. The theory version it runs on is shared and not counted (see
     * {@link com.lumina.twinstar.algorithm.cache.Snapshot#memory}).
     */
    public long memoryBytes() {
        long b = Mem.obj(8 + 4 * 5 + 4 * 30 + 4);
        b += inventory.memoryBytes() + outstanding.memoryBytes() + releasedUnits.memoryBytes() + releasedBytes.memoryBytes()
                + protectedSeeds.memoryBytes() + drawLater.memoryBytes() + chanceShort.memoryBytes() + machineLost.memoryBytes() + shortage.memoryBytes()
                + toolUses.memoryBytes() + occupancy.memoryBytes() + delivered.memoryBytes();
        b += Mem.arrayList(tasks.size()) + tasks.size() * Mem.obj(3 * 4 + 2 * 4 + 1);
        for (Task t : tasks)
            b += t.remaining.memoryBytes() + t.inFlight.memoryBytes();
        b += Mem.priorityQueue(ready.size()) + Mem.arrayList(retry.size());
        b += Mem.hashMap(waitingOn.size());
        for (Map.Entry<Integer, List<Task>> e : waitingOn.entrySet())
            b += Mem.boxedInt(e.getKey()) + Mem.arrayList(e.getValue().size());
        b += Mem.hashMapOfLongs(outstandingSince);
        b += Mem.treeMap(stuck.size()) + Mem.boxedKeys(stuck.keySet()) + stuck.size() * Mem.obj(16);
        b += Mem.hashMap(sccSeeds.size());
        for (Map.Entry<Integer, AmountMap> e : sccSeeds.entrySet())
            b += Mem.boxedInt(e.getKey()) + e.getValue().memoryBytes();
        b += Mem.arrayList(recycleGuards.size());      // the guards are the plan's own recycles
        b += Mem.hashMapOfInts(contractStep) + Mem.hashMapOfAmounts(contractLeft);
        b += Mem.hashMap(chances.size());
        for (Map.Entry<Integer, ChanceProgress> e : chances.entrySet())
            b += Mem.boxedInt(e.getKey()) + e.getValue().memoryBytes();
        b += Mem.hashMap(waitingPatterns.size()) + Mem.boxedKeys(waitingPatterns);
        return b + plan.memoryBytes();
    }

    /** Sets the bit of every kind of item this job tracks right now (its inventory, expected returns, seeds, tools, drawn-later items). */
    public void markTrackedItems(java.util.BitSet into) {
        inventory.markKeys(into);
        outstanding.markKeys(into);
        drawLater.markKeys(into);
        protectedSeeds.markKeys(into);
        toolUses.markKeys(into);
        chanceShort.markKeys(into);
        shortage.markKeys(into);
    }

    // ── read access for the CPU screen ──

    public Snapshot snapshot() {
        return snap;
    }

    public AmountMap inventory() {
        return inventory.copy();
    }

    public AmountMap outstanding() {
        return outstanding.copy();
    }

    /** Infinite-source items still to be drawn as the job runs. */
    public AmountMap drawLater() {
        return drawLater.copy();
    }

    public List<Task> tasks() {
        return Collections.unmodifiableList(tasks);
    }

    /** 機率產物不足: per item, what the probable outputs did not give after all the planned runs. */
    /** 缺少的材料: what a forced order still lacks from the network. */
    public AmountMap shortage() {
        return shortage.copy();
    }

    /** Started although the plan lacked materials (a forced order). */
    public boolean isForced() {
        return forced;
    }

    /** A forced order cancelled because it waited too long. */
    public boolean isTimedOut() {
        return timedOut;
    }

    public AmountMap chanceShortages() {
        return chanceShort.copy();
    }

    /** Per pattern: runs whose materials stayed in a machine that was taken away. */
    public AmountMap machineLosses() {
        return machineLost.copy();
    }

    /** Patterns waiting for someone to offer them again (taken out, or a restored job's pattern is gone). */
    public java.util.Set<Integer> waitingPatterns() {
        return java.util.Set.copyOf(waitingPatterns);
    }

    public List<StuckMark> stuckMarks() {
        return List.copyOf(stuck.values());
    }

    public Amount occupancy() {
        return occupancy;
    }

    public Amount delivered() {
        return delivered;
    }

    public long startNanos() {
        return startNanos;
    }

    public boolean isSuspended() {
        return suspended;
    }

    /** Pause: capacity stays held, returns still go to the pool, nothing runs, the stuck timer stops. */
    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }

    /** The hub stopped while things were out: some returns may have gone into network storage. */
    public boolean wasUnloaded() {
        return unloaded;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
