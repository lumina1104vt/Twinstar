package com.lumina.twinstar.menu;

import com.lumina.twinstar.util.BigAmountFormat;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * The hub's capacity and parallelism through AE2's CPU fields (a long and an int). Unlimited is the
 * field's maximum; a finite value too big for the field is the maximum minus one, so the screen can
 * tell them apart: ∞, or the unit form followed by "+".
 */
public final class CpuNumbers {
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);

    private CpuNumbers() {
    }

    /** null = unlimited. */
    public static long storageForAe2(@Nullable BigInteger v) {
        if (v == null)
            return Long.MAX_VALUE;
        return v.compareTo(LONG_MAX) >= 0 ? Long.MAX_VALUE - 1 : v.max(BigInteger.ZERO).longValue();
    }

    /** null = unlimited. */
    public static int coProcessorsForAe2(@Nullable BigInteger v) {
        if (v == null)
            return Integer.MAX_VALUE;
        return v.compareTo(INT_MAX) >= 0 ? Integer.MAX_VALUE - 1 : v.max(BigInteger.ZERO).intValue();
    }

    public static String storageText(long v) {
        if (v == Long.MAX_VALUE)
            return "∞";
        String s = BigAmountFormat.unitForm(BigInteger.valueOf(v));
        return v == Long.MAX_VALUE - 1 ? s + "+" : s;
    }

    public static String coProcessorsText(int v) {
        if (v == Integer.MAX_VALUE)
            return "∞";
        String s = BigAmountFormat.unitForm(BigInteger.valueOf(v));
        return v == Integer.MAX_VALUE - 1 ? s + "+" : s;
    }
}
