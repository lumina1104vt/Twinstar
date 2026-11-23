package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.algorithm.planning.Planner;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Done on dispatch": which products a run waits for, what goes into the ledger, and that a mark never leaves the job. */
class DispatchLedgerTest {
    static final int PLATE = 1, ALLOY = 2, CASING = 3, MARK = 50, REAL = 51, CHANCY = 52;

    private static Pattern build(java.util.function.Consumer<Pattern.Builder> spec) {
        Pattern.Builder b = new Pattern.Builder();
        spec.accept(b);
        return b.build(0);
    }

    @Test
    void aMarkOutputIsDoneOnDispatchAndCredited() {
        Pattern p = build(b -> b.input(PLATE, 4).output(MARK, 1).virtualOf(MARK));
        assertTrue(p.isDispatchDone(0));
        assertTrue(p.isVirtualOutput(MARK));
        assertFalse(p.hasWaitedOutput());
        AmountMap credit = DispatchLedger.credits(p, Amount.of(3));
        assertEquals(Amount.of(3), credit.get(MARK));
        assertEquals(1, credit.size());
    }

    @Test
    void aRealProductDoneOnDispatchIsNotWaitedForAndNotInventedInTheLedger() {
        Pattern p = build(b -> b.input(PLATE, 4).output(REAL, 2).dispatchDoneOf(REAL));
        assertTrue(p.isDispatchDone(0));
        assertFalse(p.isVirtualOutput(REAL));
        assertFalse(p.hasWaitedOutput());
        // the product comes out of the machine by itself: crediting it here would copy items out of nothing
        assertTrue(DispatchLedger.credits(p, Amount.of(5)).isEmpty());
    }

    @Test
    void anOrdinaryOutputIsStillWaitedFor() {
        Pattern p = build(b -> b.input(PLATE, 4).output(ALLOY, 1).output(MARK, 1).virtualOf(MARK));
        assertTrue(p.hasWaitedOutput());
        assertFalse(p.isDispatchDone(0));
        assertTrue(p.isDispatchDone(1));
    }

    @Test
    void anOutputWithAProbabilityIsNeverDoneOnDispatch() {
        Pattern p = build(b -> b.input(PLATE, 1).output(CHANCY, 1, 0.5).dispatchDoneOf(CHANCY).output(ALLOY, 1));
        assertFalse(p.isDispatchDone(0));
        assertTrue(DispatchLedger.credits(p, Amount.ONE).isEmpty());
    }

    @Test
    void noRunsCreditNothing() {
        Pattern p = build(b -> b.input(PLATE, 4).output(MARK, 1).virtualOf(MARK));
        assertTrue(DispatchLedger.credits(p, Amount.ZERO).isEmpty());
    }

    @Test
    void marksLeaveTheInventoryButNotTheOtherItems() {
        AmountMap inv = new AmountMap();
        inv.add(MARK, Amount.of(7));
        inv.add(ALLOY, Amount.of(2));
        Amount ofTarget = DispatchLedger.dropMarks(inv, Set.of(MARK), MARK);
        assertEquals(Amount.of(7), ofTarget);
        assertEquals(Amount.ZERO, inv.get(MARK));
        assertEquals(Amount.of(2), inv.get(ALLOY));
        // a mark that is not the target is dropped without counting as delivered
        inv.add(MARK, Amount.of(1));
        assertEquals(Amount.ZERO, DispatchLedger.dropMarks(inv, Set.of(MARK), ALLOY));
        assertEquals(Amount.ZERO, inv.get(MARK));
    }

    /**
     * The whole chain through the planner: the mark cannot be had from stock, so the plan makes it with the dispatching
     * pattern; a later step that takes the mark as material is ordered after it (a one-way ordering token).
     */
    @Test
    void aMarkIsPlannedLikeAnyItemAndOrdersLaterSteps() {
        TestGraphs g = new TestGraphs();
        g.pattern(b -> b.input(PLATE, 4).output(MARK, 1).virtualOf(MARK));                // P0: assembly line, done on dispatch
        g.pattern(b -> b.input(MARK, 1).input(ALLOY, 1).output(CASING, 1));               // P1: a step after it
        g.stock(PLATE, 100).stock(ALLOY, 10);
        Snapshot s = g.flush();
        Plan p = Planner.plan(s, g.stockSnapshot(), CASING, Amount.of(3), new Overlay(), Deadline.NONE);
        assertTrue(p.isComplete(), p.toString());
        assertEquals(Amount.of(3), p.patternTimes.get(0));
        assertEquals(Amount.of(3), p.patternTimes.get(1));
        assertEquals(Amount.of(12), p.pulled.get(PLATE));
        assertTrue(s.pattern(0).isVirtualOutput(MARK));
        // the job's ledger after the three runs were fed
        AmountMap inv = new AmountMap();
        inv.add(ALLOY, Amount.of(3));
        DispatchLedger.credits(s.pattern(0), Amount.of(3)).forEach(inv::add);
        assertEquals(Amount.of(3), inv.get(MARK));
        // the next step uses the marks up; none is left to give back
        inv.sub(MARK, Amount.of(3));
        assertEquals(Amount.ZERO, DispatchLedger.dropMarks(inv, Set.of(MARK), CASING));
    }
}
