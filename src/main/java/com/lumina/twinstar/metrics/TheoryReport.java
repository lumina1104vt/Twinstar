package com.lumina.twinstar.metrics;

/**
 * What the theory viewer shows about the theory layer's own Java memory and speed (all measured on
 * the server and sent as numbers; the viewer rates them with {@link Ratings#theory}).
 *
 * @param memoryReady        false until the first memory estimate of a version is done (the other sizes are then 0)
 * @param patternBytes       the patterns: arrays of items, amounts and kinds per pattern
 * @param itemBytes          per-item flat arrays (level, loop, cost, best route) and the route lists (who makes / uses it)
 * @param bomBytes           the flattened raw-material lists (per item and per pattern)
 * @param otherBytes         policy loops, the layer's own working copy and bookkeeping
 * @param flushes            theory updates so far that changed something
 * @param rebuilds           of them, full rebuilds
 * @param lastFlushNanos     the last update's time (background thread)
 * @param lastRebuilt        the last update was a full rebuild
 * @param lastRebuildNanos   the last full rebuild's time (0 = none yet)
 * @param incrMaxNanos       the slowest of the last incremental updates
 * @param lastFlushAlloc     bytes the last update allocated (−1 unknown)
 * @param plans              orders planned recently (the last {@link PlanStats#RECENT})
 * @param lastPlanNanos      the last plan's time
 * @param maxPlanNanos       the slowest of the recent plans
 * @param softHits           recent plans that ran into the soft limit (stopped improving)
 * @param hardHits           recent plans that ran into the hard limit (timed out)
 * @param growthPermille     how fast the size grows compared with the number of items and patterns, ×1000 (1000 = in step, −1 = not enough data)
 */
public record TheoryReport(boolean memoryReady, int items, int patterns, int loops,
                           long patternBytes, long itemBytes, long bomBytes, long otherBytes,
                           long flushes, long rebuilds, long lastFlushNanos, boolean lastRebuilt, long lastRebuildNanos,
                           long incrMaxNanos, long lastFlushAlloc,
                           int plans, long lastPlanNanos, long maxPlanNanos, int softHits, int hardHits,
                           int growthPermille) {
    public static final TheoryReport NONE = new TheoryReport(false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, -1,
            0, 0, 0, 0, 0, -1);

    public long totalBytes() {
        return patternBytes + itemBytes + bomBytes + otherBytes;
    }

    /** Average bytes per item (0 when none). */
    public long bytesPerItem() {
        return items <= 0 ? 0 : totalBytes() / items;
    }

    /** Average bytes per pattern (0 when none). */
    public long bytesPerPattern() {
        return patterns <= 0 ? 0 : totalBytes() / patterns;
    }
}
