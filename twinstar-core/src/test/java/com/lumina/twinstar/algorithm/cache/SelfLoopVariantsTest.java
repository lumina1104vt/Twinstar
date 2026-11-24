package com.lumina.twinstar.algorithm.cache;

import com.lumina.twinstar.algorithm.TestGraphs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The smithing-template duplication recipe added later, under the stock situations a world can have. */
class SelfLoopVariantsTest {
    static final int DIAMOND = 30, TEMPLATE = 31, NETHERRACK = 32, ORE = 33, INGOT = 34;

    private static void check(long diamond, long template, boolean infiniteRaws) {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(ORE, 1).output(INGOT, 1));
        g.stock(DIAMOND, diamond).stock(TEMPLATE, template).stock(NETHERRACK, 64).stock(ORE, 10);
        if (infiniteRaws)
            g.stock(DIAMOND, Integer.MAX_VALUE).infinite(DIAMOND).stock(NETHERRACK, Integer.MAX_VALUE).infinite(NETHERRACK);
        g.flush();
        g.pattern(b -> b.input(DIAMOND, 7).input(TEMPLATE, 1).input(NETHERRACK, 1).output(TEMPLATE, 2));
        Snapshot s = g.flush();
        assertTrue(s.scc(TEMPLATE) >= 0);
        // stock changes afterwards keep working
        g.stock(TEMPLATE, template + 1000);
        assertTrue(g.flush().scc(TEMPLATE) >= 0);
    }

    @Test
    @Timeout(20)
    void variants() {
        for (long d : new long[]{0, 7, 64})
            for (long t : new long[]{0, 1, 5})
                for (boolean inf : new boolean[]{false, true})
                    check(d, t, inf);
    }

    /** The network from the game log: AE2 cell recipes (raws from a creative cell), then the template recipe. */
    @Test
    @Timeout(20)
    void gameNetwork() {
        int glow = 0, calc = 1, k16 = 2, qglass = 3, k64 = 4, certusDust = 5, glass = 6, redstone = 7, certus = 8,
                logic = 9, k1 = 10, sky = 11, k256 = 12, table = 13, basic = 14, card = 15, k4 = 16,
                diamond = 17, template = 18, netherrack = 19;
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(glow, 4).input(calc, 1).input(k16, 3).input(qglass, 1).output(k64, 1));
        g.pattern(b -> b.input(certusDust, 5).input(glass, 4).output(qglass, 4));
        g.pattern(b -> b.input(redstone, 4).input(certus, 4).input(logic, 1).output(k1, 1));
        g.pattern(b -> b.input(sky, 4).input(calc, 1).input(k64, 3).input(qglass, 1).output(k256, 1));
        g.pattern(b -> b.input(table, 1).input(basic, 1).output(card, 1));
        g.pattern(b -> b.input(glow, 4).input(calc, 1).input(k4, 3).input(qglass, 1).output(k16, 1));
        g.pattern(b -> b.input(redstone, 4).input(calc, 1).input(k1, 3).input(qglass, 1).output(k4, 1));
        for (int raw : new int[]{glow, calc, certusDust, glass, redstone, certus, logic, sky, table, basic, diamond, netherrack})
            g.stock(raw, Integer.MAX_VALUE).infinite(raw);
        g.stock(template, 3).stock(k256, 2);
        g.flush();
        g.pattern(b -> b.input(diamond, 7).input(template, 1).input(netherrack, 1).output(template, 2));
        Snapshot s = g.flush();
        assertTrue(s.scc(template) >= 0);
        // free diamonds and netherrack: each round halves the template's cost, forever in the log
        // domain; the self-loop must be seen at once, not after spinning into the safety limit
        assertTrue(g.theory.lastFlushStats().processed() < 10, g.theory.lastFlushStats().toString());
    }
}
