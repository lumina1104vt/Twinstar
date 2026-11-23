package com.lumina.twinstar.usage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ring of time buckets that counts what machines use up (no game needed: sources and materials are strings). */
class UsageLedgerTest {
    /** 10-tick buckets, a ring of 6 (one minute) so rotation is easy to reach */
    private static UsageLedger<String, String> small(int maxKeys, int maxSources, int maxEntries) {
        return new UsageLedger<>(0, 10, 6, maxKeys, maxSources, maxEntries);
    }

    @Test
    void sumsWhatFallsInTheSameBucket() {
        var l = small(8, 8, 100);
        l.add("m", "iron", 3, 1, 1, null);
        l.add("m", "iron", 4, 9, 1, null);
        l.add("m", "gold", 1, 5, 1, null);
        var u = l.usage("m", 9, new int[]{1});
        assertEquals(List.of("iron", "gold"), u.keys());
        assertEquals(7, u.of(0)[0]);
        assertEquals(1, u.of(1)[0]);
    }

    @Test
    void windowsCountOnlyTheirOwnBuckets() {
        var l = small(8, 8, 100);
        l.add("m", "iron", 10, 5, 1, null);      // bucket 0
        l.add("m", "iron", 20, 15, 1, null);     // bucket 1
        l.add("m", "iron", 40, 25, 1, null);     // bucket 2
        var u = l.usage("m", 25, new int[]{1, 2, 3});
        assertEquals(40, u.of(0)[0]);
        assertEquals(60, u.of(0)[1]);
        assertEquals(70, u.of(0)[2]);
    }

    @Test
    void theRingForgetsWhatIsOlderThanItsLength() {
        var l = small(8, 8, 100);
        l.add("m", "iron", 100, 5, 1, null);     // bucket 0
        l.add("m", "iron", 1, 65, 1, null);      // bucket 6: the ring of 6 comes round to bucket 0's slot
        var u = l.usage("m", 65, new int[]{6});
        assertEquals(1, u.of(0)[0], "bucket 0 is gone, its slot holds bucket 6");
        assertEquals(1, l.entryCount(), "the cleared bucket's entry was given back");
    }

    @Test
    void anOldBucketNobodyOverwroteIsNotCounted() {
        var l = small(8, 8, 100);
        l.add("m", "iron", 100, 5, 1, null);     // bucket 0
        l.add("m", "gold", 1, 25, 1, null);      // bucket 2
        var u = l.usage("m", 85, new int[]{6});  // now = bucket 8: buckets 3..8 are in, 0 and 2 are not
        assertTrue(u.keys().isEmpty());
        u = l.usage("m", 65, new int[]{6});      // now = bucket 6: buckets 1..6
        assertEquals(List.of("gold"), u.keys());
    }

    @Test
    void sweepGivesBackStaleBucketsWhenTheSourceUsesSomethingAgain() {
        var l = small(8, 8, 100);
        l.add("m", "iron", 1, 5, 1, null);       // bucket 0
        l.add("m", "gold", 1, 15, 1, null);      // bucket 1
        assertEquals(2, l.entryCount());
        l.add("m", "gold", 1, 205, 1, null);     // bucket 20: both old buckets are older than the ring
        assertEquals(1, l.entryCount());
    }

    @Test
    void tooManyKindsGoToOtherButKnownOnesKeepCounting() {
        var l = small(2, 8, 100);
        l.add("m", "a", 1, 0, 1, null);
        l.add("m", "b", 1, 0, 1, null);
        l.add("m", "c", 5, 0, 1, null);          // a third kind: "other"
        l.add("m", "a", 2, 10, 1, null);         // a known kind in the next bucket: kept
        var u = l.usage("m", 10, new int[]{6});
        assertEquals(List.of("a", "b"), u.keys());
        assertEquals(3, u.of(0)[0]);
        assertEquals(5, u.other()[0]);
    }

    @Test
    void aKindThatLeftTheRingMakesRoomForANewOne() {
        var l = small(2, 8, 100);
        l.add("m", "a", 1, 0, 1, null);
        l.add("m", "b", 1, 0, 1, null);
        l.add("m", "c", 1, 100, 1, null);        // bucket 10: a and b's bucket (0) is long gone
        var u = l.usage("m", 100, new int[]{6});
        assertEquals(List.of("c"), u.keys());
        assertEquals(0, u.other()[0]);
    }

    @Test
    void sourcesBeyondTheCapAreCountedNotKept() {
        var l = small(8, 2, 100);
        l.add("a", "x", 1, 0, 1, null);
        l.add("b", "x", 1, 0, 1, null);
        l.add("c", "x", 1, 0, 1, null);
        l.add("c", "x", 1, 0, 1, null);
        assertEquals(2, l.sourceCount());
        assertEquals(2, l.droppedSources());
        assertNull(l.usage("c", 0, new int[]{1}));
        l.add("a", "x", 1, 0, 1, null);          // a kept one still counts
        assertEquals(2, l.usage("a", 0, new int[]{1}).of(0)[0]);
    }

    @Test
    void theEntryBudgetHoldsOverAllSources() {
        var l = small(8, 8, 3);
        l.add("a", "x", 1, 0, 1, null);
        l.add("a", "y", 1, 0, 1, null);
        l.add("b", "x", 1, 0, 1, null);
        l.add("b", "z", 7, 0, 1, null);          // the fourth entry: over the budget
        assertEquals(3, l.entryCount());
        var u = l.usage("b", 0, new int[]{1});
        assertEquals(List.of("x"), u.keys());
        assertEquals(7, u.other()[0]);
        l.add("b", "x", 1, 0, 1, null);          // adding to an existing entry needs no room
        assertEquals(2, l.usage("b", 0, new int[]{1}).of(0)[0]);
    }

    @Test
    void pruneForgetsASourceThatWasQuietForTheWholeRing() {
        var l = small(8, 8, 100);
        l.add("m", "x", 1, 0, 1, null);
        l.add("n", "x", 1, 55, 1, null);
        l.prune(60);                              // bucket 6: m's bucket 0 is exactly one ring old
        assertNull(l.usage("m", 60, new int[]{6}));
        assertNotNull(l.usage("n", 60, new int[]{6}));
        assertEquals(1, l.entryCount());
    }

    @Test
    void spanIsWhatTheWindowReallyCoversAndNeverMoreThanTheLedgerAge() {
        var l = new UsageLedger<String, String>(0, 200, 180, 8, 8, 100);
        // 1 minute = 6 buckets; at tick 1000 (bucket 5, 0 ticks into... 1000 % 200 = 0 → 1 tick into the bucket)
        assertEquals(1001, l.spanTicks(1000, 6), "the ledger is 1001 ticks old: that is shorter than 5 buckets + 1 tick");
        assertEquals(5 * 200 + 1, l.spanTicks(100_000, 6));
        assertEquals(5 * 200 + 51, l.spanTicks(100_050, 6));
        assertEquals(1, l.spanTicks(0, 6));
        // a ledger that began later than 0
        var late = new UsageLedger<String, String>(1000, 200, 180, 8, 8, 100);
        assertEquals(101, late.spanTicks(1100, 6));
    }

    @Test
    void ratePerMinuteFromTheTotalAndTheSpan() {
        var l = new UsageLedger<String, String>(0, 200, 180, 8, 8, 100);
        long now = 100_000;                       // far past the start: the span is bucket-limited
        for (int i = 0; i < 30; i++)              // 64 every 10 seconds for 5 minutes
            l.add("m", "iron", 64, now - i * 200L, 1, null);
        var u = l.usage("m", now, new int[]{l.bucketsFor(6000)});     // the last 5 minutes
        long span = l.spanTicks(now, l.bucketsFor(6000));
        long perMinute = u.of(0)[0] * 1200 / span;
        assertEquals(30 * 64, u.of(0)[0]);
        // the bucket now has run for one tick only, so the span is a little under the 6000 ticks: 1920 * 1200 / 5801 (the true rate is 384)
        assertTrue(perMinute >= 384 && perMinute <= 400, "about 6 * 64 = 384 a minute, was " + perMinute);
    }

    @Test
    void kindsAndLabelAreRemembered() {
        var l = small(8, 8, 100);
        l.add("m", "x", 1, 0, 1, "first");
        l.add("m", "x", 1, 1, 2, null);
        var u = l.usage("m", 1, new int[]{1});
        assertEquals(3, u.kinds());
        assertEquals("first", u.label());
        l.add("m", "x", 1, 2, 2, "second");
        assertEquals("second", l.usage("m", 2, new int[]{1}).label());
    }

    @Test
    void zeroAndNegativeAmountsAreIgnoredAndHugeOnesSaturate() {
        var l = small(8, 8, 100);
        l.add("m", "x", 0, 0, 1, null);
        l.add("m", "x", -5, 0, 1, null);
        assertEquals(0, l.sourceCount());
        l.add("m", "x", Long.MAX_VALUE - 1, 0, 1, null);
        l.add("m", "x", 10, 0, 1, null);
        assertEquals(Long.MAX_VALUE, l.usage("m", 0, new int[]{1}).of(0)[0]);
    }

    @Test
    void aLateRecordForABucketTheRingAlreadyGaveAwayIsDropped() {
        var l = small(8, 8, 100);
        l.add("m", "x", 1, 65, 1, null);         // bucket 6 in slot 0
        l.add("m", "x", 9, 5, 1, null);          // bucket 0 would go to slot 0 too: it is older, so it is not kept
        assertEquals(1, l.usage("m", 65, new int[]{6}).of(0)[0]);
    }

    @Test
    void memoryStaysSmallAndFollowsWhatIsKept() {
        var l = new UsageLedger<String, String>(0);
        long empty = l.memoryBytes();
        for (int s = 0; s < 100; s++)
            for (int b = 0; b < 180; b++)
                for (int k = 0; k < 3; k++)
                    l.add("m" + s, "k" + k, 1, b * 200L, 1, null);
        assertEquals(100 * 180 * 3, l.entryCount());
        long full = l.memoryBytes();
        assertTrue(full > empty);
        assertTrue(full < 10_000_000, "100 machines, 3 materials, a full ring: " + full + " bytes");
        l.prune(180 * 200L * 3);
        assertEquals(0, l.sourceCount());
        assertEquals(0, l.entryCount());
        assertTrue(l.memoryBytes() <= empty + 8);
    }

    @Test
    void theWorstCaseIsBounded() {
        var l = new UsageLedger<Integer, Integer>(0);
        for (int s = 0; s < 2000; s++)
            for (int b = 0; b < 180; b++)
                for (int k = 0; k < 40; k++)
                    l.add(s, k, 1, b * 200L, 1, null);
        assertTrue(l.sourceCount() <= UsageLedger.MAX_SOURCES);
        assertTrue(l.entryCount() <= UsageLedger.MAX_ENTRIES);
        assertTrue(l.memoryBytes() < 20_000_000, "worst case " + l.memoryBytes());
    }
}
