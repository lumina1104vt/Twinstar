package com.lumina.twinstar.metrics;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Speed and allocation of the theory layer's updates on a real JVM, that its size follows the data
 * (linear, and given back when patterns go away), and the small helpers of the report.
 */
class PerfProbeTest {
    @Test
    void theoryUpdatesSizeAndRelease() {
        StringBuilder table = new StringBuilder(String.format("%n%-9s %10s %12s %12s %10s %12s %12s %10s %10s%n", "patterns", "rebuild", "rebuild alloc", "(× data)",
                "increment", "incr alloc", "(× data)", "size", "estimate"));
        for (int n : new int[]{1_000, 10_000, 40_000}) {
            TheoryLayer theory = MemoryEstimateTest.buildTheory(n, 11);
            Snapshot first = theory.flush();       // warm-up build
            first.memory();
            theory.rebuild();
            TheoryLayer.FlushStats rebuild = theory.lastFlushStats();
            Snapshot snap = theory.snapshot();
            long e0 = System.nanoTime();
            long total = snap.memory().total();
            long estimateNanos = System.nanoTime() - e0;

            // an incremental update: one raw's price tier moves
            AmountArray stock = new AmountArray(snap.itemCount() + 10);
            stock.set(0, Amount.of(10));
            theory.applyStock(new StockSnapshot(stock, new BitSet()));
            theory.flush();
            TheoryLayer.FlushStats incr = theory.lastFlushStats();
            table.append(String.format("%-9d %7.1f ms %10.1f MB %11.1f× %7.1f ms %10.1f MB %11.2f× %7.1f MB %7.2f ms%n", n, rebuild.nanos() / 1e6,
                    rebuild.allocBytes() / 1e6, (double) rebuild.allocBytes() / total, incr.nanos() / 1e6, incr.allocBytes() / 1e6,
                    (double) incr.allocBytes() / total, total / 1e6, estimateNanos / 1e6));
            if (Alloc.supported()) {
                assertTrue(rebuild.allocBytes() > 0 && incr.allocBytes() > 0);
            }
            assertTrue(rebuild.nanos() < Ratings.REBUILD_HIGH, "a rebuild of " + n + " patterns took " + rebuild.nanos() / 1e6 + " ms");
            assertTrue(theory.perf().flushes() >= 2 && theory.perf().rebuilds() >= 1);
            assertTrue(theory.perf().lastRebuildNanos() > 0);

            // taking every pattern away gives the memory back (the layer's own arrays stay, the patterns and rows do not)
            for (int p = 0; p < snap.patternCount(); p++)
                theory.removePattern(p);
            Snapshot empty = theory.flush();
            long left = empty.memory().patterns();
            assertTrue(left < 64L * 1024 + snap.patternCount() * 4L, "the empty layer still holds " + left + " bytes of patterns");
        }
        System.out.println(table);
    }

    @Test
    void sizeFollowsTheData() {
        long[] perPattern = new long[3];
        int[] sizes = {2_000, 8_000, 32_000};
        for (int i = 0; i < 3; i++) {
            Snapshot s = MemoryEstimateTest.buildTheory(sizes[i], 5).flush();
            perPattern[i] = s.memory().total() / sizes[i];
        }
        // bytes per pattern stays within 10 % over a 16× larger graph: linear, nothing grows faster than the data
        assertTrue(perPattern[2] < perPattern[0] * 1.10 && perPattern[2] > perPattern[0] * 0.90, java.util.Arrays.toString(perPattern));
    }

    @Test
    void trendSeesGrowthThatIsNotLinear() {
        Trend linear = new Trend();
        linear.add(1000, 640_000);
        linear.add(2000, 1_280_000);
        linear.add(4000, 2_600_000);
        assertTrue(linear.growthPermille() >= 990 && linear.growthPermille() <= 1100, "" + linear.growthPermille());
        Trend leak = new Trend();
        leak.add(1000, 640_000);
        leak.add(2000, 1_280_000);
        leak.add(2400, 9_000_000);          // the data grew 20 %, its size 7×
        assertTrue(leak.growthPermille() > 5000, "" + leak.growthPermille());
        Trend small = new Trend();
        small.add(1000, 640_000);
        small.add(1100, 700_000);        // less than 25 % apart: no judgement yet
        assertEquals(-1, small.growthPermille());
    }

    @Test
    void tickMeterAveragesOverWindows() {
        TickMeter m = new TickMeter();
        long t = 1_000_000_000L;
        m.record(true, 2_000_000, 1000, t);
        m.record(true, 6_000_000, 3000, t + 400_000_000);
        m.record(false, 100, 10, t + 900_000_000);
        assertEquals(0, m.last().ticks(), "the window is not over yet");
        m.record(true, 4_000_000, 2000, t + 1_000_000_000L);
        TickMeter.Window w = m.last();
        assertEquals(3, w.ticks());
        assertEquals(4_000_000, w.avgNanos());
        assertEquals(6_000_000, w.maxNanos());
        assertEquals(6000, w.allocPerSecond());
        m.record(false, 100, 10, t + 2_000_000_000L);
        assertEquals(0, m.last().ticks(), "a second without work says so");
    }

    @Test
    void planStatsRememberTheLastPlans() {
        PlanStats s = new PlanStats();
        s.record(10_000_000, false, false);
        s.record(700_000_000, true, false);
        s.record(2_000_000_000L, true, true);
        PlanStats.Summary x = s.summary();
        assertEquals(3, x.plans());
        assertEquals(2_000_000_000L, x.maxNanos());
        assertEquals(1, x.softHits());
        assertEquals(1, x.hardHits());
        for (int i = 0; i < PlanStats.RECENT; i++)
            s.record(1_000_000, false, false);
        assertEquals(0, s.summary().softHits() + s.summary().hardHits(), "old plans fall out of the window");
    }

    @Test
    void ratingsExplainThemselves() {
        TheoryReport fine = new TheoryReport(true, 8000, 10000, 3, 2_850_000, 1_500_000, 1_320_000, 700_000, 5, 1, 4_000_000, false, 60_000_000,
                3_000_000, 2_000_000, 4, 20_000_000, 40_000_000, 0, 0, 1010);
        Verdict v = Ratings.theory(fine);
        assertEquals(Rating.NORMAL, v.rating(), v.toString());
        assertFalse(v.reasons().isEmpty(), "every check is listed, for the tooltip");

        TheoryReport slow = new TheoryReport(true, 8000, 10000, 3, 2_850_000, 1_500_000, 1_320_000, 700_000, 5, 1, 4_000_000, false, 1_500_000_000L,
                3_000_000, 2_000_000, 4, 20_000_000, 40_000_000, 0, 0, 1010);
        assertEquals(Rating.HIGH, Ratings.theory(slow).rating());
        assertEquals("rebuild", Ratings.theory(slow).reasons().get(0).key(), "the worst check comes first");

        TheoryReport timedOut = new TheoryReport(true, 8000, 10000, 3, 2_850_000, 1_500_000, 1_320_000, 700_000, 5, 1, 4_000_000, false, 60_000_000,
                3_000_000, 2_000_000, 4, 20_000_000, 2_000_000_000L, 1, 1, 1010);
        assertEquals(Rating.VERY_HIGH, Ratings.theory(timedOut).rating());

        HubReport idleLeak = new HubReport(0, 0, 20L * Ratings.MB, -1, 0, 0, 20L * Ratings.MB, 20L * Ratings.MB, 0, 0, 0, 0, -1);
        assertEquals(Rating.VERY_HIGH, Ratings.hub(idleLeak).rating(), "a hub with no job that still holds 20 MB");
        HubReport busy = new HubReport(2, 5000, 1_200_000, 600_000, 2500, 40, 80_000, 4_000, 0, 0, 300_000, 2_000_000, 8_000_000);
        assertEquals(Rating.NORMAL, Ratings.hub(busy).rating(), Ratings.hub(busy).toString());
        HubReport slowTick = new HubReport(2, 5000, 1_200_000, 600_000, 2500, 40, 80_000, 4_000, 0, 0, 6_000_000, 30_000_000, 8_000_000);
        assertEquals(Rating.HIGH, Ratings.hub(slowTick).rating());
    }
}
