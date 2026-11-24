package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.Overlay;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw iron ⇄ raw iron block: only a loop without an outside input makes them, and it does not grow,
 * so they are mined (priced by their stock), not impossible (found by recipeBench: 327 such items
 * in a small dev pack).
 */
class MinedItemTest {
    static final int RAW = 1, RAW_BLOCK = 2, INGOT = 3, ORE = 4;
    static final int UNPACK = 0, PACK = 1, SMELT_RAW = 2;

    private static TestGraphs iron() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(RAW_BLOCK, 1).output(RAW, 9));
        g.pattern(b -> b.input(RAW, 9).output(RAW_BLOCK, 1));
        g.pattern(b -> b.input(RAW, 1).output(INGOT, 1));
        return g;
    }

    private static Plan plan(TestGraphs g, int target, long n) {
        Snapshot s = g.flush();
        return Planner.plan(s, g.stockSnapshot(), target, Amount.of(n), new Overlay(), Deadline.NONE);
    }

    @Test
    void theLoopIsMinedNotImpossible() {
        Snapshot s = iron().stock(RAW, 100).flush();
        for (int x : new int[]{RAW, RAW_BLOCK}) {
            assertTrue(s.isMined(x), "item " + x);
            assertNotEquals(LogMath.INF, s.logCost(x));
            assertEquals(-1, s.scc(x));
        }
        assertNotEquals(LogMath.INF, s.logCost(INGOT));
    }

    @Test
    void orderingRawIronTakesABlockApart() {
        Plan p = plan(iron().stock(RAW_BLOCK, 10), RAW, 1);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.ONE, p.patternTimes.get(UNPACK));
        assertEquals(Amount.ONE, p.pulled.get(RAW_BLOCK));
    }

    @Test
    void rawIronInStorageIsSmelted() {
        Plan p = plan(iron().stock(RAW, 100), INGOT, 10);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(10), p.patternTimes.get(SMELT_RAW));
        assertEquals(Amount.of(10), p.pulled.get(RAW));
    }

    @Test
    void blocksAreTakenApartWhenRawIronRunsOut() {
        Plan p = plan(iron().stock(RAW, 3).stock(RAW_BLOCK, 2), INGOT, 10);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(3), p.pulled.get(RAW));
        assertEquals(Amount.ONE, p.patternTimes.get(UNPACK));
    }

    @Test
    void nothingInStorageReportsTheItemItself() {
        Plan p = plan(iron(), INGOT, 10);
        assertEquals(Amount.of(10), p.missing.get(RAW), p.toString());
        assertEquals(Amount.ZERO, p.missing.get(RAW_BLOCK), p.toString());
    }

    @Test
    void stockChangesAreIncremental() {
        TestGraphs g = iron().stock(RAW, 100);
        g.flush();
        g.stock(RAW, 0).stock(RAW_BLOCK, 5);
        Snapshot inc = g.flush();
        assertFalse(g.theory.lastFlushStats().rebuilt(), "a price change of a mined item needs no rebuild");
        Snapshot full = g.theory.rebuild();
        for (int x : new int[]{RAW, RAW_BLOCK, INGOT})
            assertTrue(LogMath.sameWithin(inc.logCost(x), full.logCost(x), 1e-9), "item " + x);
    }

    @Test
    void anOutsideSourceMakesItCraftableAgain() {
        TestGraphs g = iron().stock(ORE, 50);
        assertTrue(g.flush().isMined(RAW));
        g.pattern(b -> b.input(ORE, 1).output(RAW, 2));      // e.g. an ore-doubling machine
        Snapshot s = g.flush();
        assertFalse(s.isMined(RAW));
        Plan p = plan(g, INGOT, 4);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(2), p.pulled.get(ORE));
    }
}
