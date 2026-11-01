package com.lumina.twinstar.algorithm.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many runs an output that comes only with some probability needs: the fewest runs after
 * which the demand is covered with probability at least {@link #CONFIDENCE}. Each run gives its
 * whole amount or nothing, independently (a binomial count of successes).
 * <ul>
 *   <li>small spreads (variance of the successes below {@link #EXACT_VARIANCE}): exactly, from the
 *   binomial distribution;</li>
 *   <li>large ones: the normal approximation with continuity correction, in {@link BigDecimal} so
 *   demands beyond the long range work too.</li>
 * </ul>
 */
public final class ChanceRuns {
    public static final double CONFIDENCE = 0.9999;
    /** one-sided z for {@link #CONFIDENCE} */
    private static final double Z = 3.7190164854556804;
    private static final double EXACT_VARIANCE = 2500;
    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_UP);
    private static final int MEMO_MAX = 4096;
    private static final ConcurrentHashMap<Key, Amount> MEMO = new ConcurrentHashMap<>();

    private record Key(double p, Amount successes) {
    }

    private ChanceRuns() {
    }

    /** Runs for {@code demand} when each run gives {@code perRun} with probability {@code p}. */
    public static Amount runs(Amount perRun, double p, Amount demand) {
        Amount k = demand.ceilDiv(perRun);
        if (p >= 1 || k.signum() <= 0)
            return k;
        Key key = new Key(p, k);
        Amount memo = MEMO.get(key);
        if (memo != null)
            return memo;
        Amount n = successes(p, k);
        if (MEMO.size() >= MEMO_MAX)
            MEMO.clear();
        MEMO.put(key, n);
        return n;
    }

    /** The fewest runs that give at least {@code k} successes with probability {@link #CONFIDENCE}. */
    static Amount successes(double p, Amount k) {
        BigInteger normal = normal(p, k);
        double estimate = normal.doubleValue();
        if (estimate * p * (1 - p) < EXACT_VARIANCE && estimate < 1e15)
            return Amount.of(exact(p, k.longValue(), (long) estimate));
        return Amount.of(normal.max(k.bigValue()));
    }

    /** n with n·p − z·√(n·p·(1−p)) ≥ k − ½: √n solves a quadratic. */
    private static BigInteger normal(double p, Amount k) {
        BigDecimal bp = new BigDecimal(p);
        BigDecimal zq = new BigDecimal(Z * Math.sqrt(p * (1 - p)));
        BigDecimal need = new BigDecimal(k.bigValue()).subtract(new BigDecimal("0.5"));
        BigDecimal disc = zq.multiply(zq).add(BigDecimal.valueOf(4).multiply(bp).multiply(need));
        BigDecimal s = zq.add(disc.sqrt(MC)).divide(bp.multiply(BigDecimal.valueOf(2)), MC);
        return s.multiply(s).setScale(0, RoundingMode.CEILING).toBigIntegerExact();
    }

    /** Exact search around {@code estimate}: the coverage only grows with the runs. */
    private static long exact(double p, long k, long estimate) {
        long hi = Math.max(k, estimate);
        while (!enough(p, k, hi))
            hi += hi - k + 1;   // doubles the runs beyond k: the short side of the sum stays short
        long lo = k - 1;    // never enough with fewer runs than successes
        while (hi - lo > 1) {
            long mid = lo + (hi - lo) / 2;
            if (enough(p, k, mid))
                hi = mid;
            else
                lo = mid;
        }
        return hi;
    }

    /** P(at least k successes in n runs) ≥ CONFIDENCE; sums the shorter side of the distribution. */
    static boolean enough(double p, long k, long n) {
        if (n < k)
            return false;
        if (k <= n - k + 1) {
            // P(X ≤ k−1), from X = 0 up
            double[] logs = new double[(int) k];
            double l = n * Math.log1p(-p);
            double ratio = Math.log(p) - Math.log1p(-p);
            for (int j = 0; j < k; j++) {
                logs[j] = l;
                l += Math.log((double) (n - j) / (j + 1)) + ratio;
            }
            return sum(logs) <= 1 - CONFIDENCE;
        }
        // P(X ≥ k), from X = n down
        int terms = (int) (n - k + 1);
        double[] logs = new double[terms];
        double l = n * Math.log(p);
        double ratio = Math.log1p(-p) - Math.log(p);
        for (int t = 0; t < terms; t++) {
            long j = n - t;
            logs[t] = l;
            l += Math.log((double) j / (n - j + 1)) + ratio;
        }
        return sum(logs) >= CONFIDENCE;
    }

    private static double sum(double[] logs) {
        double max = Double.NEGATIVE_INFINITY;
        for (double l : logs)
            max = Math.max(max, l);
        if (max == Double.NEGATIVE_INFINITY)
            return 0;
        double s = 0;
        for (double l : logs)
            s += Math.exp(l - max);
        return Math.exp(max) * s;
    }
}
