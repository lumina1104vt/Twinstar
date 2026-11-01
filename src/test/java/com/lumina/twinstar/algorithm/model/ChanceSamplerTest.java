package com.lumina.twinstar.algorithm.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceSamplerTest {
    /** Mean and spread of many draws, against n·p and √(n·p·(1−p)), for each way of drawing. */
    private static void matches(long n, double p) {
        SplittableRandom random = new SplittableRandom(42);
        int draws = 4000;
        double sum = 0, sumSq = 0;
        for (int i = 0; i < draws; i++) {
            long k = ChanceSampler.successes(Amount.of(n), p, random).longValue();
            assertTrue(k >= 0 && k <= n, "n=" + n + " p=" + p + " k=" + k);
            sum += k;
            sumSq += (double) k * k;
        }
        double mean = sum / draws;
        double sd = Math.sqrt(sumSq / draws - mean * mean);
        double wantMean = n * p;
        double wantSd = Math.sqrt(n * p * (1 - p));
        // the mean of 4000 draws is off by more than 5 standard errors once in millions of runs
        assertTrue(Math.abs(mean - wantMean) < 5 * wantSd / Math.sqrt(draws) + 1e-9, "n=" + n + " p=" + p + " mean=" + mean);
        assertTrue(Math.abs(sd - wantSd) < 0.1 * wantSd + 0.05, "n=" + n + " p=" + p + " sd=" + sd);
    }

    @Test
    void fewRuns() {
        matches(10, 0.3);
        matches(64, 0.9);
    }

    @Test
    void jumpsBetweenRareOutcomes() {
        matches(1000, 0.01);
        matches(1000, 0.995);
        matches(100_000, 0.001);
    }

    @Test
    void normalForLargeSpreads() {
        matches(1_000_000, 0.5);
        matches(10_000_000, 0.2);
    }

    @Test
    void sureAndNone() {
        SplittableRandom random = new SplittableRandom(1);
        assertEquals(Amount.of(7), ChanceSampler.successes(Amount.of(7), 1, random));
        assertEquals(Amount.ZERO, ChanceSampler.successes(Amount.ZERO, 0.5, random));
    }

    @Test
    void astronomicalRuns() {
        Amount n = Amount.of(BigInteger.TEN.pow(40));
        Amount k = ChanceSampler.successes(n, 0.25, new SplittableRandom(3));
        BigInteger mean = BigInteger.TEN.pow(40).divide(BigInteger.valueOf(4));
        // within 10^21 (some 230 standard deviations) of the mean, never above n
        assertTrue(k.bigValue().subtract(mean).abs().compareTo(BigInteger.TEN.pow(21)) < 0, k.toString());
    }
}
