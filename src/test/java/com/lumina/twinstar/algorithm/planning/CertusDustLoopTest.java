package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.OrderMode;
import com.lumina.twinstar.algorithm.model.Overlay;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * From the game: certus dust ordered while 200k certus crystals and a crushing pattern are on the
 * network; the plan took the seed loop (dust → seeds → pure crystal → dust) and came out short of
 * dust for the seeds, instead of crushing crystals.
 */
class CertusDustLoopTest {
    static final int DUST = 0, SEED = 1, PURE = 2, CRYSTAL = 3, SAND = 4;

    private static TestGraphs network(long dust, boolean crusherLast, boolean stockFirst, int pureDust) {
        TestGraphs g = new TestGraphs();
        if (stockFirst) {
            stock(g, dust);
            g.flush();
        }
        if (!crusherLast)
            g.pattern(b -> b.input(CRYSTAL, 1).output(DUST, 1));              // crushing
        g.pattern(b -> b.input(DUST, 1).input(SAND, 1).output(SEED, 2));      // seeds (as in game)
        g.pattern(b -> b.input(SEED, 1).output(PURE, 1));                     // growth
        g.pattern(b -> b.input(PURE, 1).output(DUST, pureDust));              // crushing pure crystal
        if (crusherLast)
            g.pattern(b -> b.input(CRYSTAL, 1).output(DUST, 1));
        if (!stockFirst)
            stock(g, dust);
        return g;
    }

    /** dust < 0: the network has never had any (no stock entry at all, as in game) */
    private static void stock(TestGraphs g, long dust) {
        g.stock(CRYSTAL, 200_000).stock(SEED, 1).stock(SAND, Integer.MAX_VALUE).infinite(SAND);
        if (dust >= 0)
            g.stock(DUST, dust);
    }

    @Test
    void variants() {
        StringBuilder bad = new StringBuilder();
        for (int pureDust : new int[]{1})
        for (long dust : new long[]{-1, 0, 9})
            for (long amount : new long[]{1, 16, 1000})
                for (boolean last : new boolean[]{false, true})
                    for (boolean stockFirst : new boolean[]{false, true}) {
                        TestGraphs g = network(dust, last, stockFirst, pureDust);
                        Snapshot s = g.flush();
                        OrderResult r = Planner.order(s, g.stockSnapshot(), DUST, Amount.of(amount), OrderMode.EXACT,
                                Amount.of(BigInteger.ONE.shiftLeft(1 << 16)), new Overlay(), Deadline.NONE);
                        Plan p = r instanceof OrderResult.Accepted a ? a.plan() : null;
                        if (p == null || !p.isComplete())
                            bad.append("\ndust ").append(dust).append(" order ").append(amount).append(" crusherLast ")
                                    .append(last).append(" stockFirst ").append(stockFirst).append(": ").append(r);
                    }
        assertTrue(bad.isEmpty(), bad.toString());
    }

    /** The network as dumped from the game (dump_network): 8 dust + 32 sand → 32 seeds, no dust in stock. */
    private static TestGraphs game() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(PURE, 1).output(DUST, 1));                     // crystal pulverizer
        g.pattern(b -> b.input(DUST, 8).input(SAND, 32).output(SEED, 32));    // seeds
        g.pattern(b -> b.input(SEED, 1).output(PURE, 1));                     // growth chamber
        g.stock(PURE, 1000).stock(SEED, 100).stock(SAND, Integer.MAX_VALUE).infinite(SAND);
        return g;
    }

    private static Plan gamePlan(long amount) {
        TestGraphs g = game();
        Snapshot s = g.flush();
        return Planner.plan(s, g.stockSnapshot(), DUST, Amount.of(amount), new Overlay(), Deadline.NONE);
    }

    @Test
    void oneDustIsCrushedFromAPurifiedCrystal() {
        Plan p = gamePlan(1);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.ONE, p.pulled.get(PURE), p.toString());
    }

    @Test
    void moreDustThanCrystalsStillRunsTheLoop() {
        Plan p = gamePlan(5000);
        assertTrue(p.isComplete(), p.toString());
    }
}
