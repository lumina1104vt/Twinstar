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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loops the way they show up in game. */
class LoopScenarioTest {
    private static Plan plan(TestGraphs g, int target, Amount amount) {
        Snapshot s = g.flush();
        return Planner.plan(s, g.stockSnapshot(), target, amount, new Overlay(), Deadline.NONE);
    }

    private static OrderResult order(TestGraphs g, int target, Amount amount) {
        Snapshot s = g.flush();
        return Planner.order(s, g.stockSnapshot(), target, amount, OrderMode.EXACT,
                Amount.of(BigInteger.ONE.shiftLeft(1 << 16)), new Overlay(), Deadline.NONE);
    }

    @Test
    void pureDoublingNeedsOnlyASeed() {
        int a = 1;
        TestGraphs g = new TestGraphs();
        int dbl = g.pattern(b -> b.input(a, 1).output(a, 2));
        g.stock(a, 1);
        Snapshot s = g.flush();
        assertTrue(s.scc(a) >= 0, "1A → 2A is a policy cycle");
        Plan p = plan(g, a, Amount.of(10));
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(10), p.patternTimes.get(dbl));
    }

    @Test
    void growthLoopWithInfiniteFeedAtAstronomicalAmounts() {
        int seed = 1, dirt = 2;
        TestGraphs g = new TestGraphs();
        int grow = g.pattern(b -> b.input(seed, 1).input(dirt, 1).output(seed, 2));
        g.stock(seed, 1).stock(dirt, Integer.MAX_VALUE).infinite(dirt);
        Amount big = Amount.of(BigInteger.TEN.pow(1000));
        OrderResult r = order(g, seed, big);
        assertInstanceOf(OrderResult.Accepted.class, r, r.toString());
        Plan p = ((OrderResult.Accepted) r).plan();
        assertEquals(big, p.patternTimes.get(grow));
    }

    @Test
    void twoStepLoop() {
        // seed + sand → crystal, crystal → 2 seed (the AE2 seed loop in two steps)
        int seed = 1, sand = 2, crystal = 3;
        TestGraphs g = new TestGraphs();
        int grow = g.pattern(b -> b.input(seed, 1).input(sand, 1).output(crystal, 1));
        int split = g.pattern(b -> b.input(crystal, 1).output(seed, 2));
        g.stock(seed, 1).stock(sand, 1000);
        Snapshot s = g.flush();
        assertTrue(s.scc(seed) >= 0 && s.scc(seed) == s.scc(crystal), "seed and crystal form one policy cycle");
        Plan p = plan(g, crystal, Amount.of(20));
        assertTrue(p.isComplete(), p.toString());
        assertEquals(p.patternTimes.get(grow), p.pulled.get(sand));
        assertTrue(p.patternTimes.get(grow).compareTo(Amount.of(20)) >= 0, p.toString());
        assertTrue(p.patternTimes.get(split).signum() > 0, p.toString());
    }

    @Test
    void loopWhoseOutputIsOrderedWithoutStock() {
        // the ordered item itself is the loop: 1 seed + 1 sand → 2 seed, nothing in stock but an
        // ordinary recipe for one seed
        int seed = 1, sand = 2, ore = 3;
        TestGraphs g = new TestGraphs();
        int grow = g.pattern(b -> b.input(seed, 1).input(sand, 1).output(seed, 2));
        g.pattern(b -> b.input(ore, 64).output(seed, 1));
        g.stock(sand, 1000).stock(ore, 64);
        Plan p = plan(g, seed, Amount.of(100));
        assertTrue(p.isComplete(), p.toString());
        assertTrue(p.patternTimes.get(grow).signum() > 0, p.toString());
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void templateDuplicationWithFreeInputs() {
        // 7 diamond + 1 template + 1 netherrack → 2 templates, diamonds and netherrack from a creative cell
        int diamond = 1, template = 2, netherrack = 3;
        TestGraphs g = new TestGraphs();
        int dup = g.pattern(b -> b.input(diamond, 7).input(template, 1).input(netherrack, 1).output(template, 2));
        g.stock(diamond, Integer.MAX_VALUE).infinite(diamond).stock(netherrack, Integer.MAX_VALUE).infinite(netherrack);
        g.stock(template, 1);
        Amount big = Amount.of(BigInteger.TEN.pow(40));
        OrderResult r = order(g, template, big);
        assertInstanceOf(OrderResult.Accepted.class, r, r.toString());
        Plan p = ((OrderResult.Accepted) r).plan();
        assertEquals(big, p.patternTimes.get(dup));
        assertEquals(Amount.ONE, p.leftover.get(template));     // the seed goes back
    }

    @Test
    @org.junit.jupiter.api.Timeout(20)
    void ordersUpToTheInputLimit() {
        // a 5-level chain (like the AE2 storage cells) and the growth loop, at the input limit 1e10000
        int[] tier = {10, 11, 12, 13, 14, 15};
        int dust = 20, seed = 21, sand = 22;
        TestGraphs g = new TestGraphs();
        for (int i = 1; i < tier.length; i++) {
            int lower = tier[i - 1], upper = tier[i];
            g.pattern(b -> b.input(lower, 3).input(dust, 4).output(upper, 1));
        }
        g.pattern(b -> b.input(dust, 1).output(tier[0], 1));
        g.pattern(b -> b.input(seed, 1).input(sand, 1).output(seed, 2));
        g.stock(dust, Integer.MAX_VALUE).infinite(dust).stock(sand, Integer.MAX_VALUE).infinite(sand).stock(seed, 1);
        Amount big = Amount.of(BigInteger.TEN.pow(10000));
        assertInstanceOf(OrderResult.Accepted.class, order(g, tier[5], big));
        assertInstanceOf(OrderResult.Accepted.class, order(g, seed, big));
    }
}
