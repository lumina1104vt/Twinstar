package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A job's progress on one pattern it runs for outputs that come only with a probability
 * ({@link Plan#chanceNeeds}): the runs done and what came of the outputs it waits for. The pattern
 * stops as soon as it has them and its sure runs are done; until then it runs in batches of the
 * runs the rest needs on average, so it does not overshoot by much.
 */
final class ChanceProgress {
    final AmountMap need;
    final Amount sureRuns;
    Amount runsDone = Amount.ZERO;
    final AmountMap got = new AmountMap();
    /** machine runs: what it waits for is expected as a return (from the first batch on) */
    boolean expected;
    /** 機率產物不足 shown */
    boolean marked;

    ChanceProgress(Plan.ChanceNeed n) {
        this.need = n.items().copy();
        this.sureRuns = n.sureRuns();
    }

    boolean satisfied() {
        if (runsDone.compareTo(sureRuns) < 0)
            return false;
        for (int item : need.keys())
            if (got.get(item).compareTo(need.get(item)) < 0)
                return false;
        return true;
    }

    /** Runs for the next batch (before the caps of the task and the tick): at least one. */
    Amount nextBatch(Pattern pat) {
        if (runsDone.compareTo(sureRuns) < 0)
            return sureRuns.sub(runsDone);
        Amount most = Amount.ONE;
        for (int item : need.keys()) {
            Amount missing = need.get(item).sub(got.get(item));
            if (missing.signum() <= 0)
                continue;
            Amount successes = missing.ceilDiv(pat.outputAmountOf(item));
            BigDecimal runs = new BigDecimal(successes.bigValue()).divide(new BigDecimal(pat.chanceOf(item)), 0,
                    RoundingMode.CEILING);
            most = most.max(Amount.of(runs.toBigIntegerExact()));
        }
        return most;
    }

    void ran(Amount runs) {
        runsDone = runsDone.add(runs);
    }

    void gave(int item, Amount amount) {
        if (need.contains(item))
            got.add(item, amount);
    }

    /** What is still missing of each output it waits for. */
    AmountMap shortfall() {
        AmountMap out = new AmountMap();
        for (int item : need.keys()) {
            Amount missing = need.get(item).sub(got.get(item));
            if (missing.signum() > 0)
                out.add(item, missing);
        }
        return out;
    }
}
