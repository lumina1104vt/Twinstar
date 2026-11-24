package com.lumina.twinstar.metrics;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the memory estimates ({@code Mem}, {@code Snapshot.memory()}, {@code AmountMap.memoryBytes()})
 * against a real JVM: the heap in use after a full garbage collection, before and after the data is
 * built. The estimate stands for a 64-bit HotSpot with compressed references (what a game runs on);
 * where this test JVM does not use them the check is skipped, not failed. Prints the table that goes in
 * the report.
 */
class MemoryEstimateTest {
    /** The estimate may be off by this much (a share of the real number). */
    private static final double TOLERANCE = 0.20;

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

    static boolean compressedReferences() {
        return MemoryEstimateProbe.compressedReferences();
    }

    /** A wide, shallow graph like a real pack; {@code patterns} patterns over {@code patterns × 0.8} items. */
    static TheoryLayer buildTheory(int patterns, long seed) {
        Random rnd = new Random(seed);
        int raws = Math.max(50, patterns / 25), items = Math.max(raws + 10, (int) (patterns * 0.8));
        int layers = 8, perLayer = Math.max(1, (items - raws) / layers);
        TheoryLayer theory = new TheoryLayer();
        int id = 0;
        for (int out = raws; out < items; out++) {
            int layer = Math.min(layers - 1, (out - raws) / perLayer);
            int below = raws + layer * perLayer;
            int routes = rnd.nextInt(4) == 0 ? 2 : 1;
            for (int r = 0; r < routes; r++) {
                Pattern.Builder b = new Pattern.Builder();
                int ins = 1 + rnd.nextInt(4);
                for (int k = 0; k < ins; k++)
                    b.input(rnd.nextInt(3) == 0 ? rnd.nextInt(raws) : rnd.nextInt(Math.max(1, below)), 1 + rnd.nextInt(3));
                b.output(out, 1 + rnd.nextInt(2));
                theory.addPattern(b.build(id++));
            }
        }
        AmountArray stock = new AmountArray(items + 10);
        for (int k = 0; k < raws; k++)
            stock.set(k, Amount.of(1_000_000_000L));
        theory.applyStock(new StockSnapshot(stock, new BitSet()));
        return theory;
    }

    @Test
    void theoryEstimateMatchesTheJvm() {
        org.junit.jupiter.api.Assumptions.assumeTrue(compressedReferences(), "needs compressed references");
        StringBuilder table = new StringBuilder(String.format("%n%-9s %-9s %12s %12s %8s %12s %12s %10s%n", "patterns", "items", "real", "estimated", "error",
                "patterns", "items+bom", "per pattern"));
        for (int n : new int[]{1_000, 10_000, 40_000}) {
            long before = usedAfterGc();
            TheoryLayer theory = buildTheory(n, 7);
            Snapshot snap = theory.flush();
            long real = usedAfterGc() - before;
            long est = snap.memory().total();
            double err = (est - real) / (double) real;
            Snapshot.Memory m = snap.memory();
            table.append(String.format("%-9d %-9d %10.2f MB %10.2f MB %7.1f%% %10.2f MB %10.2f MB %8d B%n", n, snap.itemCount(), real / 1e6, est / 1e6,
                    err * 100, m.patterns() / 1e6, (m.items() + m.bom()) / 1e6, real / n));
            assertTrue(Math.abs(err) <= TOLERANCE, "estimate " + est + " vs real " + real + " for " + n + " patterns");
            assertTrue(theory.snapshot() == snap);      // keeps the layer alive until after the measurement
        }
        System.out.println(table);
    }

    @Test
    void amountMapEstimateMatchesTheJvm() {
        org.junit.jupiter.api.Assumptions.assumeTrue(compressedReferences(), "needs compressed references");
        StringBuilder table = new StringBuilder(String.format("%n%-10s %-10s %12s %12s %8s %10s%n", "entries", "amounts", "real", "estimated", "error", "per entry"));
        for (int n : new int[]{1_000, 100_000}) {
            for (String kind : new String[]{"small", "large", "huge"}) {
                long before = usedAfterGc();
                AmountMap map = new AmountMap();
                Random rnd = new Random(3);
                for (int i = 0; i < n; i++) {
                    int id = i * 3 + 1000;      // ids above the boxed-integer cache
                    map.set(id, switch (kind) {
                        case "small" -> Amount.of(1 + rnd.nextInt(60));
                        case "large" -> Amount.of(1_000_000L + rnd.nextInt(1 << 20));
                        default -> Amount.of(BigInteger.TEN.pow(30).add(BigInteger.valueOf(rnd.nextInt())));
                    });
                }
                long real = usedAfterGc() - before;
                long est = map.memoryBytes();
                double err = (est - real) / (double) real;
                table.append(String.format("%-10d %-10s %10.2f MB %10.2f MB %7.1f%% %8d B%n", n, kind, real / 1e6, est / 1e6, err * 100, real / n));
                assertTrue(Math.abs(err) <= TOLERANCE, kind + " " + n + ": estimate " + est + " vs real " + real);
                assertTrue(map.size() == n);
            }
        }
        System.out.println(table);
    }
}
