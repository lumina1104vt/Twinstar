package com.lumina.twinstar.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.NumberFormat;

/**
 * Amounts as text, any size. Short form uses the SI-style units AE2 also uses, extended past E:
 * K M G T P E Z Y R Q (10³ … 10³⁰); beyond 1000Q it falls back to scientific notation.
 */
public final class BigAmountFormat {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /** index = power of 1000 */
    static final String[] UNITS = {"", "K", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};

    private BigAmountFormat() {
    }

    public static boolean fitsLong(BigInteger v) {
        return v.compareTo(LONG_MAX) <= 0 && v.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) >= 0;
    }

    /** Short form, three significant digits at most: {@code 999}, {@code 1.23K}, {@code 45.6Z}, {@code 10Q}, {@code 1.23e40}. */
    public static String unitForm(BigInteger v) {
        return unitForm(new BigDecimal(v));
    }

    /** Short form of a value in display units ({@code perUnit} base units each, e.g. 1000 mB = 1 B). */
    public static String shortForm(BigInteger v, int perUnit, String unitSymbol) {
        BigDecimal d = new BigDecimal(v).divide(BigDecimal.valueOf(Math.max(1, perUnit)), MathContext.DECIMAL128);
        return unitForm(d) + unitSymbol;
    }

    static String unitForm(BigDecimal d) {
        if (d.signum() < 0)
            return "-" + unitForm(d.negate());
        if (d.compareTo(BigDecimal.valueOf(1000)) < 0)
            return plain(d.round(new MathContext(3, RoundingMode.DOWN)));
        int digits = d.precision() - d.scale();          // digits before the decimal point
        int group = (digits - 1) / 3;
        if (group >= UNITS.length) {
            BigDecimal mant = d.round(new MathContext(3, RoundingMode.DOWN));
            int exp = digits - 1;
            return mant.movePointLeft(exp).setScale(2, RoundingMode.DOWN).toPlainString() + "e" + exp;
        }
        BigDecimal scaled = d.movePointLeft(3 * group).round(new MathContext(3, RoundingMode.DOWN));
        return plain(scaled) + UNITS[group];
    }

    private static String plain(BigDecimal d) {
        BigDecimal s = d.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    /**
     * Every digit, grouped (tooltips), e.g. {@code 10,000,000,000,000,000,000,000}. Beyond
     * {@link #FULL_DIGITS} digits a line of digits is no longer readable (1e10000 has 10,001):
     * {@code 1.23456789012345678901234567890×10^10000} instead.
     */
    public static String fullForm(BigInteger v, int perUnit, String unitSymbol) {
        BigDecimal d = new BigDecimal(v);
        if (perUnit > 1)
            d = d.divide(BigDecimal.valueOf(perUnit), new MathContext(Math.max(34, d.precision() + 1)));
        int exponent = d.precision() - d.scale() - 1;
        if (exponent < FULL_DIGITS)
            return NumberFormat.getInstance().format(d.stripTrailingZeros()) + unitSymbol;
        BigDecimal mantissa = d.movePointLeft(exponent).round(new MathContext(30)).stripTrailingZeros();
        return mantissa.toPlainString() + "×10^" + exponent + unitSymbol;
    }

    private static final int FULL_DIGITS = 64;
}
