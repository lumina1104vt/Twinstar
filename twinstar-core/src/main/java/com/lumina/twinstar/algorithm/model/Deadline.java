package com.lumina.twinstar.algorithm.model;

/**
 * Planning time limits. Soft (0.5 s): stop improving and return the best so far, with no notice.
 * Hard (1 s): abort and report a timeout.
 */
public record Deadline(long startNanos, long softNanos, long hardNanos) {
    public static final long DEFAULT_SOFT = 1_000_000_000L;
    public static final long DEFAULT_HARD = 2_000_000_000L;

    /** For tests and tools: never runs out. */
    public static final Deadline NONE = new Deadline(0, Long.MAX_VALUE, Long.MAX_VALUE);

    public static Deadline startingNow() {
        return new Deadline(System.nanoTime(), DEFAULT_SOFT, DEFAULT_HARD);
    }

    public boolean softPassed() {
        return softNanos != Long.MAX_VALUE && System.nanoTime() - startNanos > softNanos;
    }

    /**
     * A part of the work that may use at most {@code budgetNanos} of what is left before the soft
     * limit; the hard limit stays where it is. No limit stays no limit.
     */
    public Deadline within(long budgetNanos) {
        if (softNanos == Long.MAX_VALUE)
            return this;
        long now = System.nanoTime();
        long softLeft = Math.max(0, startNanos + softNanos - now);
        long hard = hardNanos == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, startNanos + hardNanos - now);
        return new Deadline(now, Math.min(budgetNanos, softLeft), hard);
    }

    public void checkHard() {
        if (hardNanos != Long.MAX_VALUE && System.nanoTime() - startNanos > hardNanos)
            throw new PlanTimeoutException();
    }

    public static final class PlanTimeoutException extends RuntimeException {
        public PlanTimeoutException() {
            super("crafting calculation timed out", null, false, false);
        }
    }
}
