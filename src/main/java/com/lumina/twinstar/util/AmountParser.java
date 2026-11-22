package com.lumina.twinstar.util;

import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
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
 * "craft only what is missing") and the separators {@code _ ,} and spaces are ignored.
 * <p>
 * Expressions are read too: {@code + - * / ^} (also {@code x} and the times sign for times), brackets, a sign in
 * front, each number as written above: {@code 64*3}, {@code (2+3)*1.5K}, {@code 2^10}, {@code 10K/4}. {@code ^} binds
 * tightest and goes from the right ({@code 2^3^2} = 2^9), then times and divided by, then plus and minus. A
 * division is worked out to 50 digits; a power takes a whole exponent, 0 or more. Past the largest order, or
 * dividing by 0, it is not an amount.
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
        if (m.matches())
            return number(m);
        if (s.length() > 200)
            return null;
        try {
            Expr e = new Expr(s);
            BigDecimal v = e.expr();
            return e.at != s.length() ? null : finish(v);
        } catch (ArithmeticException | NumberFormatException | IllegalStateException ex) {
            return null;
        }
    }

    private static @Nullable BigDecimal number(Matcher m) {
        String exp = m.group(2);
        String suffix = m.group(3);
        BigDecimal v = new BigDecimal(m.group(1));
        int power = exp == null ? 0 : Integer.parseInt(exp);
        if (suffix != null)
            power += 3 * (SUFFIXES.indexOf(Character.toLowerCase(suffix.charAt(0))) + 1);
        if ((long) v.precision() - v.scale() + power > MAX_EXPONENT + 1)
            return null;    // too large (checked before building the number): the field shows it as invalid
        return finish(v.scaleByPowerOfTen(power));
    }

    /** Tidies a result and refuses what is past the largest order. */
    private static @Nullable BigDecimal finish(BigDecimal v) {
        v = v.stripTrailingZeros();
        if (v.abs().compareTo(new BigDecimal(MAX)) > 0)
            return null;
        if (v.scale() < 0)
            v = v.setScale(0);
        return v;
    }

    /** Digits before the point, for refusing a result before it is built. */
    private static long size(BigDecimal v) {
        return (long) v.precision() - v.scale();
    }

    /** Recursive-descent reader of an expression; throws when it is not one. */
    private static final class Expr {
        final String s;
        int at;

        Expr(String s) {
            this.s = s;
        }

        private boolean eat(char c) {
            if (at < s.length() && s.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        BigDecimal expr() {
            BigDecimal v = term();
            while (true) {
                if (eat('+'))
                    v = check(v.add(term()));
                else if (eat('-'))
                    v = check(v.subtract(term()));
                else
                    return v;
            }
        }

        private BigDecimal term() {
            BigDecimal v = power();
            while (true) {
                if (eat('*') || eat('x') || eat('X') || eat('\u00d7')) {
                    BigDecimal r = power();
                    if (size(v) + size(r) > MAX_EXPONENT + 2)
                        throw new ArithmeticException("too large");
                    v = check(v.multiply(r));
                } else if (eat('/')) {
                    BigDecimal r = power();
                    if (r.signum() == 0)
                        throw new ArithmeticException("divide by zero");
                    v = check(v.divide(r, new MathContext(50)));
                } else
                    return v;
            }
        }

        private BigDecimal power() {
            BigDecimal base = unary();
            if (!eat('^'))
                return base;
            BigDecimal e = power();        // from the right
            if (e.signum() < 0 || e.stripTrailingZeros().scale() > 0)
                throw new ArithmeticException("exponent");
            int n = e.intValueExact();
            if (n > MAX_EXPONENT)
                throw new ArithmeticException("exponent");
            if (base.abs().compareTo(BigDecimal.ONE) > 0 && size(base) * n > MAX_EXPONENT + 2)
                throw new ArithmeticException("too large");
            return check(base.pow(n));
        }

        private BigDecimal unary() {
            if (eat('-'))
                return unary().negate();
            if (eat('+'))
                return unary();
            if (eat('(')) {
                BigDecimal v = expr();
                if (!eat(')'))
                    throw new IllegalStateException("bracket");
                return v;
            }
            Matcher m = FORM.matcher(s).region(at, s.length());
            if (!m.lookingAt())
                throw new IllegalStateException("number");
            at = m.end();
            BigDecimal v = number(m);
            if (v == null)
                throw new ArithmeticException("too large");
            return v;
        }

        private static BigDecimal check(BigDecimal v) {
            if (size(v) > MAX_EXPONENT + 2)
                throw new ArithmeticException("too large");
            return v;
        }
    }
}
