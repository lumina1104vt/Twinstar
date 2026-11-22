package com.lumina.twinstar.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules that turn the numbers into a rating: normal / high / very high. Every check has two
 * limits; the rating is the worst check. The limits come from what was measured on a real
 * pack-sized graph (see {@code MemoryEstimateTest} and {@code PerfProbeTest}): about 640 bytes per
 * pattern including its items, a full rebuild of 10,000 patterns about 80 ms, an update allocating
 * about 4× the data, a job about 220 bytes per kind of item. A limit is set at
 * roughly 3× and 10× of what a healthy engine shows, so "high" means "look at this" and "very high"
 * means "something is wrong".
 */
public final class Ratings {
    public static final long KB = 1024, MB = 1024 * KB;
    public static final long MS = 1_000_000L;

    // theory layer
    public static final long THEORY_SIZE_HIGH = 64 * MB, THEORY_SIZE_VERY = 256 * MB;
    public static final long THEORY_PATTERN_HIGH = 2 * KB, THEORY_PATTERN_VERY = 8 * KB;
    public static final long REBUILD_HIGH = 1000 * MS, REBUILD_VERY = 3000 * MS;
    public static final long INCREMENTAL_HIGH = 100 * MS, INCREMENTAL_VERY = 500 * MS;
    /**
     * bytes one update allocated ÷ the size of the data. Measured: a rebuild allocates about 4× the data
     * (and so does an update that cascades through the whole graph, like a raw material's price moving).
     */
    public static final double CHURN_HIGH = 12, CHURN_VERY = 40;
    public static final long PLAN_HIGH = 500 * MS, PLAN_VERY = 1000 * MS;
    /** size growth ÷ data growth, ×1000 */
    public static final int GROWTH_HIGH = 1500, GROWTH_VERY = 3000;

    // hub
    public static final long HUB_SIZE_HIGH = 64 * MB, HUB_SIZE_VERY = 256 * MB;
    public static final long HUB_ITEM_HIGH = KB, HUB_ITEM_VERY = 4 * KB;
    public static final long TICK_AVG_HIGH = 4 * MS, TICK_AVG_VERY = 12 * MS;
    public static final long TICK_MAX_HIGH = 25 * MS, TICK_MAX_VERY = 50 * MS;
    public static final long ALLOC_HIGH = 64 * MB, ALLOC_VERY = 256 * MB;
    public static final long RESIDUAL_HIGH = 1 * MB, RESIDUAL_VERY = 16 * MB;
    public static final long OLD_SNAPSHOT_HIGH = 64 * MB, OLD_SNAPSHOT_VERY = 256 * MB;

    private Ratings() {
    }

    private static void check(List<Verdict.Reason> out, String key, double value, double high, double very) {
        out.add(new Verdict.Reason(key, Rating.of(value, high, very), value, high, very));
    }

    /** The theory layer: its size, how fast it updates, what that allocates, how plans go, how it grows. */
    public static Verdict theory(TheoryReport r) {
        List<Verdict.Reason> c = new ArrayList<>();
        if (r.memoryReady()) {
            check(c, "size", r.totalBytes(), THEORY_SIZE_HIGH, THEORY_SIZE_VERY);
            if (r.patterns() >= 100)       // a tiny graph's fixed parts would make the average meaningless
                check(c, "density", r.bytesPerPattern(), THEORY_PATTERN_HIGH, THEORY_PATTERN_VERY);
            if (r.growthPermille() >= 0)
                check(c, "growth", r.growthPermille(), GROWTH_HIGH, GROWTH_VERY);
        }
        if (r.lastRebuildNanos() > 0)
            check(c, "rebuild", r.lastRebuildNanos(), REBUILD_HIGH, REBUILD_VERY);
        if (r.incrMaxNanos() > 0)
            check(c, "incremental", r.incrMaxNanos(), INCREMENTAL_HIGH, INCREMENTAL_VERY);
        if (r.memoryReady() && r.lastFlushAlloc() >= 0 && r.totalBytes() > 0 && r.lastFlushNanos() > 0) {
            double churn = (double) r.lastFlushAlloc() / r.totalBytes();
            check(c, "churn", churn, CHURN_HIGH, CHURN_VERY);
        }
        if (r.plans() > 0) {
            check(c, "plan_time", r.maxPlanNanos(), PLAN_HIGH, PLAN_VERY);
            check(c, "plan_soft", r.softHits(), 1, 5);
            check(c, "plan_hard", r.hardHits(), 0.5, 1);       // one timeout is already "very high"
        }
        return Verdict.of(c);
    }

    /** The hub: its size, its idle residue, the executor's time and allocation, theory versions held alive. */
    public static Verdict hub(HubReport r) {
        List<Verdict.Reason> c = new ArrayList<>();
        check(c, "size", r.totalBytes(), HUB_SIZE_HIGH, HUB_SIZE_VERY);
        if (r.trackedItems() >= 100)
            check(c, "density", r.bytesPerItem(), HUB_ITEM_HIGH, HUB_ITEM_VERY);
        if (r.jobs() == 0 && r.idleResidualBytes() >= 0)
            check(c, "residual", r.idleResidualBytes(), RESIDUAL_HIGH, RESIDUAL_VERY);
        else if (r.jobs() == 0)
            check(c, "residual", r.totalBytes(), RESIDUAL_HIGH, RESIDUAL_VERY);
        if (r.tickAvgNanos() > 0) {
            check(c, "tick_avg", r.tickAvgNanos(), TICK_AVG_HIGH, TICK_AVG_VERY);
            check(c, "tick_max", r.tickMaxNanos(), TICK_MAX_HIGH, TICK_MAX_VERY);
        }
        if (r.allocPerSecond() >= 0 && r.tickAvgNanos() > 0)
            check(c, "alloc", r.allocPerSecond(), ALLOC_HIGH, ALLOC_VERY);
        if (r.oldSnapshots() > 0)
            check(c, "old_snapshots", r.oldSnapshotBytes(), OLD_SNAPSHOT_HIGH, OLD_SNAPSHOT_VERY);
        return Verdict.of(c);
    }
}
