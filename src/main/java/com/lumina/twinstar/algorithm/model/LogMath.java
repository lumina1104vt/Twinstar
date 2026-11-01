package com.lumina.twinstar.algorithm.model;

/**
 * Costs and BOM amounts are stored as log10 so 10^-1000 and 10^1000 both fit in a double.
 * Multiplying becomes adding; adding uses log-sum-exp. −∞ stands for 0, +∞ for "cannot be made".
 */
public final class LogMath {
    public static final double ZERO = Double.NEGATIVE_INFINITY;
    public static final double INF = Double.POSITIVE_INFINITY;

    private LogMath() {
    }

    /** log10(10^a + 10^b) */
    public static double add(double a, double b) {
        if (a == ZERO)
            return b;
        if (b == ZERO)
            return a;
        if (a == INF || b == INF)
            return INF;
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return hi + Math.log10(1 + Math.pow(10, lo - hi));
    }

    /** log10(10^a × 10^b); 0 × ∞ is taken as 0 (an unused input does not make a route impossible). */
    public static double mul(double a, double b) {
        if (a == ZERO || b == ZERO)
            return ZERO;
        return a + b;
    }

    public static double of(double v) {
        return v <= 0 ? ZERO : Math.log10(v);
    }

    public static boolean isFinite(double logV) {
        return logV != INF;
    }

    /** a is "the same" as b within relative tolerance eps (in plain numbers, not logs). */
    public static boolean sameWithin(double a, double b, double eps) {
        if (a == b)
            return true;
        if (Double.isInfinite(a) || Double.isInfinite(b))
            return false;
        return Math.abs(a - b) <= Math.log10(1 + eps);
    }
}
