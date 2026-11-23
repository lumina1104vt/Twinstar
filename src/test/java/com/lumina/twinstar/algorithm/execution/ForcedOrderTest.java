package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.algorithm.planning.Planner;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 強制下單: what a forced order takes now, what it waits for, how it picks the rest up, and when it gives up. */
class ForcedOrderTest {
    static final int ORE = 0, INGOT = 1, PLATE = 2, GEAR = 3;

    /** A network with a stock that can be filled up later. */
    private static final class Net {
        final Map<Integer, Amount> stock = new HashMap<>();

        Amount take(int item, Amount want) {
            Amount have = stock.getOrDefault(item, Amount.ZERO);
            Amount got = have.min(want);
            stock.put(item, have.sub(got));
            return got;
        }
    }

    private static TestGraphs gears() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(ORE, 1).output(INGOT, 1));
        g.pattern(b -> b.input(INGOT, 2).output(PLATE, 1));
        g.pattern(b -> b.input(PLATE, 1).input(INGOT, 2).output(GEAR, 1));
        return g;
    }

    private static Plan plan(TestGraphs g, int target, long amount) {
        Snapshot s = g.flush();
        return Planner.plan(s, g.stockSnapshot(), target, Amount.of(amount), new Overlay(), Deadline.NONE);
    }

    @Test
    void anIncompletePlanStillHasEverythingToRun() {
        Plan p = plan(gears().stock(ORE, 5), GEAR, 3);
        assertFalse(p.isComplete());
        // the whole order is planned; the raw level says what the network has and what it lacks
        assertEquals(Amount.of(12), p.patternTimes.get(0));
        assertEquals(Amount.of(5), p.pulled.get(ORE));
        assertEquals(Amount.of(7), p.missing.get(ORE));
    }

    @Test
    void takesWhatThereIsAndRemembersTheRest() {
        Net net = new Net();
        net.stock.put(ORE, Amount.of(5));
        AmountMap wanted = new AmountMap();
        wanted.add(ORE, Amount.of(12));
        wanted.add(PLATE, Amount.of(1));
        AmountMap lack = ForcedOrder.lacking(wanted, net::take);
        assertEquals(Amount.of(7), lack.get(ORE));
        assertEquals(Amount.ONE, lack.get(PLATE));
        assertEquals(Amount.ZERO, net.stock.get(ORE));
    }

    @Test
    void nothingIsLackingWhenTheNetworkHasItAll() {
        Net net = new Net();
        net.stock.put(ORE, Amount.of(50));
        AmountMap wanted = new AmountMap();
        wanted.add(ORE, Amount.of(12));
        assertTrue(ForcedOrder.lacking(wanted, net::take).isEmpty());
    }

    @Test
    void theJobWaitsAndGoesOnWhenTheMissingMaterialArrives() {
        Net net = new Net();
        AmountMap shortage = new AmountMap();
        shortage.add(ORE, Amount.of(7));
        AmountMap inventory = new AmountMap();
        Set<Integer> woken = new HashSet<>();

        // nothing in the network: it keeps waiting, nothing changes
        assertFalse(ForcedOrder.topUp(shortage, net::take, inventory, woken::add));
        assertEquals(Amount.of(7), shortage.get(ORE));
        assertTrue(woken.isEmpty());

        // part of it arrives: that part goes into the job and wakes the tasks waiting on it
        net.stock.put(ORE, Amount.of(3));
        assertTrue(ForcedOrder.topUp(shortage, net::take, inventory, woken::add));
        assertEquals(Amount.of(4), shortage.get(ORE));
        assertEquals(Amount.of(3), inventory.get(ORE));
        assertEquals(Set.of(ORE), woken);

        // the rest arrives (more than needed): only the shortage is taken, the surplus stays in the network
        net.stock.put(ORE, Amount.of(100));
        assertTrue(ForcedOrder.topUp(shortage, net::take, inventory, woken::add));
        assertTrue(shortage.isEmpty());
        assertEquals(Amount.of(7), inventory.get(ORE));
        assertEquals(Amount.of(96), net.stock.get(ORE));
    }

    @Test
    void aWaitingJobGivesUpAfterTheLimitWithoutProgress() {
        long limit = 30L * 60 * 1_000_000_000L;
        assertFalse(ForcedOrder.expired(true, limit - 1, 0, limit));
        assertTrue(ForcedOrder.expired(true, limit + 1, 0, limit));
        // progress moves the deadline; a job lacking nothing never expires
        assertFalse(ForcedOrder.expired(true, limit + 1, 10, limit));
        assertFalse(ForcedOrder.expired(false, 10 * limit, 0, limit));
    }
}
