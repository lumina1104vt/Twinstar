package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.key.Key;
import com.lumina.twinstar.port.PatternSite;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.ChanceSampler;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Mem;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.algorithm.planning.CycleContract;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.api.IMachine;
import com.lumina.bridge.Pos;
import com.lumina.bridge.Face;
import com.lumina.bridge.Place;
import com.lumina.twinstar.port.World;
import com.lumina.twinstar.metrics.Alloc;
import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.metrics.TickMeter;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.random.RandomGenerator;

/**
 * F — the executor, one per network (held by TwinStarService). Runs every job each tick, round
 * robin; each job gets the full white-hole parallelism P, the black-hole capacity is one pool shared
 * by all jobs and released as items leave them.
 */
public final class HubState {
    /** stuck threshold = max(median round trip × 4, 10 s); 60 s for machines without data (建議) */
    public static final int STUCK_RTT_FACTOR = 4;
    public static final long STUCK_MIN_NANOS = 10_000_000_000L;
    public static final long STUCK_DEFAULT_NANOS = 60_000_000_000L;

    final List<Job> jobs = new ArrayList<>();
    /** returns waiting inside the hub; other devices on the network cannot take them */
    final AmountMap pool = new AmountMap();
    Amount used = Amount.ZERO;
    private final Map<WindowKey, Window> windows = new LinkedHashMap<>();
    private int rotate;
    long nextJobId = 1;
    /** draws of the hub's own runs for outputs that come with a probability */
    private final RandomGenerator random = new SplittableRandom();
    /** the engine's detailed log (/twinstar debug), or null */
    private @Nullable Consumer<String> debug;

    /** Told of each job that is done (not cancelled), once. */
    private @Nullable Consumer<Job> onDone;

    public void setOnDone(@Nullable Consumer<Job> onDone) {
        this.onDone = onDone;
    }

    public void setDebugLog(@Nullable Consumer<String> debug) {
        this.debug = debug;
    }

    /** Told what the hub puts into a machine for its runs (the list of what machines use up). */
    @FunctionalInterface
    public interface UsageSink {
        void used(IMachine machine, Key key, long amount);
    }

    private @Nullable UsageSink usage;

    public void setUsage(@Nullable UsageSink usage) {
        this.usage = usage;
    }

    record WindowKey(PatternSite provider, int pattern) {
    }

    // ───────────────────────────── memory and speed ─────────────────────────────

    private final TickMeter tickMeter = new TickMeter();
    /** what {@link #sharedBytes} was when the last job ended; −1 before any job ended */
    private long idleResidual = -1;

    /**
     * Java memory of what the hub holds beside its jobs (see {@link Mem}): the return pool, the
     * adaptive windows with their timing samples, the machines it feeds and what is in flight.
     * Machines are rough (a fixed size per machine and per entry).
     */
    public long sharedBytes() {
        long b = Mem.obj(120) + pool.memoryBytes() + used.memoryBytes() + Mem.arrayList(jobs.size());
        b += Mem.hashMap(windows.size()) + windows.size() * 8L;
        for (Window w : windows.values())
            b += w.memoryBytes() + Mem.obj(8);
        for (MachineState m : machines.values())
            b += Mem.obj(120) + Mem.hashMap(m.inFlight.size()) + m.inFlight.size() * 64L
                    + Mem.hashMap(m.overflow.size()) + m.overflow.size() * 48L + Mem.hashMap(m.frontCells.size()) + m.frontCells.size() * 24L;
        b += Mem.hashMap(machines.size()) + machines.size() * 8L;
        b += Mem.hashMap(lostAt.size()) + Mem.hashMap(orphans.size()) + orphans.size() * 40L + Mem.hashMap(restoring.size());
        for (Map<Flight, Amount> m : lostAt.values())
            b += Mem.hashMap(m.size()) + m.size() * 64L;
        return b;
    }

    /**
     * What a CPU screen shows: sizes (estimated, nothing is walked more than once per call) and the
     * executor's speed over the last second.
     *
     * @param selected         the job of the CPU entry looked at, null for the idle entry
     * @param newest           the newest theory version (jobs on older ones keep them alive)
     * @param newestBytes      its estimated size (0 = unknown)
     */
    public HubReport report(@Nullable Job selected, @Nullable Snapshot newest, long newestBytes) {
        return JobMemory.report(jobs, selected, newest, newestBytes, sharedBytes(), idleResidual, tickMeter.last());
    }

    // ───────────────────────────── queries ─────────────────────────────

    public List<Job> jobs() {
        return Collections.unmodifiableList(jobs);
    }

    public Amount used() {
        return used;
    }

    public Amount freeCapacity(Amount capacity) {
        return capacity.sub(used).clampNonNegative();
    }

    /** For AE2's isRequesting / getRequestedAmount: what jobs still expect minus what already waits in the pool. */
    public Amount requestedAmount(int item) {
        Amount sum = Amount.ZERO;
        for (Job j : jobs)
            sum = sum.add(j.outstanding.get(item));
        return sum.sub(pool.get(item)).clampNonNegative();
    }

    public boolean isRequestingAny() {
        for (Job j : jobs)
            if (!j.outstanding.isEmpty())
                return true;
        return false;
    }

    // ───────────────────────────── submit ─────────────────────────────

    /**
     * Pulls everything the plan needs into a new job's own inventory, all or nothing. Null when the
     * network no longer has it: the caller re-plans once with fresh stock, then gives up.
     */
    public @Nullable Job submit(Plan plan, Snapshot snap, ExecutionEnv env) {
        if (!plan.isComplete())
            return null;
        // infinite sources are drawn while the job runs; everything else is pulled now
        AmountMap now = plan.pulled.copy();
        plan.drawLater.forEach(now::sub);
        for (Map.Entry<Integer, Amount> e : now.entries())
            if (env.index().key(e.getKey()) == null)
                return null;
        // taken for real and put back when short: big storages have no long-sized simulate to ask first
        AmountMap taken = new AmountMap();
        for (Map.Entry<Integer, Amount> e : now.entries()) {
            Key key = env.index().key(e.getKey());
            Amount got = env.store().extract(key, e.getValue());
            taken.add(e.getKey(), got);
            if (got.compareTo(e.getValue()) < 0) {
                taken.forEach((id, a) -> insertAll(env, id, a));
                return null;
            }
        }
        Job job = new Job(nextJobId++, plan, snap, env.nowNanos());
        used = used.add(job.occupancy);
        jobs.add(job);
        for (Task t : job.tasks)
            checkReady(job, t);
        return job;
    }

    // ───────────────────────────── forced orders ─────────────────────────────

    /** How often a forced job looks in network storage for what it still lacks. */
    private static final long TOPUP_NANOS = 1_000_000_000L;

    /** Jobs that still wait for materials they were started without. */
    public int forcedWaiting() {
        int n = 0;
        for (Job j : jobs)
            if (j.forced && !j.cancelled && !j.finished && waitsForMore(j))
                n++;
        return n;
    }

    /**
     * An order started although the plan lacks materials (or machines): takes what the network has of what the plan
     * pulls, remembers what is lacking ({@link Job#shortage}) and goes on with what can be made. The lacking
     * materials are taken from network storage as they appear; the job ends when everything has come and run, or
     * when it has made no progress for {@link ExecutionSettings#forcedIdleNanos()}, or when it is cancelled.
     * Null when too many forced jobs already wait ({@link ExecutionSettings#maxForcedJobs()}) or there is nothing to run.
     */
    public @Nullable Job submitForced(Plan plan, Snapshot snap, ExecutionEnv env) {
        if (plan.patternTimes.isEmpty())
            return null;
        if (forcedWaiting() >= ExecutionSettings.maxForcedJobs())
            return null;
        AmountMap now = plan.pulled.copy();
        plan.drawLater.forEach(now::sub);
        AmountMap lack = ForcedOrder.lacking(now, (item, want) -> extractFromNetwork(item, want, env));
        Job job = new Job(nextJobId++, plan, snap, env.nowNanos());
        lack.forEach((item, a) -> job.inventory.sub(item, a));
        lack.forEach(job.shortage::add);
        plan.missing.forEach(job.shortage::add);
        job.forced = true;
        job.lastProgress = env.nowNanos();
        used = used.add(job.occupancy);
        jobs.add(job);
        for (Task t : job.tasks)
            checkReady(job, t);
        return job;
    }

    /** A forced order lacks materials, or machines nothing is running in. */
    private static boolean waitsForMore(Job job) {
        return !job.shortage.isEmpty() || !job.plan.missingMachines.isEmpty() && job.outstanding.isEmpty();
    }

    /** A forced job takes what it lacks from network storage as it appears (once a second). */
    private void topUpShortage(Job job, ExecutionEnv env) {
        if (job.shortage.isEmpty() || job.suspended || env.nowNanos() - job.lastTopUp < TOPUP_NANOS)
            return;
        job.lastTopUp = env.nowNanos();
        if (ForcedOrder.topUp(job.shortage, (item, want) -> extractFromNetwork(item, want, env), job.inventory, item -> notifyItem(job, item)))
            job.lastProgress = env.nowNanos();
    }

    private Amount extractFromNetwork(int item, Amount want, ExecutionEnv env) {
        Key key = env.index().key(item);
        return key == null ? Amount.ZERO : env.store().extract(key, want);
    }

    /** A forced job that lacks materials and has done nothing for too long is cancelled (it gives everything back). */
    private boolean forcedExpired(Job job, ExecutionEnv env) {
        if (!job.forced || !waitsForMore(job) || job.suspended || job.cancelled)
            return false;
        return ForcedOrder.expired(true, env.nowNanos(), job.lastProgress, ExecutionSettings.forcedIdleNanos());
    }

    /**
     * Someone offers the pattern now: for a hub pattern the hub itself, for any other pattern any
     * provider. A pattern built from ids (tests) has no source and always counts as offered; a saved
     * pattern that could not be decoded again never does.
     */
    private static boolean offered(Pattern pat, ExecutionEnv env) {
        Object source = pat.source();
        if (source == null)
            return true;
        boolean hub = pat.where == PatternLocation.HUB_INTERNAL || pat.where == PatternLocation.HUB_MACHINE;
        for (PatternSite prov : env.providers().offering(source))
            if (!hub || prov.isHub())
                return true;
        return false;
    }

    // ───────────────────────────── save / load ─────────────────────────────

    /** A job read back from a save ({@link JobStore}): it holds its capacity again and its tasks look for work. */
    void restore(Job job, long nowNanos) {
        jobs.add(job);
        used = used.add(job.occupancy);
        nextJobId = Math.max(nextJobId, job.id + 1);
        job.lastProgress = nowNanos;
        for (int item : job.outstanding.keys())
            job.outstandingSince.put(item, nowNanos);
        for (Task t : job.tasks)
            checkReady(job, t);
    }

    /**
     * The hub is broken: every job ends and gives back what it holds (its inventory, the returns
     * waiting in the hub, products of machines the network had no room for) to network storage;
     * what does not fit goes to {@code left} (the block drops items; anything else is lost).
     */
    public void breakDown(ExecutionEnv env, java.util.function.BiConsumer<Key, Amount> left) {
        AmountMap rest = new AmountMap();
        for (Job job : jobs) {
            returnInventory(job, env);
            job.inventory.forEach(rest::add);
        }
        for (int item : pool.keys()) {
            Amount a = pool.get(item);
            rest.add(item, a.sub(insertAll(env, item, a)));
        }
        Map<Key, Long> loose = new LinkedHashMap<>(orphans);
        for (MachineState m : machines.values()) {
            m.overflow.forEach((k, n) -> loose.merge(k, n, Long::sum));
            feedLeft(m.feed, loose);
        }
        for (RestoredMachine r : restoring.values()) {
            r.overflow().forEach((k, n) -> loose.merge(k, n, Long::sum));
            feedLeft(r.feed(), loose);
        }
        loose.forEach((k, n) -> {
            Amount a = Amount.of(n);
            rest.add(env.index().idOf(k), a.sub(toNetwork(env, k, a)));
        });
        rest.forEach((item, a) -> {
            Key k = env.index().key(item);
            if (k != null && a.signum() > 0)
                left.accept(k, a);
        });
        forgetAll();
    }

    /** What a run being fed had not put in yet: it left its job and is not in the machine either. */
    static void feedLeft(@Nullable Feed feed, Map<Key, Long> into) {
        if (feed == null)
            return;
        for (Part p : feed.parts)
            if (p.left > 0)
                into.merge(p.key, p.left, Long::sum);
        // the tools of the run that had not been used yet
        for (Key tool : feed.tools)
            into.merge(tool, 1L, Long::sum);
    }

    /**
     * Anything a save has to keep: jobs, and items held for nobody in particular (returns, machine
     * products the network had no room for, runs being fed).
     */
    public boolean holdsAnything() {
        if (!jobs.isEmpty() || !pool.isEmpty() || !orphans.isEmpty())
            return true;
        for (MachineState m : machines.values())
            if (m.feed != null || !m.overflow.isEmpty())
                return true;
        for (RestoredMachine r : restoring.values())
            if (r.feed() != null || !r.overflow().isEmpty())
                return true;
        return false;
    }

    /**
     * Forgets every job and what machines hold for them, without giving anything back: they were
     * saved with the hub's node and come back with it (chunk unloaded, network split).
     */
    public void forgetAll() {
        jobs.clear();
        pool.clear();
        used = Amount.ZERO;
        machines.clear();
        lostAt.clear();
        orphans.clear();
        restoring.clear();
        windows.clear();
    }

    // ───────────────────────────── tick ─────────────────────────────

    public void hubTick(ExecutionEnv env) {
        long t0 = System.nanoTime();
        long a0 = Alloc.threadBytes();
        boolean active = !jobs.isEmpty();
        tick(env);
        tickMeter.record(active, System.nanoTime() - t0, Alloc.since(a0), System.nanoTime());
        if (active && jobs.isEmpty())
            idleResidual = sharedBytes();       // the last job is gone: what the hub still holds should be small
    }

    private void tick(ExecutionEnv env) {
        envNow = env;
        syncMachines(env);
        machineTick(env);
        flushUnclaimedPool(env);
        if (jobs.isEmpty())
            return;
        long deadline = System.nanoTime() + env.tickBudgetNanos();
        int n = jobs.size();
        for (int i = 0; i < n; i++) {
            Job job = jobs.get((rotate + i) % n);
            if (job.cancelled) {
                // a full network kept part of a cancelled job: keep trying to give it back
                if (!job.finished && returnInventory(job, env))
                    job.finished = true;
                continue;
            }
            pullReturns(job, env);      // returns are taken even while paused
            if (job.finished)
                continue;
            if (forcedExpired(job, env)) {
                job.timedOut = true;
                cancel(job, env);
                continue;
            }
            topUpShortage(job, env);
            if (!job.suspended)
                runJob(job, env, deadline);
            if (allDone(job))
                finish(job, env);
        }
        rotate++;
        checkStuck(env.nowNanos());
        jobs.removeIf(j -> j.finished);
    }

    /** Returns no job waits for any more (their job ended, or was not read back): into network storage. */
    private void flushUnclaimedPool(ExecutionEnv env) {
        for (int item : pool.keys()) {
            Amount wanted = Amount.ZERO;
            for (Job j : jobs)
                wanted = wanted.add(j.outstanding.get(item));
            Amount over = pool.get(item).sub(wanted);
            if (over.signum() > 0)
                pool.sub(item, insertAll(env, item, over));
        }
    }

    private void runJob(Job job, ExecutionEnv env, long deadline) {
        // runs this job may still send this tick, summed over all patterns; null = unlimited
        Amount p = env.parallelism();
        deliverFinals(job, env);
        // tasks that had their materials but no free machine or power last tick try again now
        List<Task> again = new ArrayList<>(job.retry);
        job.retry.clear();
        for (Task t : again)
            checkReady(job, t);
        while (!job.ready.isEmpty() && (p == null || p.signum() > 0) && System.nanoTime() < deadline) {
            Task t = job.ready.poll();
            t.queued = false;
            if (t.remaining.signum() <= 0)
                continue;
            Pattern pat = job.snap.pattern(t.pattern);
            Amount supported = supportable(job, t, pat, true);
            if (supported.signum() <= 0) {
                noteMaterials(job, t, pat);
                waitFor(job, t, pat);      // materials taken first: an inventory change wakes it
                continue;
            }
            Amount want = p == null ? t.remaining : t.remaining.min(p);
            ChanceProgress chance = job.chances.get(t.pattern);
            if (chance != null)
                want = want.min(chance.nextBatch(pat));     // no more than the rest needs on average
            Amount n = contractCap(job, t, want.min(supported));
            if (n.signum() <= 0)
                continue;                  // not this pattern's turn in the loop's contract; advancing it wakes it
            if (!offered(pat, env)) {
                // nobody offers the pattern now (taken out, or a saved job whose pattern is gone): it
                // waits, like AE2's own CPUs, until it is back or the job is cancelled
                if (job.waitingPatterns.add(t.pattern))
                    noteTask(t, pat, "等樣板（沒有供應器提供這張樣板）");
                retryNextTick(job, t);
                continue;
            }
            job.waitingPatterns.remove(t.pattern);
            n = energyCap(pat, n, env);
            if (n.signum() <= 0) {
                noteTask(t, pat, "能量不足，每次要 " + pat.energyPerRun.toDoubleSaturated() + " AE");
                retryNextTick(job, t);     // no power right now
                continue;
            }
            if (!job.drawLater.isEmpty()) {
                drawLazily(job, pat, n, env);
                n = n.min(supportable(job, t, pat, false));
                if (n.signum() <= 0) {
                    noteTask(t, pat, "無限來源沒拿到：" + lazyStatus(job, pat, env));
                    retryNextTick(job, t); // the infinite source did not deliver this tick
                    continue;
                }
            }
            if (pat.where == PatternLocation.HUB_INTERNAL) {
                consume(job, pat, n);
                produce(job, pat, n, chance);   // later tasks may become ready within this tick
            } else {
                Amount asked = n;
                long dispatchStart = System.nanoTime();
                n = pat.where == PatternLocation.HUB_MACHINE ? dispatchMachines(job, t, pat, n, chance, env)
                        : dispatchEven(job, t, pat, n, env);
                // a dispatch of tens of thousands of runs can take longer than the whole tick budget: the tasks behind it
                // would never be reached, so it goes last next tick (and is not asked again in this one)
                t.heavy = System.nanoTime() - dispatchStart > env.tickBudgetNanos();
                if (debug != null && pat.where == PatternLocation.HUB_MACHINE) {
                    String line = "[雙星] 機器派送 " + nameOf(pat, env) + "：樣板剩 " + t.remaining + " 次、材料夠 " + supported
                            + " 次、並行與能量後 " + asked + " 次 → 送出 " + n + " 次（" + lastDispatchNote + "）";
                    // a machine that stays full would log the same line every tick
                    if (n.signum() > 0 || !line.equals(lastDebugLine.put(t, line)))
                        debug.accept(line);
                }
                if (n.signum() <= 0) {
                    // materials are here but every machine is busy or refused: no inventory change
                    // will wake this task, so it must try again next tick
                    retryNextTick(job, t);
                    continue;
                }
            }
            payEnergy(pat, n, env);
            job.lastProgress = env.nowNanos();
            t.remaining = t.remaining.sub(n);
            release(job, n);            // one byte per run
            if (chance != null && pat.where == PatternLocation.HUB_INTERNAL)
                chanceCheck(job, t, chance, env.nowNanos());
            if (p != null)
                p = p.sub(n);
            advanceContract(job, t, n);
            if (t.scc != -1)
                releaseSeedsIfLoopDone(job, t.scc);
            releaseRecycleIfLoopDone(job, t.pattern);
            if (t.remaining.signum() > 0) {
                if (t.heavy)
                    retryNextTick(job, t);
                else
                    checkReady(job, t);
            }
            deliverFinals(job, env);    // finished products go out batch by batch
        }
    }

    private static void retryNextTick(Job job, Task t) {
        if (!job.retry.contains(t))
            job.retry.add(t);
    }

    private boolean allDone(Job job) {
        for (Task t : job.tasks)
            if (t.remaining.signum() > 0)
                return false;
        return job.outstanding.isEmpty() && job.shortage.isEmpty() && !feeding(job);
    }

    // ───────────────────────────── readiness ─────────────────────────────

    private void checkReady(Job job, Task t) {
        if (t.queued || t.remaining.signum() <= 0)
            return;
        Pattern pat = job.snap.pattern(t.pattern);
        if (supportable(job, t, pat, true).signum() > 0) {
            t.queued = true;
            job.ready.add(t);
        } else {
            noteMaterials(job, t, pat);
            waitFor(job, t, pat);
        }
    }

    private void waitFor(Job job, Task t, Pattern pat) {
        for (int s = 0; s < pat.inputCount(); s++) {
            List<Task> l = job.waitingOn.computeIfAbsent(pat.inputItem(s), k -> new ArrayList<>());
            if (!l.contains(t))
                l.add(t);
            for (int a = 0; a < pat.alternativeCount(s); a++) {
                List<Task> la = job.waitingOn.computeIfAbsent(pat.alternative(s, a), k -> new ArrayList<>());
                if (!la.contains(t))
                    la.add(t);
            }
        }
    }

    private void notifyItem(Job job, int item) {
        List<Task> waiting = job.waitingOn.remove(item);
        if (waiting != null)
            for (Task t : waiting)
                checkReady(job, t);
    }

    /**
     * Runs the job's stock supports (seeds of running loops are off limits to everyone else).
     * {@code withLater}: count infinite-source items not drawn yet as available.
     */
    private Amount supportable(Job job, Task t, Pattern pat, boolean withLater) {
        Amount max = null;
        for (int s = 0; s < pat.inputCount(); s++) {
            Amount have = usable(job, t, pat.inputItem(s), withLater);
            if (pat.allowSubst)
                for (int a = 0; a < pat.alternativeCount(s); a++)
                    have = have.add(usable(job, t, pat.alternative(s, a), withLater));
            Amount lim;
            switch (pat.inputKind(s)) {
                case CONSUME -> lim = have.div(totalUse(pat, s));
                case TOOL -> {
                    int dur = pat.inputDurability(s);
                    if (dur <= 0)
                        lim = have.div(pat.inputAmount(s));
                    else {
                        Amount usedRuns = job.toolUses.get(pat.inputItem(s));
                        Amount partLeft = usedRuns.ceilDiv(dur).mul(dur).sub(usedRuns);   // runs left on the tool in use
                        lim = have.mul(dur).add(partLeft);
                    }
                }
                default -> {
                    if (pat.where != PatternLocation.HUB_INTERNAL && pat.inputRole(s) == InputRoles.CATALYST && keptHeld(job, t.pattern))
                        continue;   // already in a machine for this batch: the next runs need no copy
                    if (pat.where != PatternLocation.HUB_INTERNAL && pat.inputRole(s) == InputRoles.TOOL && envNow != null)
                        have = have.add(wornTotal(job, pat.inputItem(s), envNow));
                    if (pat.where != PatternLocation.HUB_INTERNAL)
                        lim = have.div(pat.inputAmount(s));   // sent along and expected back
                    else if (have.compareTo(pat.inputAmount(s)) < 0)
                        return Amount.ZERO;
                    else
                        continue;
                }
            }
            max = max == null ? lim : max.min(lim);
        }
        return max == null ? UNBOUNDED : max.clampNonNegative();
    }

    /** What a run uses of slot {@code s}'s material in all: slots that hold the same material add up (a shaped recipe). */
    private static Amount totalUse(Pattern pat, int s) {
        Amount sum = Amount.ZERO;
        for (int o = 0; o < pat.inputCount(); o++)
            if (pat.inputItem(o) == pat.inputItem(s) && pat.inputKind(o) == InputKind.CONSUME)
                sum = sum.add(pat.inputAmount(o));
        return sum.signum() > 0 ? sum : pat.inputAmount(s);
    }

    private static final Amount UNBOUNDED = Amount.of(Long.MAX_VALUE).mul(Amount.of(Long.MAX_VALUE));

    private Amount usable(Job job, Task t, int item, boolean withLater) {
        Amount have = job.inventory.get(item);
        if (withLater)
            have = have.add(job.drawLater.get(item));
        boolean owner = t.scc != -1 && job.sccSeeds.getOrDefault(t.scc, new AmountMap()).contains(item);
        for (Plan.Recycle r : job.recycleGuards)
            owner |= r.item() == item && r.has(t.pattern);
        if (!owner)
            have = have.sub(job.protectedSeeds.get(item));
        return have.clampNonNegative();
    }

    // ───────────────────────────── hub-internal runs ─────────────────────────────

    private void consume(Job job, Pattern pat, Amount n) {
        for (int s = 0; s < pat.inputCount(); s++) {
            int item = pat.inputItem(s);
            switch (pat.inputKind(s)) {
                case CONSUME -> takeSlot(job, pat, s, pat.inputAmount(s).mul(n));
                case TOOL -> {
                    int dur = pat.inputDurability(s);
                    if (dur <= 0) {
                        takeSlot(job, pat, s, pat.inputAmount(s).mul(n));
                        break;
                    }
                    Amount before = job.toolUses.get(item);
                    Amount after = before.add(n);
                    Amount tools = after.ceilDiv(dur).sub(before.ceilDiv(dur));
                    job.toolUses.set(item, after);
                    takeSlot(job, pat, s, tools);
                }
                default -> {
                    // catalysts and containers stay in the CPU
                }
            }
        }
    }

    /** Takes from the slot's own item first, then (substitution allowed) from the others, most stocked first. */
    private void takeSlot(Job job, Pattern pat, int s, Amount amount) {
        Amount left = amount;
        left = left.sub(removeFromJob(job, pat.inputItem(s), left));
        if (left.signum() > 0 && pat.allowSubst) {
            List<Integer> alts = new ArrayList<>();
            for (int a = 0; a < pat.alternativeCount(s); a++)
                alts.add(pat.alternative(s, a));
            alts.sort((x, y) -> job.inventory.get(y).compareTo(job.inventory.get(x)));
            for (int alt : alts) {
                if (left.signum() <= 0)
                    break;
                left = left.sub(removeFromJob(job, alt, left));
            }
        }
    }

    /** Items leaving the job (used up or sent out) release capacity. */
    private Amount removeFromJob(Job job, int item, Amount want) {
        Amount got = job.inventory.get(item).min(want).clampNonNegative();
        if (got.signum() > 0) {
            job.inventory.sub(item, got);
            releaseItems(job, item, got);
        }
        return got;
    }

    /**
     * {@code amount} of {@code item} left the job: its bytes. Keys counted in fractions of a byte
     * (fluids) release whole bytes as their amounts add up; the rest goes at the end.
     */
    private void releaseItems(Job job, int item, Amount amount) {
        int apb = job.snap.units().amountPerByte(item);
        if (apb == 8 || apb <= 0) {
            release(job, amount);
            return;
        }
        Amount units = job.releasedUnits.get(item).add(amount);
        job.releasedUnits.set(item, units);
        Amount bytes = units.mul(8).div(Amount.of(apb));
        release(job, bytes.sub(job.releasedBytes.get(item)));
        job.releasedBytes.set(item, bytes);
    }

    /** Items back in the job (a machine refused them): {@link #releaseItems} undone. */
    private void reoccupy(Job job, int item, Amount amount) {
        int apb = job.snap.units().amountPerByte(item);
        Amount back = amount;
        if (apb != 8 && apb > 0) {
            Amount units = job.releasedUnits.get(item).sub(amount).clampNonNegative();
            job.releasedUnits.set(item, units);
            Amount bytes = units.mul(8).div(Amount.of(apb));
            back = job.releasedBytes.get(item).sub(bytes);
            job.releasedBytes.set(item, bytes);
        }
        job.occupancy = job.occupancy.add(back);
        used = used.add(back);
    }

    private void release(Job job, Amount a) {
        Amount r = a.min(job.occupancy);
        job.occupancy = job.occupancy.sub(r);
        used = used.sub(r).clampNonNegative();
    }

    /** Outputs that come with a probability are drawn: each run gives all of its amount or nothing. */
    private void produce(Job job, Pattern pat, Amount n, @Nullable ChanceProgress chance) {
        for (int o = 0; o < pat.outputCount(); o++) {
            Amount runs = pat.isChance(o) ? ChanceSampler.successes(n, pat.outputChance(o), random) : n;
            Amount made = pat.outputAmount(o).mul(runs);
            if (chance != null)
                chance.gave(pat.outputItem(o), made);
            if (made.signum() <= 0)
                continue;
            job.inventory.add(pat.outputItem(o), made);
            notifyItem(job, pat.outputItem(o));
        }
        if (chance != null)
            chance.ran(n);
    }

    /**
     * After runs of a pattern run for probable outputs: stop it once it has what the plan counts on
     * (the materials of the runs left over go back at the end), or, when every planned run is done
     * and it is still short, mark 機率產物不足 and wait for the gap like for a machine's return. No
     * runs are added on top of the plan.
     */
    private void chanceCheck(Job job, Task t, ChanceProgress chance, long now) {
        if (chance.satisfied()) {
            t.remaining = Amount.ZERO;
            return;
        }
        if (t.remaining.signum() > 0)
            return;
        AmountMap gap = chance.shortfall();
        gap.forEach((item, a) -> {
            job.chanceShort.add(item, a);
            expect(job, item, a, now);
        });
    }

    // ───────────────────────────── dispatch ─────────────────────────────

    /**
     * Even split within the highest-priority group of machines that still have window room; leftover
     * copies go one each from the rotation start; only then the next group. N copies = N operations.
     * Returns the runs actually sent.
     */
    private Amount dispatchEven(Job job, Task t, Pattern pat, Amount n, ExecutionEnv env) {
        Object details = pat.source();
        if (details == null)
            return Amount.ZERO;
        TreeListByPriority groups = new TreeListByPriority();
        for (PatternSite prov : env.providers().offering(details))
            if (!prov.isBusy())
                groups.add(prov.priority(), prov);
        long want = n.saturatedLong();
        long sent = 0;
        for (List<PatternSite> group : groups.highestFirst()) {
            List<PatternSite> open = new ArrayList<>(group);
            while (sent < want && !open.isEmpty()) {
                long left = want - sent;
                int m = open.size();
                long share = left / m;
                long extra = left % m;
                boolean progress = false;
                List<PatternSite> full = new ArrayList<>();
                for (int i = 0; i < m; i++) {
                    PatternSite prov = open.get((i + rotate) % m);
                    long k = share + (i < extra ? 1 : 0);
                    if (k <= 0)
                        continue;
                    Window w = windows.computeIfAbsent(new WindowKey(prov, t.pattern), key -> new Window());
                    long cap = w.size.saturatedLong();
                    k = Math.min(k, cap);
                    long pushed = 0;
                    boolean outOfStock = false;
                    for (long c = 0; c < k; c++) {
                        List<@Nullable Map<Key, Long>> inputs = extractInputs(job, pat, env);
                        if (inputs == null) {
                            outOfStock = true;
                            break;
                        }
                        if (!prov.push(details, inputs)) {
                            returnInputs(job, inputs, env);
                            break;
                        }
                        pushed++;
                    }
                    w.onPushResult(Amount.of(k), Amount.of(pushed));
                    if (pushed > 0) {
                        w.lastSendNanos = env.nowNanos();
                        expectOutputs(job, t, pat, pushed, env.nowNanos());
                        creditDispatched(job, pat, Amount.of(pushed));
                        sent += pushed;
                        progress = true;
                    }
                    if (outOfStock)
                        return Amount.of(sent);
                    if (pushed < k || pushed >= cap)
                        full.add(prov);     // refused, or its window is used up for this tick
                }
                open.removeAll(full);
                if (!progress)
                    break;
            }
            if (sent >= want)
                break;
        }
        return Amount.of(sent);
    }

    /** Providers grouped by pattern priority. */
    private static final class TreeListByPriority {
        private final java.util.TreeMap<Integer, List<PatternSite>> map = new java.util.TreeMap<>(Collections.reverseOrder());

        void add(int priority, PatternSite p) {
            map.computeIfAbsent(priority, k -> new ArrayList<>()).add(p);
        }

        Iterable<List<PatternSite>> highestFirst() {
            return map.values();
        }
    }

    /** One run's inputs, slot by slot as AE2 expects; null (nothing taken) when the job lacks them. */
    private @Nullable List<@Nullable Map<Key, Long>> extractInputs(Job job, Pattern pat, ExecutionEnv env) {
        return extractInputs(job, pat, env, null);
    }

    /** {@code into}: the machine the run goes to; a kept catalyst it already holds for this batch is not taken again. */
    private @Nullable List<@Nullable Map<Key, Long>> extractInputs(Job job, Pattern pat, ExecutionEnv env, @Nullable MachineState into) {
        List<@Nullable Map<Key, Long>> out = new ArrayList<>(pat.inputCount());
        for (int s = 0; s < pat.inputCount(); s++)
            out.add(null);
        AmountMap taken = new AmountMap();
        for (int s = 0; s < pat.inputCount(); s++) {
            if (pat.inputAmount(s).isBig())
                return rollback(job, taken);
            Amount amt = pat.inputAmount(s);
            int item = pat.inputItem(s);
            if (into != null && pat.inputRole(s) == InputRoles.CATALYST && holdsKept(into, job, pat, s, env))
                continue;
            if (pat.inputRole(s) == InputRoles.TOOL && job.inventory.get(item).compareTo(amt) < 0) {
                int worn = wornCopy(job, item, amt, env);
                if (worn >= 0)
                    item = worn;
            }
            if (job.inventory.get(item).compareTo(amt) < 0 && pat.allowSubst) {
                for (int a = 0; a < pat.alternativeCount(s); a++)
                    if (job.inventory.get(pat.alternative(s, a)).compareTo(amt) >= 0) {
                        item = pat.alternative(s, a);
                        break;
                    }
            }
            Key key = env.index().key(item);
            if (key == null || job.inventory.get(item).compareTo(amt) < 0)
                return rollback(job, taken);
            job.inventory.sub(item, amt);
            taken.add(item, amt);
            Map<Key, Long> slot = new LinkedHashMap<>();
            out.set(s, slot);
            if (!isVirtual(job, item))      // a mark is used up in the ledger and sent nowhere
                slot.merge(key, amt.longValue(), Long::sum);
        }
        taken.forEach((id, a) -> {
            if (!isVirtual(job, id))
                releaseItems(job, id, a);
        });
        return out;
    }

    private static @Nullable List<@Nullable Map<Key, Long>> rollback(Job job, AmountMap taken) {
        taken.forEach(job.inventory::add);
        return null;
    }

    private void returnInputs(Job job, List<@Nullable Map<Key, Long>> inputs, ExecutionEnv env) {
        for (Map<Key, Long> kc : inputs) {
            if (kc == null)
                continue;
            for (Map.Entry<Key, Long> e : kc.entrySet()) {
                int id = env.index().idOf(e.getKey());
                Amount a = Amount.of(e.getValue());
                job.inventory.add(id, a);
                reoccupy(job, id, a);
            }
        }
    }

    /**
     * Only the products the pattern states count as completion; containers sent along come back too.
     * Outputs that come with a probability are not expected run by run (a run may give none): a
     * pattern run for them expects what it waits for as a whole ({@link #dispatchMachines}).
     */
    private void expectOutputs(Job job, Task t, Pattern pat, long runs, long now) {
        Amount r = Amount.of(runs);
        for (int o = 0; o < pat.outputCount(); o++)
            if (!pat.isChance(o) && !pat.isDispatchDone(o))
                expect(job, pat.outputItem(o), pat.outputAmount(o).mul(r), now);
        for (int s = 0; s < pat.inputCount(); s++)
            if ((pat.inputKind(s) == InputKind.CONTAINER || pat.inputKind(s) == InputKind.CATALYST)
                    // a tool goes back into the job by itself, a kept catalyst is expected once per batch (startRun)
                    && pat.inputRole(s) != InputRoles.TOOL && pat.inputRole(s) != InputRoles.CATALYST)
                expect(job, pat.inputItem(s), pat.inputAmount(s).mul(r), now);
        // a run whose products all count as made once it is fed has nothing coming back to show as in flight
        if (!pat.hasDispatchDone() || pat.hasWaitedOutput())
            t.inFlight = t.inFlight.add(r);
    }

    /**
     * A run's materials are all in its machine (or were sent): the products it is done on dispatch for count as made.
     * A real item is not put into the job (it comes out of the machine by itself and nobody waits for it); a mark is
     * put into the job's ledger so later steps can use it, and it never leaves the job ({@link #isVirtual}).
     */
    private void creditDispatched(Job job, Pattern pat, Amount runs) {
        AmountMap credit = DispatchLedger.credits(pat, runs);
        if (credit.isEmpty())
            return;
        if (envNow != null)
            job.lastProgress = envNow.nowNanos();
        credit.forEach((item, a) -> {
            job.inventory.add(item, a);
            notifyItem(job, item);
        });
    }

    /** Marks of this job's patterns (see {@link Pattern#isVirtualOutput}): no real item, so they never leave the job. */
    private static boolean isVirtual(Job job, int item) {
        return job.virtualItems().contains(item);
    }

    /** Throws away the marks left in the job's inventory (what is left of a mark is not an item to give back). */
    private void dropVirtual(Job job) {
        job.delivered = job.delivered.add(DispatchLedger.dropMarks(job.inventory, job.virtualItems(), job.plan.target));
    }

    /** Some machine is still being fed a run of this job (the job is not done while it is). */
    private boolean feeding(Job job) {
        for (MachineState m : machines.values())
            if (m.feed != null && m.feed.job == job)
                return true;
        for (RestoredMachine r : restoring.values())
            if (r.feed() != null && r.feed().job == job)
                return true;
        return false;
    }

    private static void expect(Job job, int item, Amount a, long now) {
        if (!job.outstanding.contains(item))
            job.outstandingSince.put(item, now);
        job.outstanding.add(item, a);
    }

    // ───────────────────────────── returns ─────────────────────────────

    /**
     * AE2's insertIntoCpus (called through CraftingServiceStorage, mounted at the highest priority):
     * keep only what jobs still expect; the rest flows on into the network. Returns the amount kept.
     */
    public Amount onInsert(int item, Amount amount, long nowNanos) {
        if (selfInserting)
            return Amount.ZERO;     // the hub's own insertion coming back through AE2
        Amount room = requestedAmount(item);
        Amount take = amount.min(room);
        if (take.signum() <= 0)
            return Amount.ZERO;
        pool.add(item, take);
        for (Map.Entry<WindowKey, Window> e : windows.entrySet()) {
            Window w = e.getValue();
            if (w.lastSendNanos >= 0 && producesItem(e.getKey().pattern(), item))
                w.recordRoundTrip(nowNanos - w.lastSendNanos);
        }
        return take;
    }

    private boolean producesItem(int pattern, int item) {
        for (Job j : jobs) {
            Pattern p = j.snap.pattern(pattern);
            if (p != null)
                return p.produces(item);
        }
        return false;
    }

    /** The job takes what it expects from the pool (jobs take turns: the tick's rotation order). */
    private void pullReturns(Job job, ExecutionEnv env) {
        for (int item : job.outstanding.keys()) {
            Amount got = pool.get(item).min(job.outstanding.get(item));
            if (got.signum() <= 0)
                continue;
            pool.sub(item, got);
            job.lastProgress = env.nowNanos();
            job.outstanding.sub(item, got);
            job.inventory.add(item, got);
            job.chanceShort.sub(item, got.min(job.chanceShort.get(item)));
            chanceReturned(job, item, got);
            job.stuck.remove(item);
            if (job.outstanding.contains(item))
                job.outstandingSince.put(item, env.nowNanos());
            else
                job.outstandingSince.remove(item);
            for (Task t : job.tasks) {
                Pattern p = job.snap.pattern(t.pattern);
                Amount per = p.outputAmountOf(item);
                if (per.signum() > 0 && t.inFlight.signum() > 0)
                    t.inFlight = t.inFlight.sub(got.ceilDiv(per).min(t.inFlight));
            }
            notifyItem(job, item);
        }
    }

    /** Finished products go to the network batch by batch (unless a remaining task still needs them). */
    private void deliverFinals(Job job, ExecutionEnv env) {
        int target = job.plan.target;
        for (Task t : job.tasks)
            if (t.remaining.signum() > 0 && job.snap.pattern(t.pattern).hasInput(target))
                return;
        Amount have = job.inventory.get(target);
        if (have.signum() <= 0)
            return;
        if (isVirtual(job, target)) {
            // a mark is the whole product: nothing to hand over
            job.inventory.sub(target, have);
            job.delivered = job.delivered.add(have);
            return;
        }
        Amount in = Amount.ZERO;
        Key key = env.index().key(target);
        if (job.sink != null && key != null) {
            // the requester first (AE2 does the same through the crafting link)
            Amount left = have;
            for (int c = 0; c < MAX_CHUNKS_PER_CALL && left.signum() > 0; c++) {
                long chunk = left.saturatedLong();
                boolean was = selfInserting;
                selfInserting = true;
                long took;
                try {
                    took = job.sink.insert(key, chunk);
                } finally {
                    selfInserting = was;
                }
                in = in.add(Amount.of(took));
                left = left.sub(Amount.of(took));
                if (took < chunk)
                    break;
            }
        }
        if (in.compareTo(have) < 0)
            in = in.add(insertAll(env, target, have.sub(in)));
        job.inventory.sub(target, in);
        job.delivered = job.delivered.add(in);
        releaseItems(job, target, in);
    }

    /** The requester's crafting link takes longs: an astronomical amount goes to it a few chunks per call. */
    private static final int MAX_CHUNKS_PER_CALL = 16;

    /**
     * The hub putting something into network storage itself (finished products, what is left of a
     * job, machine products no job waits for). AE2 offers every insertion to the crafting CPUs
     * first; the hub must not take back what it is giving away, or a job still waiting for more of
     * its product would count its own delivery again and again as products coming back.
     */
    private boolean selfInserting;

    private Amount toNetwork(ExecutionEnv env, Key key, Amount amount) {
        boolean was = selfInserting;
        selfInserting = true;
        try {
            return env.store().insert(key, amount);
        } finally {
            selfInserting = was;
        }
    }

    /** Into network storage (exactly, when the grid has a big storage); returns what went in. */
    private Amount insertAll(ExecutionEnv env, int item, Amount amount) {
        Key key = env.index().key(item);
        return key == null ? Amount.ZERO : toNetwork(env, key, amount);
    }

    // ───────────────────────────── end, cancel, problems ─────────────────────────────

    /** Everything left goes back: ledger extras, rounding, containers, catalysts, tools, loop seeds. */
    private void finish(Job job, ExecutionEnv env) {
        if (returnInventory(job, env)) {
            job.finished = true;
            release(job, job.occupancy);
            if (onDone != null && !job.cancelled)
                onDone.accept(job);
        }
    }

    /** Cancel: inventory back as it is (intermediates are not taken apart), expectations dropped, capacity freed. */
    public void cancel(Job job, ExecutionEnv env) {
        job.cancelled = true;
        job.shortage.clear();
        job.outstanding.clear();
        job.outstandingSince.clear();
        returnInventory(job, env);
        release(job, job.occupancy);
        if (job.inventory.isEmpty())
            job.finished = true;
        jobs.removeIf(j -> j.finished);
    }

    /** True when all of it went in (a full network keeps the rest in the job for the next tick). */
    private boolean returnInventory(Job job, ExecutionEnv env) {
        dropVirtual(job);
        for (int item : job.inventory.keys()) {
            Amount in = insertAll(env, item, job.inventory.get(item));
            job.inventory.sub(item, in);
        }
        return job.inventory.isEmpty();
    }

    private void checkStuck(long now) {
        for (Job job : jobs) {
            if (job.suspended)
                continue;       // paused: the stuck timer stops
            for (int item : job.outstanding.keys()) {
                Long since = job.outstandingSince.get(item);
                if (since == null)
                    continue;
                int pattern = -1;
                long median = -1;
                for (Task t : job.tasks)
                    if (job.snap.pattern(t.pattern).produces(item)) {
                        pattern = t.pattern;
                        for (Map.Entry<WindowKey, Window> e : windows.entrySet())
                            if (e.getKey().pattern() == t.pattern)
                                median = Math.max(median, e.getValue().medianRoundTrip());
                    }
                long threshold = median >= 0 ? Math.max(median * STUCK_RTT_FACTOR, STUCK_MIN_NANOS) : STUCK_DEFAULT_NANOS;
                long waited = now - since;
                if (waited > threshold)
                    job.stuck.put(item, new StuckMark(item, pattern, waited));
            }
        }
    }

    /** F9: the hub was down; jobs with things out get a notice and the manual "pull from network" button. */
    public void onHubResume() {
        for (Job job : jobs)
            if (!job.outstanding.isEmpty())
                job.unloaded = true;
    }

    /** Player pressed the button: take at most the gap from network storage; real returns beyond that flow on. */
    public void pullFromNetwork(Job job, ExecutionEnv env) {
        boolean all = true;
        for (int item : job.outstanding.keys()) {
            Amount gap = job.outstanding.get(item).sub(pool.get(item).min(job.outstanding.get(item)));
            Key key = env.index().key(item);
            if (gap.signum() <= 0 || key == null)
                continue;
            Amount a = env.store().extract(key, gap);
            all &= a.compareTo(gap) >= 0;
            pool.add(item, a);      // from here on like any return: the job takes it on its turn
        }
        if (all)
            job.unloaded = false;
    }

    // ───────────────────────────── loops ─────────────────────────────

    /**
     * Runs the available energy pays for. Energy is counted exactly (fractions of AE): the hub's own
     * storage first, the AE2 network (double) for the rest.
     */
    private Amount energyCap(Pattern pat, Amount n, ExecutionEnv env) {
        Rational per = pat.energyPerRun;
        if (per.signum() <= 0 || n.signum() <= 0)
            return n;
        Rational fromHub = env.hubEnergy() == null ? Rational.ZERO : env.hubEnergy().extractEnergy(per.mul(Rational.of(n)), true);
        return EnergyCap.poweredRuns(per, n, fromHub,
                rest -> env.energy().extract(rest, true));
    }

    private void payEnergy(Pattern pat, Amount n, ExecutionEnv env) {
        Rational per = pat.energyPerRun;
        if (per.signum() <= 0 || n.signum() <= 0)
            return;
        Rational cost = per.mul(Rational.of(n));
        if (env.hubEnergy() != null)
            cost = cost.sub(env.hubEnergy().extractEnergy(cost, false));
        if (cost.signum() > 0)
            env.energy().extract(cost.toDoubleSaturated(), false);
    }

    /**
     * Infinite-source items come in as the job needs them (they could not be pulled at submit):
     * enough for {@code n} runs, any amount, while the source is still infinite.
     */
    private void drawLazily(Job job, Pattern pat, Amount n, ExecutionEnv env) {
        for (int s = 0; s < pat.inputCount(); s++) {
            int item = pat.inputItem(s);
            Amount later = job.drawLater.get(item);
            if (later.signum() <= 0)
                continue;
            Amount need = switch (pat.inputKind(s)) {
                case CONSUME -> totalUse(pat, s).mul(n);   // slots that hold the same material add up
                case TOOL -> pat.inputDurability(s) > 0 ? n.ceilDiv(pat.inputDurability(s)) : pat.inputAmount(s).mul(n);
                default -> pat.where == PatternLocation.HUB_INTERNAL ? pat.inputAmount(s) : pat.inputAmount(s).mul(n);
            };
            need = need.sub(job.inventory.get(item)).min(later);
            Key key = env.index().key(item);
            if (need.signum() <= 0 || key == null)
                continue;
            // an infinite source does not run out, and AE2 extracts at most one long per call: while it
            // is still infinite, the whole need is credited in one go
            if (env.store().isInfinite(key)) {
                job.inventory.add(item, need);
                job.drawLater.sub(item, need);
                continue;
            }
            // the source is gone (cell removed): take what real storage gives
            Amount got = env.store().extract(key, need);
            if (got.signum() > 0) {
                job.inventory.add(item, got);
                job.drawLater.sub(item, got);
            }
        }
    }

    /** A loop with a contract runs in the contract's order and batch sizes. */
    private Amount contractCap(Job job, Task t, Amount n) {
        if (t.scc == -1 || !job.contractStep.containsKey(t.scc))
            return n;
        CycleContract c = job.plan.contracts.get(t.scc);
        int step = job.contractStep.get(t.scc);
        if (c.patterns()[step] != t.pattern)
            return Amount.ZERO;
        return n.min(job.contractLeft.get(t.scc));
    }

    private void advanceContract(Job job, Task t, Amount n) {
        if (t.scc == -1 || !job.contractStep.containsKey(t.scc))
            return;
        Amount left = job.contractLeft.get(t.scc).sub(n);
        if (left.signum() > 0) {
            job.contractLeft.put(t.scc, left);
            return;
        }
        CycleContract c = job.plan.contracts.get(t.scc);
        int step = job.contractStep.get(t.scc) + 1;
        if (step >= c.patterns().length) {
            job.contractStep.remove(t.scc);    // done: plain rules from here on
            job.contractLeft.remove(t.scc);
        } else {
            job.contractStep.put(t.scc, step);
            job.contractLeft.put(t.scc, c.batches()[step]);
        }
        for (Task other : job.tasks)
            if (other.scc == t.scc)
                checkReady(job, other);
    }

    /** A loop that uses an item again holds its seed until every pattern of the loop has run. */
    private void releaseRecycleIfLoopDone(Job job, int pattern) {
        for (Iterator<Plan.Recycle> it = job.recycleGuards.iterator(); it.hasNext(); ) {
            Plan.Recycle r = it.next();
            if (!r.has(pattern))
                continue;
            boolean done = true;
            for (Task t : job.tasks)
                if (r.has(t.pattern) && t.remaining.signum() > 0)
                    done = false;
            if (!done)
                continue;
            it.remove();
            job.protectedSeeds.sub(r.item(), r.seed());
            notifyItem(job, r.item());
        }
    }

    /** Seeds stay protected until the whole loop has run; then they are ordinary stock again. */
    private void releaseSeedsIfLoopDone(Job job, int scc) {
        for (Task t : job.tasks)
            if (t.scc == scc && t.remaining.signum() > 0)
                return;
        AmountMap seeds = job.sccSeeds.remove(scc);
        if (seeds == null)
            return;
        seeds.forEach(job.protectedSeeds::sub);
        for (int item : seeds.keys())
            notifyItem(job, item);
    }

    // ───────────────────────────── machines ─────────────────────────────

    /**
     * One machine behind a machine access interface. It is fed one run at a time: a run's
     * materials go in as the machine takes them (a singularity compressor takes a stack at a time),
     * and the next run starts only when they are all in. Products are taken out of its output slots
     * by the hub every tick, whoever's they are.
     */
    static final class MachineState {
        public final IMachine access;
        @Nullable Feed feed;
        /** the pattern fed last (blocking interfaces) */
        int lastPattern = -1;
        @Nullable Pattern lastPat;
        /** runs started here whose products are not all back, per job and pattern */
        final Map<Flight, Amount> inFlight = new LinkedHashMap<>();
        /** products taken out that neither the hub nor the network took yet */
        final Map<Key, Long> overflow = new LinkedHashMap<>();
        /** where the machine is, kept for when the interface is gone (lost runs are kept by place) */
        @Nullable Place place;
        /** cells in front of it that materials were placed or dropped in: products are picked up there */
        final Set<Pos> frontCells = new HashSet<>();
        /** its sub-machines for {@link #subsFor}, found at {@link #subsAt} (game time) */
        @Nullable List<Pos> subs;
        @Nullable Pattern subsFor;
        long subsAt;
        /** game time the GUI was last opened to look for products */
        long guiPolledAt = Long.MIN_VALUE / 2;
        /** game time of the last redstone pulse asked for */
        long pulsedAt = Long.MIN_VALUE / 2;
        /**
         * sub-machines that took something by a right-click (no item slots: an altar's pedestal) and still hold it: a
         * second right-click would swap what is on it, so they are left alone until the machine has no run left
         */
        final Set<Pos> filledSubs = new HashSet<>();
        /** catalysts (role CATALYST) put into the machine and not yet back out, per batch of runs */
        final Map<Flight, Map<Key, Long>> held = new LinkedHashMap<>();
        /** catalysts to take out of the machine now: those of batches that are done */
        final Map<Key, Long> due = new LinkedHashMap<>();

        public MachineState(IMachine access) {
            this.access = access;
        }
    }

    record Flight(Job job, int pattern) {
    }

    /** The run being fed: per input slot what is still to go in, and through which faces. */
    static final class Feed {
        final Job job;
        final int pattern;
        final List<Part> parts = new ArrayList<>();
        /** tools to right-click the machine with once every material is in (role TOOL) */
        final List<Key> tools = new ArrayList<>();
        /** game time the tools are used at, once every material is in (-1: not set yet) */
        long toolAt = -1;
        long total;
        long fed;

        Feed(Job job, int pattern) {
            this.job = job;
            this.pattern = pattern;
        }

        boolean done() {
            for (Part p : parts)
                if (p.left > 0)
                    return false;
            return true;
        }
    }

    static final class Part {
        final Key key;
        long left;
        final List<@Nullable Face> faces;
        /** where it goes ({@link InputTargets}) */
        final int target;
        /** what it is besides used up ({@link InputRoles}) */
        final int role;
        /** the slot of the machine's GUI it goes into, for machines that take items only there (0 = the first that takes it) */
        final int guiSlot;
        /** the hatch (input bus, fluid input) it began going into when it has no slot number: it stays there, and no other material goes there */
        @Nullable Pos hatchPort;

        Part(Key key, long left, List<@Nullable Face> faces, int target, int role, int guiSlot) {
            this.guiSlot = guiSlot;
            this.key = key;
            this.left = left;
            this.faces = faces;
            this.target = target;
            this.role = role;
        }
    }

    /** Runs lost with a machine that went away, kept by the machine's place until an interface is back on it. */
    final Map<Place, Map<Flight, Amount>> lostAt = new HashMap<>();
    public final Map<IMachine, MachineState> machines = new LinkedHashMap<>();
    /** products of interfaces taken off that the network had no room for yet */
    final Map<Key, Long> orphans = new LinkedHashMap<>();
    /** machines as a save left them, by place, until their interface is on the grid again ({@link JobStore}) */
    final Map<Place, RestoredMachine> restoring = new HashMap<>();

    /** What a saved machine was doing: put back on the interface found at its place. */
    record RestoredMachine(@Nullable Feed feed, int lastPattern, @Nullable Pattern lastPat, Map<Flight, Amount> inFlight,
                           Map<Key, Long> overflow) {
    }

    /** Machine access interfaces added and gone since the last tick. */
    private void syncMachines(ExecutionEnv env) {
        Set<IMachine> now = Collections.newSetFromMap(new IdentityHashMap<>());
        now.addAll(env.machines());
        for (Iterator<Map.Entry<IMachine, MachineState>> it = machines.entrySet().iterator(); it.hasNext(); ) {
            MachineState m = it.next().getValue();
            if (!now.contains(m.access)) {
                it.remove();
                machineGone(m, env);
            }
        }
        for (IMachine a : now)
            if (!machines.containsKey(a)) {
                MachineState m = new MachineState(a);
                machines.put(a, m);
                machineBack(m, env);
            }
    }

    private static @Nullable Place placeOf(IMachine a) {
        World level = a.getMachineWorld();
        return level == null ? null : level.place(a.getMachinePos());
    }

    /**
     * An interface taken off (or its machine broken): the runs in that machine are lost. What a feed
     * had not put in yet goes back to its job; the products of the lost runs are no longer waited
     * for, and the job shows why it stopped. Nothing is made again on its own.
     */
    private void machineGone(MachineState m, ExecutionEnv env) {
        World gone = m.access.getMachineWorld();
        if (gone != null)
            gone.storage().forget(m.access.getMachinePos());
        if (m.feed != null) {
            Feed f = m.feed;
            for (Part p : f.parts)
                if (p.left > 0) {
                    int id = env.index().idOf(p.key);
                    f.job.inventory.add(id, Amount.of(p.left));
                    reoccupy(f.job, id, Amount.of(p.left));
                    notifyItem(f.job, id);
                }
            m.feed = null;
        }
        // products held back while the network was full: kept by the hub until it takes them
        m.overflow.forEach((k, v) -> orphans.merge(k, v, Long::sum));
        m.overflow.clear();
        Place at = m.place;
        for (Map.Entry<Flight, Amount> e : m.inFlight.entrySet()) {
            Job job = e.getKey().job();
            if (job.finished || job.cancelled)
                continue;
            Pattern pat = job.snap.pattern(e.getKey().pattern());
            for (int o = 0; o < pat.outputCount(); o++)
                if (!pat.isChance(o) && !pat.isDispatchDone(o)) {
                    int item = pat.outputItem(o);
                    job.outstanding.sub(item, pat.outputAmount(o).mul(e.getValue()).min(job.outstanding.get(item)));
                }
            job.machineLost.add(e.getKey().pattern(), e.getValue());
            if (at != null)
                lostAt.computeIfAbsent(at, k -> new LinkedHashMap<>()).merge(e.getKey(), e.getValue(), Amount::add);
        }
        m.inFlight.clear();
    }

    /** An interface on a machine whose runs were lost: they are tracked again, late products are taken. */
    private void machineBack(MachineState m, ExecutionEnv env) {
        Place at = m.place = placeOf(m.access);
        RestoredMachine saved = at == null ? null : restoring.remove(at);
        if (saved != null) {
            m.feed = saved.feed();
            m.lastPattern = saved.lastPattern();
            m.lastPat = saved.lastPat();
            m.inFlight.putAll(saved.inFlight());
            saved.overflow().forEach((k, v) -> m.overflow.merge(k, v, Long::sum));
        }
        Map<Flight, Amount> lost = at == null ? null : lostAt.remove(at);
        if (lost == null)
            return;
        for (Map.Entry<Flight, Amount> e : lost.entrySet()) {
            Job job = e.getKey().job();
            if (job.finished || job.cancelled || !jobs.contains(job))
                continue;
            Pattern pat = job.snap.pattern(e.getKey().pattern());
            Amount r = e.getValue();
            for (int o = 0; o < pat.outputCount(); o++)
                if (!pat.isChance(o) && !pat.isDispatchDone(o))
                    expect(job, pat.outputItem(o), pat.outputAmount(o).mul(r), env.nowNanos());
            job.machineLost.sub(e.getKey().pattern(), r.min(job.machineLost.get(e.getKey().pattern())));
            m.inFlight.merge(e.getKey(), r, Amount::add);
        }
    }

    /** Every tick: products out of every machine, feeds go on, probable outputs that ran out are marked. */
    private void machineTick(ExecutionEnv env) {
        for (Iterator<Map.Entry<Key, Long>> it = orphans.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Long> e = it.next();
            long went = deliver(env.index().idOf(e.getKey()), e.getKey(), Amount.of(e.getValue()), env).saturatedLong();
            if (went >= e.getValue())
                it.remove();
            else
                e.setValue(e.getValue() - went);
        }
        for (MachineState m : machines.values()) {
            World level = m.access.getMachineWorld();
            Pos pos = m.access.getMachinePos();
            if (level == null || !level.isLoaded(pos))
                continue;
            if (m.place == null)
                m.place = level.place(pos);
            dropFeedOfEndedJob(m, env);
            if (m.feed == null && m.inFlight.isEmpty())
                m.filledSubs.clear();
            flushOverflow(m, env);
            boolean[] took = {false};
            level.storage().extractOutputs(pos, otherOutputs(m, env), materialsInside(m, env), productsInside(m, env), (key, n) -> {
                took[0] = true;
                takeProduct(m, key, n, env);
            });
            // the output hatches bound to it
            for (IMachine.Port port : m.access.ports())
                if (port.role() == IMachine.PortRole.OUTPUT && level.isLoaded(port.pos()))
                    level.storage().extractOutputs(port.pos(), otherOutputs(m, env), materialsInside(m, env), productsInside(m, env), (key, n) -> {
                        took[0] = true;
                        takeProduct(m, key, n, env);
                    });
            // what the machine put out into its interface by itself
            m.access.takeReceived((key, n) -> {
                took[0] = true;
                takeProduct(m, key, n, env);
            });
            pickUpFront(m, level, env);
            takeFromGui(m, level, pos, env, took);
            retrieveCatalysts(m, level, pos, env);
            pulseAgain(m, level, env);
            if (debug != null && !took[0] && !m.inFlight.isEmpty() && m.feed == null) {
                Amount runs = Amount.ZERO;
                for (Amount r : m.inFlight.values())
                    runs = runs.add(r);
                note(outNotes, m, "[雙星] 機器產物 " + pos.toShortString() + "：等 " + runs + " 份的產物，這 tick 沒抽到東西；看到："
                        + level.storage().describe(pos));
            }
            if (m.access.isAccessActive() && m.access.getInsertMode() != IMachine.InsertMode.OUTPUT)
                feed(m, level, pos);
            pruneFlights(m, env);
        }
        for (Job job : jobs)
            for (Map.Entry<Integer, ChanceProgress> e : job.chances.entrySet())
                chanceCheckMachine(job, e.getKey(), e.getValue(), env);
    }

    /**
     * Runs no longer in this machine: those of jobs that ended, and those of patterns whose
     * products all come with a probability (no product says a run is done) once the machine has
     * none of their materials left.
     */
    private void pruneFlights(MachineState m, ExecutionEnv env) {
        for (Iterator<Map.Entry<Flight, Amount>> it = m.inFlight.entrySet().iterator(); it.hasNext(); ) {
            Flight f = it.next().getKey();
            if (f.job().finished || f.job().cancelled || !jobs.contains(f.job())) {
                it.remove();
                continue;
            }
            Pattern pat = f.job().snap.pattern(f.pattern());
            boolean sure = false;
            for (int o = 0; o < pat.outputCount() && !sure; o++)
                sure = !pat.isChance(o) && !pat.isDispatchDone(o);
            if (!sure && !(m.feed != null && m.feed.job == f.job() && m.feed.pattern == f.pattern()) && !holds(m, pat, env))
                it.remove();
        }
    }

    /**
     * The materials of the runs in machine {@code m} (and of the one being fed) that are used up or are catalysts: while
     * they are in it they are not products, whatever the look of the slot they lie in, unless a run makes them too.
     * What a machine gives back by design (a container) is not among them.
     */
    private Set<Key> materialsInside(MachineState m, ExecutionEnv env) {
        List<Pattern> pats = new ArrayList<>();
        for (MachineState peer : peers(m)) {
            for (Flight f : peer.inFlight.keySet())
                pats.add(f.job().snap.pattern(f.pattern()));
            if (peer.feed != null)
                pats.add(peer.feed.job.snap.pattern(peer.feed.pattern));
        }
        Set<Key> keep = new HashSet<>(), products = new HashSet<>();
        for (Pattern pat : pats) {
            for (int o = 0; o < pat.outputCount(); o++) {
                Key k = env.index().key(pat.outputItem(o));
                if (k != null)
                    products.add(k);
            }
            for (int s = 0; s < pat.inputCount(); s++) {
                if (pat.inputKind(s) != InputKind.CONSUME && pat.inputRole(s) != InputRoles.CATALYST)
                    continue;
                Key k = env.index().key(pat.inputItem(s));
                if (k != null)
                    keep.add(k);
            }
        }
        keep.removeAll(products);
        return keep;
    }

    /** Everything the runs in machine {@code m} (and its peers) make. */
    private Set<Key> productsInside(MachineState m, ExecutionEnv env) {
        Set<Key> out = new HashSet<>();
        for (MachineState peer : peers(m))
            for (Flight f : peer.inFlight.keySet()) {
                Pattern pat = f.job().snap.pattern(f.pattern());
                for (int o = 0; o < pat.outputCount(); o++) {
                    Key k = env.index().key(pat.outputItem(o));
                    if (k != null)
                        out.add(k);
                }
            }
        out.removeAll(m.access.getStockedKeys());
        return out;
    }

    /** The outputs of the runs in machine {@code m} that are neither items nor fluids (chemicals). */
    private Set<Key> otherOutputs(MachineState m, ExecutionEnv env) {
        Set<Key> out = new HashSet<>();
        for (MachineState peer : peers(m))
            for (Flight f : peer.inFlight.keySet()) {
                Pattern pat = f.job().snap.pattern(f.pattern());
                for (int o = 0; o < pat.outputCount(); o++) {
                    Key k = env.index().key(pat.outputItem(o));
                    if (k != null && k.kind() == Key.Kind.OTHER)
                        out.add(k);
                }
            }
        return out;
    }

    /** {@code m} and the interfaces of the same machine spread over several blocks ({@link IMachine#getGroup}). */
    private List<MachineState> peers(MachineState m) {
        int group = m.access.getGroup();
        if (group <= 0)
            return List.of(m);
        List<MachineState> out = new ArrayList<>();
        for (MachineState other : machines.values())
            if (other.access.getGroup() == group && java.util.Objects.equals(other.access.getMachineWorld(), m.access.getMachineWorld())
                    && java.util.Objects.equals(other.access.getMachine(), m.access.getMachine()))
                out.add(other);
        return out;
    }

    /**
     * {@code n} of {@code key} out of machine {@code m}: the runs it finishes are no longer in flight, the
     * jobs waiting for it take it, the rest goes to network storage (as it came).
     */
    private void takeProduct(MachineState m, Key key, long n, ExecutionEnv env) {
        // a catalyst coming back out of the machine is no longer in it, nor due
        for (Iterator<Map.Entry<Flight, Map<Key, Long>>> it = m.held.entrySet().iterator(); it.hasNext() && n > 0; ) {
            Map<Key, Long> h = it.next().getValue();
            Long c = h.get(key);
            if (c != null) {
                long back = Math.min(c, n);
                if (c - back > 0)
                    h.put(key, c - back);
                else
                    h.remove(key);
                if (h.isEmpty())
                    it.remove();
                m.due.computeIfPresent(key, (k, d) -> d - back > 0 ? d - back : null);
            }
        }
        Key as = productAs(m, key, env);
        int id = env.index().idOf(as);
        landed(m, id, Amount.of(n));
        long kept = onInsert(id, Amount.of(n), env.nowNanos()).saturatedLong();
        long rest = n - kept;
        if (rest > 0) {
            long went = toNetwork(env, key, Amount.of(rest)).saturatedLong();
            if (rest - went > 0)
                m.overflow.merge(key, rest - went, Long::sum);
        }
    }

    /**
     * What {@code key} out of machine {@code m} counts as: itself, or, when it is not a product of the
     * runs in it as it is, the product it is but for its NBT, of a pattern that does not mind (the hub
     * then holds it as that product).
     */
    private Key productAs(MachineState m, Key key, ExecutionEnv env) {
        if (key.kind() != Key.Kind.ITEM)
            return key;
        int exact = env.index().find(key);
        Key match = null;
        for (MachineState peer : peers(m))
            for (Flight f : peer.inFlight.keySet()) {
                Pattern pat = f.job().snap.pattern(f.pattern());
                if (exact >= 0 && pat.produces(exact))
                    return key;
                for (int o = 0; o < pat.outputCount() && match == null; o++) {
                    int item = pat.outputItem(o);
                    Key out = env.index().key(item);
                    if (out != null && pat.ignoresNbtOf(item) && out.sameIgnoringData(key))
                        match = out;
                }
            }
        return match != null ? match : key;
    }

    /** A cancelled job's run that is not all in: what is left goes to network storage, not into the machine. */
    private void dropFeedOfEndedJob(MachineState m, ExecutionEnv env) {
        Feed f = m.feed;
        if (f == null || !(f.job.cancelled || f.job.finished))
            return;
        for (Part p : f.parts)
            if (p.left > 0) {
                Amount went = toNetwork(env, p.key, Amount.of(p.left));
                long rest = p.left - went.saturatedLong();
                if (rest > 0)
                    m.overflow.merge(p.key, rest, Long::sum);
            }
        for (Key tool : f.tools) {
            long rest = 1 - toNetwork(env, tool, Amount.ONE).saturatedLong();
            if (rest > 0)
                m.overflow.merge(tool, rest, Long::sum);
        }
        m.feed = null;
    }

    /** Into the hub for the jobs that wait for it, the rest into network storage; returns what went. */
    private Amount deliver(int id, Key key, Amount n, ExecutionEnv env) {
        Amount kept = onInsert(id, n, env.nowNanos());
        Amount rest = n.sub(kept);
        return rest.signum() > 0 ? kept.add(toNetwork(env, key, rest)) : kept;
    }

    private void flushOverflow(MachineState m, ExecutionEnv env) {
        for (Iterator<Map.Entry<Key, Long>> it = m.overflow.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Key, Long> e = it.next();
            long went = deliver(env.index().idOf(e.getKey()), e.getKey(), Amount.of(e.getValue()), env).saturatedLong();
            if (went >= e.getValue())
                it.remove();
            else
                e.setValue(e.getValue() - went);
        }
    }

    /** Products out of machine {@code m}: the runs they finish there are no longer in flight. */
    private void landed(MachineState m, int item, Amount n) {
        for (MachineState peer : peers(m))
            landedOn(peer, item, n);
    }

    private static void landedOn(MachineState m, int item, Amount n) {
        for (Iterator<Map.Entry<Flight, Amount>> it = m.inFlight.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Flight, Amount> e = it.next();
            Pattern pat = e.getKey().job().snap.pattern(e.getKey().pattern());
            Amount per = pat.sureOutputAmountOf(item);
            if (per.signum() <= 0)
                continue;
            Amount left = e.getValue().sub(n.ceilDiv(per));
            if (left.signum() <= 0)
                it.remove();
            else
                e.setValue(left);
            return;
        }
    }

    // ───────────────────────────── catalysts and tools of machine patterns ─────────────────────────────

    /** Machine {@code m} already holds the kept catalyst of slot {@code s} for {@code job}'s run of {@code pat}. */
    private boolean holdsKept(MachineState m, Job job, Pattern pat, int s, ExecutionEnv env) {
        Key key = env.index().key(pat.inputItem(s));
        if (key == null)
            return false;
        for (Map.Entry<Flight, Map<Key, Long>> e : m.held.entrySet())
            if (e.getKey().job() == job && job.snap.pattern(e.getKey().pattern()) == pat && e.getValue().getOrDefault(key, 0L) > 0)
                return true;
        return false;
    }

    /** Some machine holds a kept catalyst for {@code job}'s pattern {@code p}. */
    private boolean keptHeld(Job job, int p) {
        for (MachineState m : machines.values())
            for (Map.Entry<Flight, Map<Key, Long>> e : m.held.entrySet())
                if (e.getKey().job() == job && e.getKey().pattern() == p && !e.getValue().isEmpty())
                    return true;
        return false;
    }

    /** The job's copies of a tool in other states of wear (same item, other data): the id of one with {@code amt}, or -1. */
    private int wornCopy(Job job, int item, Amount amt, ExecutionEnv env) {
        Key main = env.index().key(item);
        if (main == null || main.kind() != Key.Kind.ITEM)
            return -1;
        for (int id : job.inventory.keys()) {
            if (id != item && job.inventory.get(id).compareTo(amt) >= 0 && sameItem(env.index().key(id), main))
                return id;
        }
        return -1;
    }

    /** Both are items of one kind (components may differ). */
    private static boolean sameItem(@Nullable Key a, Key b) {
        return a != null && a.kind() == Key.Kind.ITEM && a.id().equals(b.id());
    }

    private Amount wornTotal(Job job, int item, ExecutionEnv env) {
        Key main = env.index().key(item);
        Amount sum = Amount.ZERO;
        if (main != null && main.kind() == Key.Kind.ITEM)
            for (int id : job.inventory.keys())
                if (id != item && sameItem(env.index().key(id), main))
                    sum = sum.add(job.inventory.get(id));
        return sum;
    }

    /** A batch is over: nothing of it is being fed, in flight, or still to be sent. */
    private boolean batchOver(MachineState m, Flight f) {
        if (f.job().finished || f.job().cancelled || !jobs.contains(f.job()))
            return true;
        if (m.feed != null && m.feed.job == f.job() && m.feed.pattern == f.pattern())
            return false;
        if (m.inFlight.containsKey(f))
            return false;
        for (Task t : f.job().tasks)
            if (t.pattern == f.pattern() && t.remaining.signum() > 0)
                return false;
        return true;
    }


    private void takeFromGui(MachineState m, World level, Pos pos, ExecutionEnv env, boolean[] took) {
        if (took[0] || m.inFlight.isEmpty() || m.feed != null)
            return;
        long now = level.getGameTime();
        if (now - m.guiPolledAt < ExecutionSettings.guiPollTicks())
            return;
        m.guiPolledAt = now;
        Map<Key, Integer> products = new HashMap<>();
        for (MachineState peer : peers(m))
            for (Flight f : peer.inFlight.keySet()) {
                Pattern pat = f.job().snap.pattern(f.pattern());
                for (int o = 0; o < pat.outputCount(); o++) {
                    Key k = env.index().key(pat.outputItem(o));
                    if (k != null)
                        products.merge(k, pat.outputGuiSlotOf(pat.outputItem(o)), Math::max);
                }
            }
        // a machine with item slots is looked into only for products the pattern says come out of a GUI slot
        if (products.isEmpty() || level.storage().hasItemStorage(pos) && products.values().stream().noneMatch(v -> v > 0))
            return;
        long got = level.actions().extractViaGui(pos, m.access.getMachineFace(), products, (k, n) -> {
            took[0] = true;
            takeProduct(m, k, n, env);
        }, (k, n) -> m.overflow.merge(k, n, Long::sum));
        if (debug != null && got > 0)
            note(outNotes, m, "[雙星] 從 GUI 取出 " + pos.toShortString() + "：" + got + " 個產物");
    }

    /**
     * Takes catalysts back out of the machine: those of finished runs, and the kept ones of finished batches.
     * What does not come out this tick is tried again on the next.
     */
    private void retrieveCatalysts(MachineState m, World level, Pos pos, ExecutionEnv env) {
        if (m.held.isEmpty() && m.due.isEmpty())
            return;
        for (Map.Entry<Flight, Map<Key, Long>> e : new ArrayList<>(m.held.entrySet())) {
            if (!batchOver(m, e.getKey()))
                continue;
            Pattern pat = e.getKey().job().snap.pattern(e.getKey().pattern());
            for (int s = 0; s < pat.inputCount(); s++) {
                int role = pat.inputRole(s);
                Key key = env.index().key(pat.inputItem(s));
                if (key == null || role != InputRoles.CATALYST)
                    continue;
                long inside = e.getValue().getOrDefault(key, 0L);
                if (inside > 0)
                    m.due.merge(key, inside, Math::max);
            }
        }
        for (Map.Entry<Key, Long> d : new ArrayList<>(m.due.entrySet())) {
            if (d.getKey().kind() != Key.Kind.ITEM)
                continue;
            Key item = d.getKey();
            long want = d.getValue();
            long got = level.storage().retrieve(pos, item, want, (k, n) -> takeProduct(m, k, n, env));
            if (got < want && !level.storage().hasItemStorage(pos))
                // a block that only gives with a hand (an altar, an infusion chamber): an empty-hand click
                level.actions().emptyClick(pos, m.access.getMachineFace(), (k, n) -> takeProduct(m, k, n, env));
            // what was put into its sub-machines (an altar's pedestals) comes out of them; by their item slots only,
            // as a hand on a pedestal would take whatever else lies there
            for (Map.Entry<Flight, Map<Key, Long>> h : new ArrayList<>(m.held.entrySet())) {
                if (m.due.getOrDefault(item, 0L) <= 0)
                    break;
                for (Pos sub : subsOf(m, h.getKey().job().snap.pattern(h.getKey().pattern()), level, pos))
                    if (level.isLoaded(sub))
                        level.storage().retrieve(sub, item, m.due.getOrDefault(item, 0L), (k, n) -> takeProduct(m, k, n, env));
            }
        }
    }

    /** Every material of the run is in: right-click the machine with each of its tools, and keep the tools as the clicks left them. */
    private void useTools(MachineState m, Feed f, World level, Pos pos) {
        for (Key key : f.tools) {
            if (key.kind() != Key.Kind.ITEM)
                continue;
            Key tool = key;
            Key back = level.actions().useTool(pos, m.access.getMachineFace(), tool, (k, n) -> m.overflow.merge(k, n, Long::sum));
            if (back != null && envNow != null) {
                int id = envNow.index().idOf(back);
                f.job.inventory.add(id, Amount.ONE);
                reoccupy(f.job, id, Amount.ONE);
            }
        }
    }

    /** Puts in what the machine takes of its current run. */
    private void feed(MachineState m, World level, Pos pos) {
        Feed f = m.feed;
        if (f == null)
            return;
        StringBuilder stuck = debug == null ? null : new StringBuilder();
        Pattern pat = f.job.snap.pattern(f.pattern);
        for (Part p : f.parts) {
            if (p.left <= 0)
                continue;
            long in = switch (InputTargets.kind(p.target)) {
                case InputTargets.SUB -> {
                    long went = 0;
                    for (Pos sub : subsOf(m, pat, level, pos)) {
                        if (m.filledSubs.contains(sub))
                            continue;
                        long got = insertOrClick(m, level, sub, ANY_FACE, p.key, p.left - went, p.guiSlot);
                        if (got > 0 && !level.storage().hasItemStorage(sub))
                            m.filledSubs.add(sub);
                        went += got;
                        if (went >= p.left)
                            break;
                    }
                    yield went;
                }
                case InputTargets.FRONT_BLOCK, InputTargets.FRONT_DROP -> {
                    if (p.key.kind() != Key.Kind.ITEM)
                        yield 0;
                    Key item = p.key;
                    Pos cell = level.actions().front(pos, m.access.getMachineFace(), InputTargets.distance(p.target));
                    m.frontCells.add(cell);
                    long went = InputTargets.kind(p.target) == InputTargets.FRONT_BLOCK ? level.actions().place(cell, item) : 0;
                    // not a block (or a block it may not place): dropped instead
                    if (went == 0 && !(InputTargets.kind(p.target) == InputTargets.FRONT_BLOCK
                            && level.actions().isBlockItem(item)))
                        went = level.actions().drop(cell, item, p.left);
                    yield went;
                }
                default -> {
                    // the machine's hatches first (a multiblock's input buses, by the slot number the pattern gives): a material whose
                    // slot has a hatch goes there and nowhere else; the rest goes into the machine as it always did
                    boolean[] only = {false};
                    long went = intoHatches(m, level, p, only);
                    yield only[0] || went >= p.left ? went
                            : went + insertOrClick(m, level, pos, p.faces, p.key, p.left - went, p.guiSlot);
                }
            };
            p.left -= in;
            f.fed += in;
            if (in > 0 && p.role != InputRoles.NORMAL)
                m.held.computeIfAbsent(new Flight(f.job, f.pattern), k -> new LinkedHashMap<>()).merge(p.key, in, Long::sum);
            else if (in > 0 && usage != null)
                usage.used(m.access, p.key, in);        // a material the machine uses up (a catalyst or a tool comes back: not counted)
            if (stuck != null && p.left > 0)
                stuck.append("\n    ").append(p.key.displayName()).append("（").append(p.key.typeName())
                        .append("）還剩 ").append(p.left).append("，這次放入 ").append(in).append("；")
                        .append(level.storage().describeInsert(pos, p.faces, p.key, p.left))
                        .append(hatchNote(m, level, p))
                        .append(level.actions().lastClick().isEmpty() ? "" : "；最後一次右鍵：" + level.actions().lastClick());
        }
        if (f.done()) {
            if (!f.tools.isEmpty()) {
                // the machine is given a moment to take in what it was fed (a forge works out its pedestals a little later)
                // before it is struck: started the very tick the last material lands, the machine tells its clients about
                // the run while it is already going, which Forbidden Arcanus' client cannot take
                long now = level.getGameTime();
                if (f.toolAt < 0)
                    f.toolAt = now + ExecutionSettings.toolDelayTicks();
                if (now < f.toolAt)
                    return;
            }
            m.feed = null;
            useTools(m, f, level, pos);
            creditDispatched(f.job, pat, Amount.ONE);
            // runs fed together in one tick (dropped in front all at once) get one pulse, not one each
            if (m.pulsedAt != level.getGameTime()) {
                m.access.onRunFed();
                m.pulsedAt = level.getGameTime();
            }
        }
        else if (stuck != null)
            note(feedNotes, m, "[雙星] 機器放入 " + pos.toShortString() + "（放入方式 " + m.access.getInsertMode() + "，接口所在面 "
                    + m.access.getMachineFace().getName() + "）：" + stuck);
    }

    /** For the debug log: the hatch a material's slot number names, and what it does with it. */
    private String hatchNote(MachineState m, World level, Part p) {
        List<IMachine.Port> ports = m.access.ports();
        if (ports.isEmpty())
            return "";
        boolean fluid = p.key.kind() == Key.Kind.FLUID;
        IMachine.PortRole role = fluid ? IMachine.PortRole.FLUID_IN : IMachine.PortRole.ITEM_IN;
        StringBuilder sb = new StringBuilder("；艙口（" + (fluid ? "流體" : "物品") + "輸入，欄位 " + p.guiSlot + "）：");
        boolean any = false;
        for (IMachine.Port port : ports) {
            if (port.role() != role || (p.guiSlot > 0 && port.slot() != p.guiSlot))
                continue;
            any = true;
            sb.append("\n      #").append(port.slot()).append(" ").append(port.pos().toShortString()).append(" ")
                    .append(level.blockId(port.pos())).append("：")
                    .append(level.storage().describeInsert(port.pos(), ANY_FACE, p.key, p.left));
        }
        return any ? sb.toString() : sb.append("沒有這個欄位號碼的艙口（綁了 ").append(ports.size()).append(" 個艙口）").toString();
    }

    /**
     * A multiblock's controller has no item inventory (the items go through its input buses): a material with no slot number then
     * has nowhere to go but the buses, in order, the first one that takes it.
     */
    private static boolean controllerTakesNoItems(MachineState m, World level) {
        return !level.storage().hasItemStorage(m.access.getMachinePos());
    }

    /**
     * Puts a material into the hatches bound to the machine ({@link IMachine#ports}): one whose slot number is the material's
     * slot (items into the item inputs, fluids into the fluid inputs, each numbered apart); a fluid with no slot into the first
     * fluid input that takes it. {@code only[0]} is set when a hatch is named for it, so it goes nowhere else even when that hatch
     * is full (the order of an assembly line matters). Returns how much went in.
     */
    private long intoHatches(MachineState m, World level, Part p, boolean[] only) {
        long went = intoHatches0(m, level, p, only);
        if (went > 0 && debug != null) {
            // which hatch took what, by ids: a material in the wrong hatch shows here (what the hatch holds is read after the insert)
            String id = p.key.kind() == Key.Kind.OTHER ? p.key.typeName() : p.key.id();
            StringBuilder sb = new StringBuilder("[雙星] 艙口放入 ").append(id).append(" ×").append(went).append("（樣板欄位 ").append(p.guiSlot).append("）→");
            for (IMachine.Port port : m.access.ports())
                if (port.pos().equals(p.hatchPort) || p.guiSlot > 0 && port.slot() == p.guiSlot
                        && port.role() == (p.key.kind() == Key.Kind.FLUID ? IMachine.PortRole.FLUID_IN : IMachine.PortRole.ITEM_IN))
                    sb.append(" 艙口 #").append(port.slot()).append(" ").append(port.pos().toShortString()).append("，現有：")
                            .append(p.key.kind() == Key.Kind.ITEM ? level.storage().contentsAt(port.pos()) : "（流體）");
            debug.accept(sb.toString());
        }
        return went;
    }

    private long intoHatches0(MachineState m, World level, Part p, boolean[] only) {
        List<IMachine.Port> ports = m.access.ports();
        if (ports.isEmpty())
            return 0;
        boolean fluid = p.key.kind() == Key.Kind.FLUID;
        IMachine.PortRole role = fluid ? IMachine.PortRole.FLUID_IN : IMachine.PortRole.ITEM_IN;
        boolean noController = !fluid && controllerTakesNoItems(m, level);
        if (p.guiSlot > 0) {
            // the hatch with this slot number, and nowhere else
            boolean named = false;
            for (IMachine.Port port : ports) {
                if (port.role() != role || port.slot() != p.guiSlot)
                    continue;
                named = true;
                only[0] = true;
                if (level.isLoaded(port.pos()))
                    return insertOrClick(m, level, port.pos(), ANY_FACE, p.key, p.left, 0);
            }
            // no hatch has this number: a controller with no items has nowhere else to put it (its GUI is not the place)
            if (!named && noController && ports.stream().anyMatch(port -> port.role() == role))
                only[0] = true;
            return 0;
        }
        if (!fluid && !noController)
            return 0;               // an item with no slot goes into the machine
        // no slot number: the hatches in the order of their numbers, one hatch for each material (a second material
        // of the same kind goes to the next hatch, as an assembly line wants), and not one a numbered material is for
        if (!fluid)
            only[0] = true;         // the controller takes none: the buses only (the click on it would do nothing)
        java.util.Set<Integer> claimed = new java.util.HashSet<>();
        java.util.Set<Pos> used = new java.util.HashSet<>();
        if (m.feed != null)
            for (Part other : m.feed.parts) {
                if (other == p || (other.key.kind() == Key.Kind.FLUID) != fluid)
                    continue;
                if (other.guiSlot > 0)
                    claimed.add(other.guiSlot);
                if (other.hatchPort != null)
                    used.add(other.hatchPort);
            }
        List<IMachine.Port> order = new ArrayList<>();
        for (IMachine.Port port : ports)
            if (port.role() == role && level.isLoaded(port.pos()))
                order.add(port);
        order.sort(java.util.Comparator.comparingInt(IMachine.Port::slot));
        long went = 0;
        for (IMachine.Port port : order) {
            if (p.hatchPort != null ? !port.pos().equals(p.hatchPort) : claimed.contains(port.slot()) || used.contains(port.pos()))
                continue;
            long in = insertOrClick(m, level, port.pos(), ANY_FACE, p.key, p.left - went, 0);
            if (in > 0) {
                p.hatchPort = port.pos();
                went += in;
                break;
            }
        }
        return went;
    }

    /** No face, then every face: a sub-machine is put into however it lets anything in. */
    private static final List<@Nullable Face> ANY_FACE = java.util.Arrays.asList(null, Face.DOWN, Face.UP,
            Face.NORTH, Face.SOUTH, Face.WEST, Face.EAST);

    /**
     * Puts {@code amount} of {@code key} into the block at {@code pos} through its capabilities; an
     * item into a block that has no item capability at all goes in by a right-click with it instead
     * (an altar that only takes what a player puts on it). What the click gives back goes to the network.
     */
    private long insertOrClick(MachineState m, World level, Pos pos, List<@Nullable Face> faces, Key key, long amount, int guiSlot) {
        if (guiSlot > 0 && key.kind() == Key.Kind.ITEM) {
            Key item = key;
            // a GUI slot the pattern names: that slot, nowhere else (an ordered crafting table puts it by place; the machine's
            // own item slots would put it wherever they like)
            Face face = faces.isEmpty() || faces.get(0) == null ? m.access.getMachineFace() : faces.get(0);
            return level.actions().insertViaGui(pos, face, item, amount, guiSlot, (k, n) -> m.overflow.merge(k, n, Long::sum));
        }
        long in = level.storage().insert(pos, faces, key, amount);
        if (in == 0 && key.kind() == Key.Kind.ITEM && !level.storage().hasItemStorage(pos)) {
            Face face = faces.isEmpty() || faces.get(0) == null ? m.access.getMachineFace() : faces.get(0);
            in = level.actions().rightClick(pos, face, key, amount, (k, n) -> m.overflow.merge(k, n, Long::sum));
            if (in == 0)
                // not a block that takes a hand-held item: one that keeps its items inside and shows them in a GUI
                in = level.actions().insertViaGui(pos, face, key, amount, guiSlot, (k, n) -> m.overflow.merge(k, n, Long::sum));
        }
        return in;
    }

    /** The sub-machines of {@code pat} around machine {@code m} ({@link SubMachines}), looked for again once a second. */
    private List<Pos> subsOf(MachineState m, Pattern pat, World level, Pos pos) {
        long now = level.getGameTime();
        if (m.subs != null && m.subsFor == pat && now - m.subsAt < 20)
            return m.subs;
        List<String> kinds = new ArrayList<>();
        if (envNow != null)
            for (int i = 0; i < pat.subMachineCount(); i++) {
                Key k = envNow.index().key(pat.subMachine(i));
                if (k != null && k.kind() == Key.Kind.ITEM)
                    kinds.add(k.id());
            }
        List<Pos> others = new ArrayList<>();
        for (MachineState other : machines.values())
            if (other != m && level.equals(other.access.getMachineWorld()))
                others.add(other.access.getMachinePos());
        m.subs = SubMachines.find(level, pos, m.access.getSubRange(), kinds, others);
        m.subsFor = pat;
        m.subsAt = now;
        return m.subs;
    }

    /** How long the materials of a run may lie in a machine (or in front of it) before it gets another pulse. */
    private static final int PULSE_AGAIN_TICKS = 20;

    /**
     * Another redstone pulse once a second while materials of its runs are still in the machine or in
     * front of it: one shot of an atomic reconstructor converts only what its energy allows, the rest
     * waits for the next.
     */
    private void pulseAgain(MachineState m, World level, ExecutionEnv env) {
        if (m.feed != null || m.inFlight.isEmpty() || level.getGameTime() - m.pulsedAt < PULSE_AGAIN_TICKS)
            return;
        Set<Key> inputs = new HashSet<>();
        boolean inMachine = false;
        for (Flight f : m.inFlight.keySet()) {
            Pattern pat = f.job().snap.pattern(f.pattern());
            inputs.addAll(inputKeys(pat, env));
            inMachine |= holds(m, pat, env);
        }
        boolean lying = false;
        for (Pos cell : m.frontCells)
            lying |= level.actions().holdsAny(cell, inputs);
        if (inMachine || lying) {
            m.access.onRunFed();
            m.pulsedAt = level.getGameTime();
        }
    }

    /**
     * Products lying in the cells in front of machine {@code m} (or the block there): the outputs of
     * its runs that are not one of their materials as well. The cells are forgotten once no run is in it.
     */
    private void pickUpFront(MachineState m, World level, ExecutionEnv env) {
        if (m.frontCells.isEmpty())
            return;
        if (m.inFlight.isEmpty() && m.feed == null) {
            m.frontCells.clear();
            return;
        }
        Set<Key> wanted = new HashSet<>(), inputs = new HashSet<>();
        for (Flight f : m.inFlight.keySet()) {
            Pattern pat = f.job().snap.pattern(f.pattern());
            for (int o = 0; o < pat.outputCount(); o++) {
                Key k = env.index().key(pat.outputItem(o));
                if (k != null && k.kind() == Key.Kind.ITEM)
                    wanted.add(k);
            }
            inputs.addAll(inputKeys(pat, env));
        }
        wanted.removeAll(inputs);
        Set<Key> wantedExact = wanted;
        for (Pos cell : m.frontCells)
            level.actions().pickUp(cell, k -> wantedExact.contains(k) || (!inputs.contains(k) && productAs(m, k, env) != k),
                    (key, n) -> takeProduct(m, key, n, env));
    }

    // ───────────────────────────── debug notes ─────────────────────────────

    private @Nullable ExecutionEnv envNow;
    /** last line per thing, so a state that lasts is logged once, not every tick */
    private final Map<Object, String> taskNotes = new java.util.WeakHashMap<>();
    private final Map<Object, String> feedNotes = new java.util.WeakHashMap<>();
    private final Map<Object, String> outNotes = new java.util.WeakHashMap<>();

    private void note(Map<Object, String> last, Object about, String line) {
        if (debug != null && !line.equals(last.put(about, line)))
            debug.accept(line);
    }

    /** Why a machine pattern's task sends nothing (the paths that would otherwise be silent). */
    private void noteTask(Task t, Pattern pat, String why) {
        if (debug == null || pat.where != PatternLocation.HUB_MACHINE || envNow == null)
            return;
        note(taskNotes, t, "[雙星] 機器樣板 " + nameOf(pat, envNow) + " 沒有派送：" + why);
    }

    private void noteMaterials(Job job, Task t, Pattern pat) {
        if (debug == null || pat.where != PatternLocation.HUB_MACHINE || envNow == null)
            return;
        StringBuilder sb = new StringBuilder("材料不夠");
        for (int s = 0; s < pat.inputCount(); s++) {
            int item = pat.inputItem(s);
            Key key = envNow.index().key(item);
            sb.append("；").append(key == null ? "?" : key.displayName())
                    .append(" 有 ").append(job.inventory.get(item)).append("（無限來源待拿 ").append(job.drawLater.get(item))
                    .append("）、每次要 ").append(pat.inputAmount(s));
        }
        noteTask(t, pat, sb.toString());
    }

    private static String lazyStatus(Job job, Pattern pat, ExecutionEnv env) {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < pat.inputCount(); s++) {
            int item = pat.inputItem(s);
            if (job.drawLater.get(item).signum() <= 0)
                continue;
            Key key = env.index().key(item);
            sb.append(sb.isEmpty() ? "" : "；").append(key == null ? "?" : key.displayName())
                    .append(" 待拿 ").append(job.drawLater.get(item)).append("、已有 ").append(job.inventory.get(item))
                    .append("、現在仍是無限來源=").append(key != null && env.store().isInfinite(key));
        }
        return sb.toString();
    }

    /**
     * The machines of {@code pat} on the grid, working and loaded: the block an interface sits on is
     * that machine, or the machine the player set on the interface is (a multiblock's port, another
     * tier of the same machine).
     */
    private List<MachineState> machinesFor(Pattern pat, ExecutionEnv env) {
        List<MachineState> out = new ArrayList<>();
        for (MachineState m : machines.values()) {
            World level = m.access.getMachineWorld();
            Pos pos = m.access.getMachinePos();
            if (level == null || !m.access.isAccessActive() || !level.isLoaded(pos))
                continue;
            if (isMachine(level.blockItemId(pos), pat, env) || isMachine(idOf(m.access.getMachine()), pat, env))
                out.add(m);
        }
        return out;
    }

    private static @Nullable String idOf(@Nullable Key item) {
        return item == null ? null : item.id();
    }

    private static boolean isMachine(@Nullable String itemId, Pattern pat, ExecutionEnv env) {
        if (itemId == null || itemId.equals("minecraft:air"))
            return false;
        Key machine = env.index().key(pat.machine);
        return machine != null && machine.kind() == Key.Kind.ITEM && machine.id().equals(itemId);
    }

    /** Every key the pattern's materials can be (substitutes too): what "its materials are still in" looks for. */
    private static Set<Key> inputKeys(Pattern pat, ExecutionEnv env) {
        Set<Key> keys = new HashSet<>();
        for (int s = 0; s < pat.inputCount(); s++) {
            Key k = env.index().key(pat.inputItem(s));
            if (k != null)
                keys.add(k);
            for (int a = 0; a < pat.alternativeCount(s); a++) {
                Key alt = env.index().key(pat.alternative(s, a));
                if (alt != null)
                    keys.add(alt);
            }
        }
        return keys;
    }

    /**
     * Materials of the runs in machine {@code m} (or of the last pattern fed; or only of {@code only}) are
     * still in it, on its sub-machines or lying in front of it: a blocking interface waits for them to go.
     */
    private boolean stillHasMaterials(MachineState m, ExecutionEnv env, @Nullable Pattern only) {
        World level = m.access.getMachineWorld();
        if (level == null)
            return false;
        List<Pattern> pats = new ArrayList<>();
        if (only != null)
            pats.add(only);
        else {
            for (Flight f : m.inFlight.keySet())
                pats.add(f.job().snap.pattern(f.pattern()));
            if (m.lastPat != null && !pats.contains(m.lastPat))
                pats.add(m.lastPat);
        }
        for (Pattern pat : pats) {
            if (holds(m, pat, env))
                return true;
            Set<Key> keys = inputKeys(pat, env);
            keys.removeAll(m.access.getStockedKeys());
            for (Pos cell : m.frontCells)
                if (level.actions().holdsAny(cell, keys))
                    return true;
            if (m.subs != null)
                for (Pos sub : m.subs)
                    if (level.isLoaded(sub) && level.storage().holdsAny(sub, keys))
                        return true;
        }
        return false;
    }

    /** Some input slot of the machine holds a material of {@code pat} (not one its interface keeps stocked). */
    private static boolean holds(MachineState m, Pattern pat, ExecutionEnv env) {
        World level = m.access.getMachineWorld();
        if (level == null || !level.isLoaded(m.access.getMachinePos()))
            return false;
        Set<Key> keys = inputKeys(pat, env);
        keys.removeAll(m.access.getStockedKeys());
        if (level.storage().holdsAny(m.access.getMachinePos(), keys))
            return true;
        // a multiblock's controller holds nothing: its materials sit in the hatches bound to it (an assembly line's buses)
        for (IMachine.Port port : m.access.ports())
            if ((port.role() == IMachine.PortRole.ITEM_IN || port.role() == IMachine.PortRole.FLUID_IN)
                    && level.isLoaded(port.pos()) && level.storage().holdsAny(port.pos(), keys))
                return true;
        return false;
    }

    /** Machine {@code m} still works on a run of {@code job}'s pattern {@code p}: being fed, or its materials still in. */
    private static boolean busyWith(MachineState m, Job job, int p, ExecutionEnv env) {
        if (m.feed != null && m.feed.job == job && m.feed.pattern == p)
            return true;
        return m.inFlight.containsKey(new Flight(job, p)) && holds(m, job.snap.pattern(p), env);
    }

    /**
     * Sends up to {@code n} runs to the pattern's machines, spread evenly over the ones free to take
     * a run (not feeding one; a blocking interface also waits until the previous pattern's
     * materials are gone). A machine gets its next run in the same tick as long as each goes in
     * whole. A pattern run for probable outputs sends its next batch only once its machines have
     * none of its materials left. Returns the runs started.
     */
    private Amount dispatchMachines(Job job, Task t, Pattern pat, Amount n, @Nullable ChanceProgress chance, ExecutionEnv env) {
        List<MachineState> all = machinesFor(pat, env);
        lastDispatchNote = "找不到機器";
        if (all.isEmpty())
            return Amount.ZERO;
        lastDispatchNote = "上一批機率樣板還沒做完";
        if (chance != null && chance.runsDone.signum() > 0)
            for (MachineState m : all)
                if (busyWith(m, job, t.pattern, env))
                    return Amount.ZERO;     // the last batch is not all in yet
        List<MachineState> free = new ArrayList<>();
        for (MachineState m : all) {
            if (m.feed != null || m.access.getInsertMode() == IMachine.InsertMode.OUTPUT)
                continue;
            IMachine.BlockMode block = m.access.getBlockMode();
            if (block == IMachine.BlockMode.FULL && stillHasMaterials(m, env, null))
                continue;   // full blocking: the last run's materials are not all used up yet
            if (block == IMachine.BlockMode.BLOCKING && m.lastPat != null && m.lastPattern != t.pattern
                    && stillHasMaterials(m, env, m.lastPat))
                continue;   // blocking: the previous pattern's materials are still there
            free.add(m);
        }
        lastDispatchNote = all.size() + " 台機器都在餵料或阻擋中";
        if (free.isEmpty())
            return Amount.ZERO;
        lastDispatchNote = all.size() + " 台機器、" + free.size() + " 台空著";
        if (chance != null && !chance.expected) {
            chance.need.forEach((item, a) -> expect(job, item, a, env.nowNanos()));
            chance.expected = true;
        }
        long want = n.saturatedLong();
        long sent = 0;
        int k = free.size();
        long share = want / k, extra = want % k;
        for (int i = 0; i < k && sent < want; i++) {
            MachineState m = free.get((i + rotate) % k);
            long runs = share + (i < extra ? 1 : 0);
            if (m.access.getBlockMode() == IMachine.BlockMode.FULL)
                runs = Math.min(runs, 1);       // one run, then wait until it is used up
            for (long c = 0; c < runs; c++) {
                if (!startRun(job, t, pat, m, env)) {
                    lastDispatchNote += "，訂單裡的材料不夠了";
                    return Amount.of(sent);     // out of materials
                }
                sent++;
                if (chance != null)
                    chance.ran(Amount.ONE);
                World level = m.access.getMachineWorld();
                if (level != null)
                    feed(m, level, m.access.getMachinePos());
                if (m.feed != null) {
                    lastDispatchNote += "，機器放不下：" + leftOf(m.feed, level, m.access.getMachinePos(), debug != null);
                    break;                      // the machine takes the rest over the next ticks
                }
            }
        }
        return Amount.of(sent);
    }

    /** Why the last machine dispatch sent what it sent, for the debug log. */
    private String lastDispatchNote = "";
    private final Map<Task, String> lastDebugLine = new java.util.WeakHashMap<>();

    private static String nameOf(Pattern pat, ExecutionEnv env) {
        for (int o = 0; o < pat.outputCount(); o++)
            if (!pat.isByproduct(o)) {
                Key k = env.index().key(pat.outputItem(o));
                return k == null ? "?" : k.displayName();
            }
        return "?";
    }

    /** What did not go in, and why: each face's simulated insert and what the machine holds, so the log says it at once. */
    private static String leftOf(Feed f, World level, Pos pos, boolean detailed) {
        StringBuilder sb = new StringBuilder();
        for (Part p : f.parts)
            if (p.left > 0) {
                sb.append(sb.isEmpty() ? "" : "、").append(p.key.displayName()).append(" 還有 ").append(p.left)
                        .append(" 放不進去（").append(p.faces.stream().map(d -> d == null ? "不指定面" : d.getName())
                                .collect(java.util.stream.Collectors.joining("、"))).append("）");
                if (detailed)       // only with the detailed engine log on: it asks the machine about every slot
                    try {
                        sb.append("〔").append(level.storage().describeInsert(pos, p.faces, p.key, p.left)).append("〕");
                    } catch (RuntimeException e) {
                        sb.append("〔看不到機器：").append(e.getClass().getSimpleName()).append("〕");
                    }
            }
        return sb.toString();
    }

    /** Takes one run's materials out of the job and starts feeding them to {@code m}. */
    private boolean startRun(Job job, Task t, Pattern pat, MachineState m, ExecutionEnv env) {
        List<@Nullable Map<Key, Long>> inputs = extractInputs(job, pat, env, m);
        if (inputs == null)
            return false;
        Feed f = new Feed(job, t.pattern);
        for (int s = 0; s < inputs.size(); s++) {
            if (inputs.get(s) == null)
                continue;
            int role = pat.inputRole(s);
            List<@Nullable Face> faces = facesFor(m.access, pat.inputFaces(s));
            for (Map.Entry<Key, Long> e : inputs.get(s).entrySet()) {
                if (role == InputRoles.TOOL) {
                    f.tools.add(e.getKey());
                    continue;
                }
                f.parts.add(new Part(e.getKey(), e.getValue(), faces,
                        InputTargets.resolve(pat.inputTarget(s), pat.subMachineCount() > 0, s == 0), role, pat.inputGuiSlot(s)));
                f.total += e.getValue();
                // a kept catalyst is expected back once, when its batch is done, not with every run
                if (role == InputRoles.CATALYST)
                    expect(job, env.index().idOf(e.getKey()), Amount.of(e.getValue()), env.nowNanos());
            }
        }
        m.feed = f;
        m.lastPattern = t.pattern;
        m.lastPat = pat;
        m.inFlight.merge(new Flight(job, t.pattern), Amount.ONE, Amount::add);
        expectOutputs(job, t, pat, 1, env.nowNanos());
        return true;
    }

    /**
     * The faces a material goes in through: the pattern's faces for it when it gives any (unless the interface lets
     * the machine choose), else the interface's own face, or no face for "any".
     */
    private static List<@Nullable Face> facesFor(IMachine a, int patternFaces) {
        List<@Nullable Face> out = new ArrayList<>();
        if (a.getInsertMode() == IMachine.InsertMode.ANY) {
            out.add(null);
            return out;
        }
        out.addAll(InputFaces.faces(patternFaces));
        if (out.isEmpty())
            out.add(a.getMachineFace());
        return out;
    }

    /** A probable output that came back: counts for the machine pattern waiting for it; enough stops it. */
    private void chanceReturned(Job job, int item, Amount got) {
        for (Map.Entry<Integer, ChanceProgress> e : job.chances.entrySet()) {
            Pattern pat = job.snap.pattern(e.getKey());
            ChanceProgress c = e.getValue();
            if (pat.where != PatternLocation.HUB_MACHINE || !c.need.contains(item))
                continue;
            c.gave(item, got);
            if (c.satisfied())
                for (Task t : job.tasks)
                    if (t.pattern == e.getKey())
                        t.remaining = Amount.ZERO;   // the materials of the runs left over go back at the end
            return;
        }
    }

    /**
     * A machine pattern run for probable outputs with every planned run sent and its machines
     * done with its materials, still short: 機率產物不足. What it waits for stays expected, so late
     * products, an insertion into the network or "pull from network" still fill it.
     */
    private void chanceCheckMachine(Job job, int p, ChanceProgress c, ExecutionEnv env) {
        Pattern pat = job.snap.pattern(p);
        if (c.marked || !c.expected || pat.where != PatternLocation.HUB_MACHINE || c.satisfied())
            return;
        for (Task t : job.tasks)
            if (t.pattern == p && t.remaining.signum() > 0)
                return;
        for (MachineState m : machines.values())
            if (busyWith(m, job, p, env))
                return;
        c.shortfall().forEach(job.chanceShort::add);
        c.marked = true;
    }

    /**
     * What the machine of interface {@code a} is doing, for its screen: the run being fed (amounts in
     * and to go in), else the runs in it whose products are not all back; null when it does neither.
     * {@code job} and {@code pattern} are the run fed, else the first run in it.
     */
    public record MachineStatus(Job job, int pattern, boolean feeding, long fed, long total, Amount runsIn) {
    }

    public @Nullable MachineStatus machineStatus(IMachine a) {
        MachineState m = machines.get(a);
        if (m == null)
            return null;
        Amount runsIn = Amount.ZERO;
        for (Amount r : m.inFlight.values())
            runsIn = runsIn.add(r);
        if (m.feed != null)
            return new MachineStatus(m.feed.job, m.feed.pattern, true, m.feed.fed, m.feed.total, runsIn);
        if (m.inFlight.isEmpty())
            return null;
        Flight first = m.inFlight.keySet().iterator().next();
        return new MachineStatus(first.job(), first.pattern(), false, 0, 0, runsIn);
    }

    /** Snapshot of the window table, for debugging screens. */
    public Map<String, Amount> windowSizes() {
        Map<String, Amount> m = new HashMap<>();
        windows.forEach((k, w) -> m.put(k.provider() + "#" + k.pattern(), w.size));
        return m;
    }
}
