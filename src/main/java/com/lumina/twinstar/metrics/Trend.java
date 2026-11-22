package com.lumina.twinstar.metrics;

/**
 * Does the data grow in step with what it describes? Keeps the last few (elements, bytes) samples
 * and compares the newest with the earlier ones: bytes per element should stay about the same; when
 * the size grows much faster than the number of items and patterns, something is not linear (or
 * something is not let go).
 */
public final class Trend {
    private static final int KEEP = 12;
    private final long[] elements = new long[KEEP];
    private final long[] bytes = new long[KEEP];
    private int count;

    /** Takes a sample (callers add once per theory version). */
    public synchronized void add(long elementCount, long byteCount) {
        if (elementCount <= 0)
            return;
        int at = count++ % KEEP;
        elements[at] = elementCount;
        bytes[at] = byteCount;
    }

    /**
     * (bytes growth) ÷ (element growth) × 1000 between the newest sample and the earlier samples at
     * least 25 % smaller (the worst of them), or −1 when there is none. 1000 = linear; 2000 = the size
     * grew twice as fast as the data.
     */
    public synchronized int growthPermille() {
        int n = Math.min(count, KEEP);
        if (n < 2)
            return -1;
        int newest = (count - 1) % KEEP;
        double worst = -1;
        for (int i = 0; i < n; i++) {
            if (i == newest || elements[i] * 5 > elements[newest] * 4)
                continue;
            double growth = ((double) bytes[newest] / bytes[i]) / ((double) elements[newest] / elements[i]);
            worst = Math.max(worst, growth);
        }
        return worst < 0 ? -1 : (int) Math.min(1_000_000, Math.round(worst * 1000));
    }
}
