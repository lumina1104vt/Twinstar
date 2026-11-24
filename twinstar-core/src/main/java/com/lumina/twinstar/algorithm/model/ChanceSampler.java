package com.lumina.twinstar.algorithm.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.random.RandomGenerator;

/**
 * Draws how many of {@code n} runs give an output that comes with probability {@code p} (a binomial
 * draw), for the hub's own runs:
 * <ul>
 *   <li>few runs: one draw per run;</li>
 *   <li>small spreads: jumps from success to success (or failure to failure, whichever is rarer),
 *   so the work follows the rarer outcome, not the runs;</li>
 *   <li>large spreads: the normal approximation, in {@link BigDecimal} beyond the long range.</li>
 * </ul>
 */
public final class ChanceSampler {
    private static final int PER_RUN_MAX = 64;
    private static final double JUMP_VARIANCE = 1000;
    private static final MathContext MC = new MathContext(40, RoundingMode.HALF_EVEN);

    private ChanceSampler() {
    }

    public static Amount successes(Amount n, double p, RandomGenerator random) {
        if (n.signum() <= 0)
            return Amount.ZERO;
        if (p >= 1)
            return n;
        if (!n.isBig() && n.longValue() <= PER_RUN_MAX) {
            long k = 0;
            for (long i = 0; i < n.longValue(); i++)
                if (random.nextDouble() < p)
                    k++;
            return Amount.of(k);
        }
        double nd = Math.pow(10, n.log10());
        if (!n.isBig() && nd * p * (1 - p) < JUMP_VARIANCE) {
            long runs = n.longValue();
            boolean flip = p > 0.5;
            double q = flip ? 1 - p : p;
            double log = Math.log1p(-q);
            long count = 0;
            long at = 0;
            while (true) {
                // runs up to and including the next rarer outcome: geometric
                double gap = Math.floor(Math.log(1 - random.nextDouble()) / log);
                if (gap >= runs - at)
                    break;
                at += (long) gap + 1;
                count++;
            }
            return Amount.of(flip ? runs - count : count);
        }
        BigDecimal bn = new BigDecimal(n.bigValue());
        BigDecimal mean = bn.multiply(new BigDecimal(p), MC);
        BigDecimal sd = mean.multiply(BigDecimal.valueOf(1 - p), MC).sqrt(MC);
        BigDecimal draw = mean.add(sd.multiply(BigDecimal.valueOf(random.nextGaussian()), MC))
                .setScale(0, RoundingMode.HALF_EVEN);
        if (draw.signum() < 0)
            return Amount.ZERO;
        return Amount.of(draw.toBigIntegerExact()).min(n);
    }
}
