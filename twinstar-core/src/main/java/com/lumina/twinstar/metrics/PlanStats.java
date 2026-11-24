package com.lumina.twinstar.metrics;

/**
 * The last few plans' times and whether they ran into the soft limit (stopped improving) or the hard
 * limit (timed out). Written by the engine thread, read by the server thread.
 */
public final class PlanStats {
    /** How many plans the viewer looks back over. */
    public static final int RECENT = 20;

    private final long[] nanos = new long[RECENT];
    private final byte[] outcome = new byte[RECENT];      // 0 fine, 1 soft limit, 2 hard limit
    private int count;                                    // plans so far (the ring's write position is count % RECENT)
    private volatile Summary summary = new Summary(0, 0, 0, 0, 0);

    /** @param plans in the window; {@code lastNanos} / {@code maxNanos} their times; hits of each limit */
    public record Summary(int plans, long lastNanos, long maxNanos, int softHits, int hardHits) {
    }

    public synchronized void record(long took, boolean softHit, boolean hardHit) {
        int at = count++ % RECENT;
        nanos[at] = took;
        outcome[at] = (byte) (hardHit ? 2 : softHit ? 1 : 0);
        int n = Math.min(count, RECENT), soft = 0, hard = 0;
        long max = 0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, nanos[i]);
            if (outcome[i] == 2)
                hard++;
            else if (outcome[i] == 1)
                soft++;
        }
        summary = new Summary(n, took, max, soft, hard);
    }

    public Summary summary() {
        return summary;
    }
}
