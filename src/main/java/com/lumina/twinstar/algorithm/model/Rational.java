package com.lumina.twinstar.algorithm.model;

import java.math.BigInteger;

/** Exact fraction, always reduced with a positive denominator (state equation, macro patterns). */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    public final BigInteger num;
    public final BigInteger den;

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    /** Java memory of this fraction (see {@link Mem}); 0 for the shared ZERO and ONE. */
    public long memoryBytes() {
        if (this == ZERO || this == ONE)
            return 0;
        return Mem.obj(2 * 4) + smallBig(num) + smallBig(den);
    }

    /** {@code BigInteger.valueOf} hands out shared objects for −16..16. */
    private static long smallBig(BigInteger v) {
        return v.bitLength() <= 4 ? 0 : Mem.bigInteger(v);
    }

    public static Rational of(BigInteger num, BigInteger den) {
        if (den.signum() == 0)
            throw new ArithmeticException("zero denominator");
        if (den.signum() < 0) {
            num = num.negate();
            den = den.negate();
        }
        BigInteger g = num.gcd(den);
        if (!g.equals(BigInteger.ONE) && g.signum() != 0) {
            num = num.divide(g);
            den = den.divide(g);
        }
        if (num.signum() == 0)
            return ZERO;
        return new Rational(num, den);
    }

    public static Rational of(long v) {
        return of(BigInteger.valueOf(v), BigInteger.ONE);
    }

    public static Rational of(Amount a) {
        return of(a.bigValue(), BigInteger.ONE);
    }

    /** Exact value of a double (AE2 reports energy as double); NaN and ∞ are not allowed. */
    public static Rational of(double v) {
        if (!Double.isFinite(v))
            throw new ArithmeticException("not a finite number: " + v);
        java.math.BigDecimal d = new java.math.BigDecimal(v);
        if (d.scale() <= 0)
            return of(d.toBigIntegerExact(), BigInteger.ONE);
        return of(d.unscaledValue(), BigInteger.TEN.pow(d.scale()));
    }

    /** Largest integer ≤ this. */
    public BigInteger floor() {
        BigInteger[] qr = num.divideAndRemainder(den);
        return qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    public Rational min(Rational o) {
        return compareTo(o) <= 0 ? this : o;
    }

    /** Clamped to ±Double.MAX_VALUE, for AE2's double energy API. */
    public double toDoubleSaturated() {
        double d = toDouble();
        if (Double.isNaN(d))
            return 0;
        return Math.max(-Double.MAX_VALUE, Math.min(Double.MAX_VALUE, d));
    }

    public int signum() {
        return num.signum();
    }

    public boolean isZero() {
        return num.signum() == 0;
    }

    public Rational add(Rational o) {
        if (den.equals(o.den))
            return of(num.add(o.num), den);
        return of(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    public Rational sub(Rational o) {
        return add(o.negate());
    }

    public Rational mul(Rational o) {
        return of(num.multiply(o.num), den.multiply(o.den));
    }

    public Rational div(Rational o) {
        return of(num.multiply(o.den), den.multiply(o.num));
    }

    public Rational negate() {
        return new Rational(num.negate(), den);
    }

    /** Smallest integer ≥ this. */
    public BigInteger ceil() {
        BigInteger[] qr = num.divideAndRemainder(den);
        return qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
    }

    public double toDouble() {
        return new java.math.BigDecimal(num).divide(new java.math.BigDecimal(den), java.math.MathContext.DECIMAL64).doubleValue();
    }

    @Override
    public int compareTo(Rational o) {
        return num.multiply(o.den).compareTo(o.num.multiply(den));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Rational r && num.equals(r.num) && den.equals(r.den);
    }

    @Override
    public int hashCode() {
        return num.hashCode() * 31 + den.hashCode();
    }

    @Override
    public String toString() {
        return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
    }
}
