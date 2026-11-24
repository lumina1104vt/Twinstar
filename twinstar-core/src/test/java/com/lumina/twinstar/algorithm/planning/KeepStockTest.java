package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plan screen's "keep" mark: stock kept out of the plan. */
class KeepStockTest {
    static final int PLATE = 0, INGOT = 1, ORE = 2;

    private static TestGraphs graph() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(INGOT, 1).output(PLATE, 1));
        g.pattern(b -> b.input(ORE, 1).output(INGOT, 1));
        g.stock(INGOT, 10).stock(ORE, 100);
        return g;
    }

    @Test
    void keepingSomeLeavesTheRest() {
        StockSnapshot s = graph().stockSnapshot().withKept(Map.of(INGOT, Amount.of(4)), Set.of());
        assertEquals(Amount.of(6), s.stock(INGOT));
        StockSnapshot all = graph().stockSnapshot().withKept(Map.of(INGOT, Amount.of(40)), Set.of());
        assertEquals(Amount.ZERO, all.stock(INGOT));
    }

    @Test
    void keptIngotsAreMadeFromOreInstead() {
        TestGraphs g = graph();
        Snapshot snap = g.flush();
        StockSnapshot kept = g.stockSnapshot().withKept(Map.of(), Set.of(INGOT));
        Plan plan = Planner.plan(snap, kept, PLATE, Amount.of(10), new Overlay(), Deadline.NONE);
        assertTrue(plan.isComplete());
        assertTrue(plan.pulled.get(INGOT).isZero(), "took kept ingots: " + plan.pulled.get(INGOT));
        assertEquals(Amount.of(10), plan.pulled.get(ORE));
    }

    @Test
    void keptRawIsMissing() {
        TestGraphs g = graph();
        Snapshot snap = g.flush();
        // ore kept but 95 of it: 5 left, 10 ingots in stock kept whole
        StockSnapshot kept = g.stockSnapshot().withKept(Map.of(ORE, Amount.of(95)), Set.of(INGOT));
        Plan plan = Planner.plan(snap, kept, PLATE, Amount.of(10), new Overlay(), Deadline.NONE);
        assertFalse(plan.isComplete());
        assertEquals(Amount.of(5), plan.missing.get(ORE));
    }
}
