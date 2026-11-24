package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChanceProgressTest {
    static final int GRAVEL = 0, FLINT = 1, SAND = 2;

    /** gravel → 2 flint with 10 % + 1 sand always */
    private static final Pattern SIEVE = new Pattern.Builder().input(GRAVEL, 1).output(FLINT, 2, 0.1).output(SAND, 1).build(0);

    private static ChanceProgress progress(long flint, long sureRuns) {
        AmountMap items = new AmountMap();
        items.add(FLINT, Amount.of(flint));
        return new ChanceProgress(new Plan.ChanceNeed(items, Amount.of(sureRuns)));
    }

    @Test
    void batchIsWhatTheRestNeedsOnAverage() {
        ChanceProgress c = progress(5, 0);
        // 5 flint = 3 successes, 10 % each: 30 runs
        assertEquals(Amount.of(30), c.nextBatch(SIEVE));
        c.ran(Amount.of(30));
        c.gave(FLINT, Amount.of(4));
        c.gave(SAND, Amount.of(30));    // not waited for: not counted
        // 1 more flint = 1 success: 10 runs
        assertEquals(Amount.of(10), c.nextBatch(SIEVE));
        assertFalse(c.satisfied());
        assertEquals(Amount.ONE, c.shortfall().get(FLINT));
        assertEquals(Amount.ZERO, c.shortfall().get(SAND));
    }

    @Test
    void sureRunsComeFirst() {
        ChanceProgress c = progress(2, 12);
        assertEquals(Amount.of(12), c.nextBatch(SIEVE));
        c.ran(Amount.of(5));
        c.gave(FLINT, Amount.of(2));
        assertFalse(c.satisfied(), "the runs for the sand all run");
        assertEquals(Amount.of(7), c.nextBatch(SIEVE));
        c.ran(Amount.of(7));
        assertTrue(c.satisfied());
    }

    @Test
    void atLeastOneRun() {
        ChanceProgress c = progress(1, 0);
        c.gave(FLINT, Amount.of(2));
        assertTrue(c.satisfied());
        assertEquals(Amount.ONE, c.nextBatch(SIEVE));
    }
}
