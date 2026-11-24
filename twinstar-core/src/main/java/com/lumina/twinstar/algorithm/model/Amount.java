package com.lumina.twinstar.algorithm.model;

import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Every quantity in the engine. Holds a {@code long} while the value fits (fast path) and a
 * {@link BigInteger} only beyond that. Immutable.
 * <p>
 * Normalised: a value that fits in a long never has {@code big} set, so each value has exactly one
 * representation and equals / compareTo are exact. Overflow never throws and never truncates; the
 * result is promoted to BigInteger instead.
 */
public final class Amount implements Comparable<Amount> {
    private static final int CACHE_MAX = 1024;
    private static final Amount[] CACHE = new Amount[CACHE_MAX + 1];

    static {
        for (int i = 0; i <= CACHE_MAX; i++)
            CACHE[i] = new Amount(i, null);
    }

    public static final Amount ZERO = CACHE[0];
    public static final Amount ONE = CACHE[1];

    private static final double LOG10_2 = Math.log10(2);

    private final long small;       // valid when big == null
    private final BigInteger big;   // only set when outside the long range

    private Amount(long small, BigInteger big) {
        this.small = small;
        this.big = big;
    }

    public static Amount of(long v) {
        if (v >= 0 && v <= CACHE_MAX)
            return CACHE[(int) v];
        return new Amount(v, null);
    }

    public static Amount of(BigInteger v) {
        if (v.bitLength() <= 63)
            return of(v.longValue());
        return new Amount(0, v);
    }

    /**
     * Java memory of this value (see {@link Mem}); 0 for the shared cached small values, whose
     * holders all point to the same object.
     */
    public long memoryBytes() {
        if (big != null)
            return Mem.obj(8 + 4) + Mem.bigInteger(big);
        return small >= 0 && small <= CACHE_MAX ? 0 : Mem.obj(8 + 4);
    }

    public boolean isBig() {
        return big != null;
    }

    /** @throws ArithmeticException when the value does not fit, so a truncated value is never returned */
    public long longValue() {
        if (big != null)
            throw new ArithmeticException("amount does not fit in a long: " + big);
        return small;
    }

    /** Clamped to the long range; for AE2 APIs that only take longs. */
    public long saturatedLong() {
        if (big == null)
            return small;
        return big.signum() > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
    }

    /** Clamped to the int range. */
    public int saturatedInt() {
        long v = saturatedLong();
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    public BigInteger bigValue() {
        return big != null ? big : BigInteger.valueOf(small);
    }

    public int signum() {
        return big != null ? big.signum() : Long.signum(small);
    }

    public boolean isZero() {
        return big == null && small == 0;
    }

    public boolean isPositive() {
        return signum() > 0;
    }

    public Amount add(Amount o) {
        if (big == null && o.big == null) {
            long r = small + o.small;
            if (((small ^ r) & (o.small ^ r)) >= 0)
                return of(r);
        }
        return of(bigValue().add(o.bigValue()));
    }

    public Amount add(long o) {
        if (big == null) {
            long r = small + o;
            if (((small ^ r) & (o ^ r)) >= 0)
                return of(r);
        }
        return of(bigValue().add(BigInteger.valueOf(o)));
    }

    public Amount sub(Amount o) {
        if (big == null && o.big == null) {
            long r = small - o.small;
            if (((small ^ o.small) & (small ^ r)) >= 0)
                return of(r);
        }
        return of(bigValue().subtract(o.bigValue()));
    }

    public Amount mul(Amount o) {
        if (big == null && o.big == null) {
            long hi = Math.multiplyHigh(small, o.small);
            long lo = small * o.small;
            if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0))
                return of(lo);
        }
        return of(bigValue().multiply(o.bigValue()));
    }

    public Amount mul(long o) {
        return mul(of(o));
    }

    /** Floor division; {@code o} must be positive. */
    public Amount div(Amount o) {
        if (o.signum() <= 0)
            throw new ArithmeticException("divisor must be positive");
        if (big == null && o.big == null)
            return of(Math.floorDiv(small, o.small));
        BigInteger[] qr = bigValue().divideAndRemainder(o.bigValue());
        BigInteger q = qr[0];
        if (qr[1].signum() < 0)
            q = q.subtract(BigInteger.ONE);
        return of(q);
    }

    /** Ceiling division; {@code o} must be positive. */
    public Amount ceilDiv(Amount o) {
        if (o.signum() <= 0)
            throw new ArithmeticException("divisor must be positive");
        if (big == null && o.big == null)
            return of(-Math.floorDiv(-small, o.small));
        BigInteger[] qr = bigValue().divideAndRemainder(o.bigValue());
        BigInteger q = qr[0];
        if (qr[1].signum() > 0)
            q = q.add(BigInteger.ONE);
        return of(q);
    }

    public Amount ceilDiv(long o) {
        return ceilDiv(of(o));
    }

    public Amount min(Amount o) {
        return compareTo(o) <= 0 ? this : o;
    }

    public Amount max(Amount o) {
        return compareTo(o) >= 0 ? this : o;
    }

    /** max(this, 0) */
    public Amount clampNonNegative() {
        return signum() < 0 ? ZERO : this;
    }

    public Amount half() {
        if (big == null)
            return of(small / 2);
        return of(big.shiftRight(1));
    }

    /** log10 of the value; −∞ for zero. Only for estimates, so the precision of a double is enough. */
    public double log10() {
        if (signum() < 0)
            throw new ArithmeticException("log of a negative amount");
        if (big == null)
            return small == 0 ? Double.NEGATIVE_INFINITY : Math.log10(small);
        int shift = big.bitLength() - 62;
        return Math.log10(big.shiftRight(shift).doubleValue()) + shift * LOG10_2;
    }

    /** The largest amount whose log10 is at most {@code log}; ZERO for −∞, and never negative. */
    public static Amount floorOfLog10(double log) {
        if (Double.isNaN(log) || log == Double.NEGATIVE_INFINITY)
            return ZERO;
        if (log < 18)
            return of((long) Math.floor(Math.pow(10, log)));
        if (log == Double.POSITIVE_INFINITY)
            throw new ArithmeticException("infinite amount");
        // 10^log = 10^int * 10^frac; keep 15 significant digits, the rest is beyond double precision anyway
        int whole = (int) Math.floor(log);
        double mant = Math.pow(10, log - whole + 15);
        return of(java.math.BigDecimal.valueOf(mant).movePointRight(whole - 15).setScale(0, RoundingMode.FLOOR).toBigInteger());
    }

    @Override
    public int compareTo(Amount o) {
        if (big == null && o.big == null)
            return Long.compare(small, o.small);
        return bigValue().compareTo(o.bigValue());
    }

    public int compareTo(long o) {
        if (big == null)
            return Long.compare(small, o);
        return big.signum();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Amount o))
            return false;
        if (big == null)
            return o.big == null && small == o.small;
        return big.equals(o.big);
    }

    @Override
    public int hashCode() {
        return big == null ? Long.hashCode(small) : big.hashCode();
    }

    @Override
    public String toString() {
        return big == null ? Long.toString(small) : big.toString();
    }
}
