package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modpack-sized graph (about 10,000 patterns, wide and shallow, some alternative routes and
 * reversible pairs). Prints timings; asserts only that it works and stays far below the hard limit.
 */
class PlannerScaleTest {
    @Test
    void tenThousandPatterns() {
        Random rnd = new Random(42);
        // wide and shallow, like real packs: 8 layers, inputs from the layers below
        int raws = 400, items = 8000, layers = 8;
        int perLayer = (items - raws) / layers;
        TheoryLayer theory = new TheoryLayer();
        int id = 0;
        for (int out = raws; out < items; out++) {
            int layer = Math.min(layers - 1, (out - raws) / perLayer);
            int below = raws + layer * perLayer;     // items with a smaller id in lower layers (or raws)
            int routes = rnd.nextInt(10) == 0 ? 2 : 1;
            for (int r = 0; r < routes; r++) {
                Pattern.Builder b = new Pattern.Builder();
                int ins = 1 + rnd.nextInt(4);
                for (int k = 0; k < ins; k++)
                    b.input(rnd.nextInt(3) == 0 ? rnd.nextInt(raws) : rnd.nextInt(below), 1 + rnd.nextInt(3));
                b.output(out, 1 + rnd.nextInt(2));
                theory.addPattern(b.build(id++));
            }
            if (rnd.nextInt(50) == 0) {     // reversible pair: block ↔ 9 ingots
                theory.addPattern(new Pattern.Builder().input(out, 9).output(out + 100_000, 1).build(id++));
                theory.addPattern(new Pattern.Builder().input(out + 100_000, 1).output(out, 9).build(id++));
            }
        }
        AmountArray stock = new AmountArray(items + 110_000);
        for (int k = 0; k < raws; k++)
            stock.set(k, Amount.of(1_000_000_000L));
        StockSnapshot ss = new StockSnapshot(stock, new BitSet());

        long t0 = System.nanoTime();
        theory.applyStock(ss);
        Snapshot snap = theory.flush();
        long build = System.nanoTime() - t0;

        // a "big order": one pattern that needs 60 top-layer items
        Pattern.Builder big = new Pattern.Builder();
        for (int k = 0; k < 60; k++)
            big.input(items - 1 - k * 7, 1 + k % 3);
        int target = items + 200_000;
        big.output(target, 1);
        theory.addPattern(big.build(id++));
        snap = theory.flush();

        long t1 = System.nanoTime();
        Plan p = Planner.plan(snap, ss, target, Amount.of(64), new Overlay(), Deadline.NONE);
        long plan = System.nanoTime() - t1;
        assertTrue(p.isComplete(), "raws are plentiful: " + p.missing);

        long t2 = System.nanoTime();
        theory.onStockChanged(5, Amount.of(3), false);
        theory.flush();
        long incremental = System.nanoTime() - t2;

        // scarce raws: C1 ceilings and the C2 repair have work to do
        AmountArray scarce = new AmountArray(items + 210_000);
        for (int k = 0; k < raws; k++)
            scarce.set(k, Amount.of(10 + rnd.nextInt(5_000)));
        StockSnapshot tight = new StockSnapshot(scarce, new BitSet());
        theory.applyStock(tight);
        Snapshot tightSnap = theory.flush();
        long t3 = System.nanoTime();
        Plan q = Planner.plan(tightSnap, tight, target, Amount.of(64), new Overlay(), Deadline.startingNow());
        long tightPlan = System.nanoTime() - t3;

        System.out.printf("patterns %d: build %.1f ms, plan %.1f ms (%d patterns used), incremental %.1f ms, "
                        + "scarce plan %.1f ms (%d missing)%n",
                id, build / 1e6, plan / 1e6, p.patternTimes.size(), incremental / 1e6, tightPlan / 1e6, q.missing.size());
        assertTrue(plan < Deadline.DEFAULT_HARD, "planning must stay under the hard limit");
        assertTrue(tightPlan < Deadline.DEFAULT_HARD + 100_000_000L, "the hard limit must stop a slow plan");
    }
}
