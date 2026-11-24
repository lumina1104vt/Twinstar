package com.lumina.twinstar.metrics;

/**
 * How worrying a number is. Shown as an icon with a different SHAPE for each level plus a word, never
 * by colour alone.
 */
public enum Rating {
    NORMAL, HIGH, VERY_HIGH;

    public Rating worst(Rating other) {
        return other.ordinal() > ordinal() ? other : this;
    }

    /** {@code value} against two limits: {@code high} and {@code veryHigh} (both inclusive). */
    public static Rating of(double value, double high, double veryHigh) {
        return value >= veryHigh ? VERY_HIGH : value >= high ? HIGH : NORMAL;
    }
}
