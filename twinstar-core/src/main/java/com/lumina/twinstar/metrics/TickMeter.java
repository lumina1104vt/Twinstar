package com.lumina.twinstar.metrics;

/**
 * Time and allocation of something that runs every tick, summed over one-second windows: the last
 * full window is what {@link #last()} gives. Only ticks that did work count ({@code active}), so an
 * idle hub does not water the average down. One writer (the server thread); readers see the last window.
 */
public final class TickMeter {
    public record Window(int ticks, long avgNanos, long maxNanos, long allocPerSecond) {
        public static final Window NONE = new Window(0, 0, 0, -1);
    }

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private long start;
    private int ticks;
    private long nanos, alloc, max;
    private boolean allocKnown = true;
    private volatile Window last = Window.NONE;

    /**
     * @param active   the tick did work
     * @param took     its time
     * @param alloc    bytes it allocated (−1 unknown)
     * @param nowNanos {@code System.nanoTime()}
     */
    public void record(boolean active, long took, long alloc, long nowNanos) {
        if (start == 0)
            start = nowNanos;
        if (active) {
            ticks++;
            nanos += took;
            max = Math.max(max, took);
            if (alloc < 0)
                allocKnown = false;
            else
                this.alloc += alloc;
        }
        long span = nowNanos - start;
        if (span >= WINDOW_NANOS) {
            last = ticks == 0 ? Window.NONE
                    : new Window(ticks, nanos / ticks, max, allocKnown ? (long) (this.alloc * (double) WINDOW_NANOS / span) : -1);
            start = nowNanos;
            ticks = 0;
            nanos = this.alloc = max = 0;
            allocKnown = true;
        }
    }

    public Window last() {
        return last;
    }
}
