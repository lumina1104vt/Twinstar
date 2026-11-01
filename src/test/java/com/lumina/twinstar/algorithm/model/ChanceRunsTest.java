package com.lumina.twinstar.algorithm.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceRunsTest {
    /** P(at least k successes in n runs), straight from the binomial terms (small n only). */
    private static double coverage(double p, int k, int n) {
        double total = 0;
        for (int j = k; j <= n; j++) {
            double log = 0;
            for (int i = 0; i < j; i++)
                log += Math.log((double) (n - i) / (i + 1));
            total += Math.exp(log + j * Math.log(p) + (n - j) * Math.log1p(-p));
        }
        return total;
    }

    @Test
    void oneHalfOnce() {
        // 1 − 0.5^14 ≥ 0.9999 > 1 − 0.5^13
        assertEquals(Amount.of(14), ChanceRuns.runs(Amount.ONE, 0.5, Amount.ONE));
    }

    @Test
    void sureOutputIsAPlainDivision() {
        assertEquals(Amount.of(4), ChanceRuns.runs(Amount.of(3), 1, Amount.of(10)));
    }

    @Test
    void amountPerRunCountsAsOneSuccess() {
        // 10 with 3 per run: 4 successes, the same runs as 4 with 1 per run
        assertEquals(ChanceRuns.runs(Amount.ONE, 0.3, Amount.of(4)), ChanceRuns.runs(Amount.of(3), 0.3, Amount.of(10)));
    }

    @Test
    void fewestRunsThatReachTheConfidence() {
        double[] ps = {0.01, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99};
        for (double p : ps)
            for (int k = 1; k <= 60; k += 7) {
                int n = ChanceRuns.runs(Amount.ONE, p, Amount.of(k)).saturatedInt();
                if (n > 3000)
                    continue;   // too slow to check this way
                assertTrue(coverage(p, k, n) >= ChanceRuns.CONFIDENCE, "p=" + p + " k=" + k + " n=" + n);
                // one run fewer is not enough (a tie exactly at the confidence may take one more: rounding)
                assertTrue(n == k || coverage(p, k, n - 1) < ChanceRuns.CONFIDENCE + 1e-12, "p=" + p + " k=" + k + " n=" + n);
            }
    }

    @Test
    void exactAndNormalMeetAtTheBorder() {
        // variance 2500 at p = 0.5 is about 10000 runs: just below exact, just above normal
        long exact = ChanceRuns.successes(0.5, Amount.of(4800)).longValue();
        long normal = ChanceRuns.successes(0.5, Amount.of(5200)).longValue();
        double perSuccess = (normal - exact) / 400.0;
        assertTrue(perSuccess > 1.9 && perSuccess < 2.1, exact + " / " + normal);
    }

    @Test
    void astronomicalDemand() {
        Amount k = Amount.of(BigInteger.TEN.pow(30));
        Amount n = ChanceRuns.runs(Amount.ONE, 0.1, k);
        // about 10^31, a few standard deviations (~10^15.5) more
        BigInteger lo = BigInteger.TEN.pow(31);
        BigInteger hi = lo.add(BigInteger.TEN.pow(17));
        assertTrue(n.bigValue().compareTo(lo) > 0 && n.bigValue().compareTo(hi) < 0, n.toString());
    }

    @Test
    void rareOutput() {
        // 1 in a million, once: ln(10^4) / 10^-6 ≈ 9.21 million runs
        long n = ChanceRuns.runs(Amount.ONE, 1e-6, Amount.ONE).longValue();
        assertTrue(Math.abs(n - 9_210_336) < 10, Long.toString(n));
    }
}
