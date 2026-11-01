package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.Overlay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Block ⇄ ingot ⇄ nugget: taking a block apart costs exactly what the real source costs, so the
 * route order must still start with the policy (found by recipeBench on vanilla recipes).
 */
class ReversibleRecipeTest {
    static final int ORE = 1, INGOT = 2, BLOCK = 3, NUGGET = 4;
    static final int BLOCK_TO_INGOTS = 0, NUGGETS_TO_INGOT = 1, SMELT = 2, INGOTS_TO_BLOCK = 3;

    private static TestGraphs iron() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(BLOCK, 1).output(INGOT, 9));       // lowest id on purpose
        g.pattern(b -> b.input(NUGGET, 9).output(INGOT, 1));
        g.pattern(b -> b.input(ORE, 1).output(INGOT, 1));
        g.pattern(b -> b.input(INGOT, 9).output(BLOCK, 1));
        g.pattern(b -> b.input(INGOT, 1).output(NUGGET, 9));
        return g;
    }

    private static Plan plan(TestGraphs g, int target, long n) {
        Snapshot s = g.flush();
        assertEquals(s.best(INGOT), s.producers(INGOT)[0], "the first route is the policy");
        return Planner.plan(s, g.stockSnapshot(), target, Amount.of(n), new Overlay(), Deadline.NONE);
    }

    @Test
    void freeOreIsSmeltedNotABlockTakenApart() {
        TestGraphs g = iron().stock(ORE, Integer.MAX_VALUE).infinite(ORE);
        Plan p = plan(g, BLOCK, 1);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(9), p.patternTimes.get(SMELT));
        assertEquals(Amount.ZERO, p.patternTimes.get(BLOCK_TO_INGOTS));
    }

    @Test
    void aBlockInStorageIsNotTurnedIntoABlock() {
        TestGraphs g = iron().stock(ORE, 1000).stock(BLOCK, 1000);
        Plan p = plan(g, BLOCK, 1);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.ZERO, p.pulled.get(BLOCK), p.toString());
        assertEquals(Amount.of(9), p.pulled.get(ORE));
    }

    @Test
    void stillTrueAfterIncrementalUpdates() {
        TestGraphs g = iron().stock(ORE, 1000).stock(BLOCK, 1000);
        g.flush();
        for (long ore : new long[]{999, 50_000, 7, 1000})
            g.stock(ORE, ore).flush();
        Plan p = plan(g, BLOCK, 1);
        assertEquals(Amount.ZERO, p.pulled.get(BLOCK), p.toString());
    }

    @Test
    void takingBlocksApartIsStillUsedWhenOreRunsShort() {
        TestGraphs g = iron().stock(ORE, 5).stock(BLOCK, 3);
        Plan p = plan(g, INGOT, 14);
        assertTrue(p.isComplete(), p.toString());
        assertTrue(p.patternTimes.get(BLOCK_TO_INGOTS).signum() > 0, p.toString());
    }
}
