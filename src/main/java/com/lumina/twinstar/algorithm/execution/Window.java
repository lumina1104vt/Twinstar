package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;

import java.util.Arrays;

/**
 * Adaptive batch window for one machine × pattern (TCP slow start style): how many copies to push
 * to it per tick. No upper bound: "unlimited" means push everything it takes.
 */
public final class Window {
    private static final int SAMPLES = 16;

    Amount size = Amount.ONE;
    Amount lastOk = Amount.ZERO;
    final long[] rttSamples = new long[SAMPLES];
    /** samples ever recorded; the valid ones are the last min(rttCount, 16) */
    int rttCount;
    long lastSendNanos = -1;

    /** Java memory of this window with its timing samples (see {@link com.lumina.twinstar.algorithm.model.Mem}). */
    long memoryBytes() {
        return com.lumina.twinstar.algorithm.model.Mem.obj(4 + 4 + 4 + 4 + 8) + size.memoryBytes() + lastOk.memoryBytes()
                + com.lumina.twinstar.algorithm.model.Mem.longs(rttSamples);
    }

    /** All taken: double. Part taken: the amount taken. Refused: the last good amount, or half. */
    void onPushResult(Amount requested, Amount accepted) {
        if (accepted.compareTo(requested) >= 0)
            size = size.mul(2);
        else if (accepted.signum() > 0)
            size = accepted;
        else
            size = lastOk.signum() > 0 ? lastOk : size.half().max(Amount.ONE);
        if (accepted.signum() > 0)
            lastOk = accepted;
    }

    void recordRoundTrip(long nanos) {
        rttSamples[rttCount % SAMPLES] = nanos;
        rttCount++;
    }

    /** Median round trip, or −1 without data. */
    public long medianRoundTrip() {
        int n = Math.min(rttCount, SAMPLES);
        if (n == 0)
            return -1;
        long[] s = Arrays.copyOf(rttSamples, n);
        Arrays.sort(s);
        return s[n / 2];
    }

    public Amount size() {
        return size;
    }
}
