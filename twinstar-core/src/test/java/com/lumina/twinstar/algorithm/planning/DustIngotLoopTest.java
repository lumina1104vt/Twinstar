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
 * From a player: dust → hot ingot → ingot → dust, one dust on the network, one ingot ordered. A
 * closed loop (no outside source); the dust is two steps above what was ordered.
 */
class DustIngotLoopTest {
    /** A closed loop of {@code size} items, each made from the one before; item 0 is in stock. */
    private static Plan plan(int size, long stock, int target, long amount, int shift) {
        TestGraphs g = new TestGraphs();
        for (int i = 0; i < size; i++) {
            int from = (i + shift) % size;
            g.pattern(b -> b.input(from, 1).output((from + 1) % size, 1));
        }
        g.stock(0, stock);
        Snapshot s = g.flush();
        return Planner.plan(s, g.stockSnapshot(), target, Amount.of(amount), new Overlay(), Deadline.NONE);
    }

    @Test
    void theOneDustGoesAllTheWayUpTheChain() {
        for (int size : new int[]{3, 4, 8})
            for (int shift = 0; shift < size; shift++)
                for (int target = 1; target < size; target++) {
                    Plan p = plan(size, 1, target, 1, shift);
                    assertTrue(p.isComplete(), size + "/" + shift + "/" + target + " " + p);
                    assertEquals(Amount.ONE, p.pulled.get(0), p.toString());
                }
    }

    @Test
    void onlyWhatTheStockCannotCoverIsMissing() {
        Plan p = plan(3, 1, 2, 3, 0);
        assertEquals(Amount.of(2), p.missing.get(2), p.toString());
        assertTrue(plan(3, 3, 2, 3, 0).isComplete());
    }

    @Test
    void withNothingInStockItIsStillMissing() {
        Plan p = plan(3, 0, 2, 1, 0);
        assertEquals(Amount.ONE, p.missing.get(2), p.toString());
    }
}
