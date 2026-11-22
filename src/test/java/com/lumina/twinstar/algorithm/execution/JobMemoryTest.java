package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.ByteUnits;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.metrics.HubReport;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A job's and the hub's memory estimate against a real JVM (heap after a full garbage collection),
 * and that a finished job is let go: the hub's report goes back to nothing and the JVM gets the memory back.
 */
class JobMemoryTest {
    /**
     * The estimate counts an {@code Amount} once for every map that holds it, and jobs share them (the
     * plan's pulled amounts are also the inventory's): it is an upper bound, a little above the real number.
     */
    private static final double UNDER = -0.10, OVER = 0.30;

    static long usedAfterGc() {
        long used = 0;
        for (int i = 0; i < 5; i++) {
            System.gc();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime rt = Runtime.getRuntime();
            used = rt.totalMemory() - rt.freeMemory();
        }
        return used;
    }

    /** A job with {@code kinds} kinds of items in its inventory, expected returns, plan and tasks. */
    static Job job(int kinds, boolean huge, Snapshot snap) {
        Random rnd = new Random(5);
        AmountMap pulled = new AmountMap(), patternTimes = new AmountMap(), leftover = new AmountMap();
        for (int i = 0; i < kinds; i++) {
            Amount a = huge ? Amount.of(BigInteger.TEN.pow(25).add(BigInteger.valueOf(rnd.nextInt()))) : Amount.of(2000 + rnd.nextInt(1 << 20));
            pulled.set(1000 + i, a);
            if (i % 4 == 0)
                leftover.set(1000 + i, Amount.of(5000 + i));
            if (i % 3 == 0)
                patternTimes.set(i % Math.max(1, snap.patternCount()), Amount.of(3000 + i));
        }
        Plan plan = Plan.restore(1000, Amount.of(1000), patternTimes, pulled, new AmountMap(), leftover, Amount.of(100_000), Map.of(), Map.of(),
                List.of(), Map.of());
        Job job = new Job(1, plan, snap, 0, Amount.of(100_000));
        pulled.forEach(job.inventory::add);
        for (int i = 0; i < kinds; i += 5) {
            job.outstanding.add(1000 + i, Amount.of(2500 + i));
            job.outstandingSince.put(1000 + i, 123_456_789L + i);
        }
        for (int i = 0; i < Math.min(kinds, 2000); i++)
            job.tasks.add(new Task(i % Math.max(1, snap.patternCount()), i % 7, -1, Amount.of(3000 + i)));
        return job;
    }

    @Test
    void jobEstimateMatchesTheJvm() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.lumina.twinstar.metrics.MemoryEstimateProbe.compressedReferences());
        Snapshot snap = Snapshot.ofPatterns(new Pattern[]{new Pattern.Builder().input(1, 1).output(2, 1).build(0)}, ByteUnits.ITEMS);
        StringBuilder table = new StringBuilder(String.format("%n%-8s %-8s %12s %12s %8s %10s%n", "items", "amounts", "real", "estimated", "error", "per item"));
        for (int kinds : new int[]{500, 20_000}) {
            for (boolean huge : new boolean[]{false, true}) {
                long before = usedAfterGc();
                Job job = job(kinds, huge, snap);
                long real = usedAfterGc() - before;
                long est = job.memoryBytes();
                double err = (est - real) / (double) real;
                table.append(String.format("%-8d %-8s %10.2f MB %10.2f MB %7.1f%% %8d B%n", kinds, huge ? "huge" : "normal", real / 1e6, est / 1e6, err * 100,
                        real / kinds));
                assertTrue(err >= UNDER && err <= OVER, "estimate " + est + " vs real " + real + " for " + kinds + (huge ? " huge" : ""));
                assertTrue(job.inventory.size() == kinds);
            }
        }
        System.out.println(table);
    }

    @Test
    void aFinishedJobIsLetGo() {
        org.junit.jupiter.api.Assumptions.assumeTrue(com.lumina.twinstar.metrics.MemoryEstimateProbe.compressedReferences());
        Snapshot snap = Snapshot.ofPatterns(new Pattern[]{new Pattern.Builder().input(1, 1).output(2, 1).build(0)}, ByteUnits.ITEMS);
        java.util.List<Job> jobs = new java.util.ArrayList<>();
        long base = usedAfterGc();
        HubReport idle = JobMemory.report(jobs, null, snap, 0, 0, -1, com.lumina.twinstar.metrics.TickMeter.Window.NONE);
        jobs.add(job(20_000, false, snap));
        long during = usedAfterGc();
        HubReport busy = JobMemory.report(jobs, jobs.get(0), snap, 0, 0, -1, com.lumina.twinstar.metrics.TickMeter.Window.NONE);
        assertEquals(1, busy.jobs());
        assertTrue(busy.trackedItems() >= 20_000, "tracked kinds " + busy.trackedItems());
        assertTrue(busy.totalBytes() > 100 * idle.totalBytes() + 1_000_000, busy.totalBytes() + " vs idle " + idle.totalBytes());
        assertTrue(busy.jobBytes() > 0 && busy.jobBytes() <= busy.totalBytes());
        jobs.clear();
        long after = usedAfterGc();
        HubReport gone = JobMemory.report(jobs, null, snap, 0, 0, -1, com.lumina.twinstar.metrics.TickMeter.Window.NONE);
        assertEquals(0, gone.jobs());
        assertEquals(0, gone.trackedItems());
        assertEquals(idle.totalBytes(), gone.totalBytes(), "the hub holds nothing of the job any more");
        System.out.printf("%njob of 20000 kinds: JVM +%.2f MB while running, %.2f MB after it ended (estimate said %.2f MB)%n",
                (during - base) / 1e6, (after - base) / 1e6, busy.jobBytes() / 1e6);
        assertTrue(after - base < (during - base) / 4 + 200_000, "memory is not given back");
    }
}
