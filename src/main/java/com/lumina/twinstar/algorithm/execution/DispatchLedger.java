package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;

import java.util.Set;

/**
 * The job ledger's side of "done on dispatch": a run whose products count as made once its materials are all in the
 * machine puts those products that are marks (no real item) into the job's inventory, where later steps may use them
 * up; marks never leave the job.
 */
final class DispatchLedger {
    private DispatchLedger() {
    }

    /** What {@code runs} runs of {@code pat} put into the ledger when their materials are in: its done-on-dispatch marks. */
    static AmountMap credits(Pattern pat, Amount runs) {
        AmountMap out = new AmountMap();
        if (runs.signum() <= 0 || !pat.hasDispatchDone())
            return out;
        for (int o = 0; o < pat.outputCount(); o++)
            if (pat.isDispatchDone(o) && pat.isVirtualOutput(pat.outputItem(o)))
                out.add(pat.outputItem(o), pat.outputAmount(o).mul(runs));
        return out;
    }

    /** Takes the marks out of {@code inventory}; returns how much of {@code target} was among them. */
    static Amount dropMarks(AmountMap inventory, Set<Integer> marks, int target) {
        Amount ofTarget = Amount.ZERO;
        for (int item : marks) {
            Amount a = inventory.get(item);
            if (a.signum() <= 0)
                continue;
            inventory.sub(item, a);
            if (item == target)
                ofTarget = ofTarget.add(a);
        }
        return ofTarget;
    }
}
