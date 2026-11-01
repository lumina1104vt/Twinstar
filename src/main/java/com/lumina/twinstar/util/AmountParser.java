package com.lumina.twinstar.util;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Amount input of the twin-star order screen: plain numbers ({@code 64}, {@code 1,234}) and unit
 * suffixes and scientific notation:
 * {@code 64K}, {@code 1.5M}, {@code 3G}, {@code 2T}, {@code 1P}, {@code 3E}, {@code 2e18}, {@code 1.2e3K}.
 * <p>
 * K = 10³, M = 10⁶, G = 10⁹, T = 10¹², P = 10¹⁵, E = 10¹⁸, Z = 10²¹, Y = 10²⁴, R = 10²⁷, Q = 10³⁰
 * (case-insensitive; K–E are the units AE2 shows).
 * An {@code e} followed by digits is an exponent ({@code 2e18}); a trailing {@code E} on its own is
 * the unit ({@code 3E}). A leading {@code =} (AE2's
 * "craft only what is missing") and the separators {@code _ ,} and spaces are ignored. Math
 * expressions ({@code 64*3}) are not read.
 */
public final class AmountParser {
    /**
     * Largest order: 10^MAX_EXPONENT (1e10000 is fine, 1.1e10000 is not). Planning time grows with
     * the size of the numbers, and above this it gets slow; it also keeps a typo like 1e999999999
     * from allocating gigabytes.
     */
    public static final int MAX_EXPONENT = 10_000;
    public static final BigInteger MAX = BigInteger.TEN.pow(MAX_EXPONENT);
    /**
     * Largest amount on the wire, in AE2 units: the field's display unit can be larger (1 bucket =
     * 1000 mB), so an order of 1e10000 buckets is 1e10003 here.
     */
    public static final BigInteger MAX_EXTERNAL = MAX.multiply(BigInteger.valueOf(1000));
    private static final Pattern FORM = Pattern.compile(
            "([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE]([+-]?[0-9]{1,6}))?([kKmMgGtTpPeEzZyYrRqQ])?");
    /** The units AE2 shows (3E = 3 × 10¹⁸), extended to Z Y R Q (10²¹ … 10³⁰) like our display. */
    private static final String SUFFIXES = "kmgtpezyrq";

    private AmountParser() {
    }

    /** The value in the field's display unit, or null when the text is not in this form. */
    public static @Nullable BigDecimal parse(String text) {
        if (text == null)
            return null;
        String s = text.trim();
        if (s.startsWith("="))
            s = s.substring(1);
        s = s.replace("_", "").replace(",", "").replace(" ", "");
        Matcher m = FORM.matcher(s);
        if (!m.matches())
            return null;
        String exp = m.group(2);
        String suffix = m.group(3);
        BigDecimal v = new BigDecimal(m.group(1));
        int power = exp == null ? 0 : Integer.parseInt(exp);
        if (suffix != null)
            power += 3 * (SUFFIXES.indexOf(Character.toLowerCase(suffix.charAt(0))) + 1);
        if ((long) v.precision() - v.scale() + power > MAX_EXPONENT + 1)
            return null;    // too large (checked before building the number): the field shows it as invalid
        v = v.scaleByPowerOfTen(power).stripTrailingZeros();
        if (v.compareTo(new BigDecimal(MAX)) > 0)
            return null;
        if (v.scale() < 0)
            v = v.setScale(0);
        return v;
    }
}
