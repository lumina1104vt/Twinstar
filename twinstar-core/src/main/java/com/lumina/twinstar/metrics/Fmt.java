package com.lumina.twinstar.metrics;

import java.util.Locale;

/** Short number texts for the memory and speed panels (binary units: 1 KB = 1024 bytes, as the JVM counts). */
public final class Fmt {
    private Fmt() {
    }

    /** 837 B, 6.7 KB, 12.4 MB, 1.3 GB. */
    public static String bytes(long b) {
        if (b < 0)
            return "?";
        if (b < 1024)
            return b + " B";
        double v = b / 1024.0;
        if (v < 1024)
            return String.format(Locale.ROOT, v < 10 ? "%.2f KB" : v < 100 ? "%.1f KB" : "%.0f KB", v);
        v /= 1024;
        if (v < 1024)
            return String.format(Locale.ROOT, v < 10 ? "%.2f MB" : v < 100 ? "%.1f MB" : "%.0f MB", v);
        v /= 1024;
        return String.format(Locale.ROOT, v < 10 ? "%.2f GB" : "%.1f GB", v);
    }

    /** 250 µs, 4.2 ms, 82 ms, 1.2 s. */
    public static String nanos(long n) {
        if (n < 0)
            return "?";
        if (n < 1_000_000)
            return String.format(Locale.ROOT, "%d µs", Math.max(0, n / 1000));
        if (n < 10_000_000)
            return String.format(Locale.ROOT, "%.1f ms", n / 1e6);
        if (n < 1_000_000_000L)
            return String.format(Locale.ROOT, "%.0f ms", n / 1e6);
        return String.format(Locale.ROOT, "%.1f s", n / 1e9);
    }

    /** A share of a whole, as a whole percent (below 1 % as "<1 %"). */
    public static String percent(long part, long whole) {
        if (whole <= 0 || part <= 0)
            return "0 %";
        double p = 100.0 * part / whole;
        return p < 1 ? "<1 %" : String.format(Locale.ROOT, "%.0f %%", p);
    }

    /** ×4.1 */
    public static String times(double v) {
        return String.format(Locale.ROOT, v < 10 ? "×%.2f" : "×%.0f", v);
    }
}
