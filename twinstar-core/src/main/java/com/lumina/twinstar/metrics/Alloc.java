package com.lumina.twinstar.metrics;

import java.lang.management.ManagementFactory;

/**
 * Bytes the calling thread has allocated so far (HotSpot counts them in the thread's allocation
 * buffer, so reading it costs about as much as {@code System.nanoTime()}). The difference over a piece
 * of work is what it allocated: a cheap way to see churn (short-lived objects, boxing) without a
 * profiler. {@code -1} where the JVM cannot tell.
 */
public final class Alloc {
    private static final com.sun.management.ThreadMXBean BEAN = bean();

    private Alloc() {
    }

    private static com.sun.management.ThreadMXBean bean() {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean b && b.isThreadAllocatedMemorySupported()) {
                b.setThreadAllocatedMemoryEnabled(true);
                return b;
            }
        } catch (Throwable ignored) {
            // no com.sun.management here (another JVM): the numbers just stay unknown
        }
        return null;
    }

    public static boolean supported() {
        return BEAN != null;
    }

    /** Allocated by the calling thread since it started, or −1. */
    public static long threadBytes() {
        if (BEAN == null)
            return -1;
        try {
            return BEAN.getCurrentThreadAllocatedBytes();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** {@code now − before}, or −1 when either is unknown. */
    public static long since(long before) {
        long now = threadBytes();
        return before < 0 || now < 0 ? -1 : now - before;
    }
}
