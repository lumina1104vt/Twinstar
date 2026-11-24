package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.allocation.Repair;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.OrderMode;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.StockSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Entry point for one order (B, with C and D inside). Pure computation on an immutable snapshot and
 * stock snapshot, so it may run on a background thread. Submitting (pulling items, re-planning once
 * if stock changed) touches the network and lives on the main thread in TwinStarService.
 */
public final class Planner {
    private Planner() {
    }

    /**
     * One plan: the demand pass; if it has shortages that switching routes might fix, the C2 repair
     * and a second pass, keeping whichever is better.
     */
    public static Plan plan(Snapshot snap, StockSnapshot stock, int target, Amount amount, Overlay overlay, Deadline dl) {
        DemandPass first = DemandPass.start(snap, stock, overlay, dl, Map.of());
        first.pushOrder(target, amount);
        first.run();
        DemandPass result = first;
        if (!first.missing.isEmpty() && !dl.softPassed() && Repair.repairable(first)) {
            Map<Integer, List<Split>> forced = Repair.repair(first, stock, dl);
            if (forced != null) {
                DemandPass second = DemandPass.start(snap, stock, overlay, dl, forced);
                second.pushOrder(target, amount);
                second.run();
                if (better(second, first))
                    result = second;
            }
        }
        return result.toPlan(target, amount);
    }

    private static boolean better(DemandPass a, DemandPass b) {
        if (a.missing.size() != b.missing.size())
            return a.missing.size() < b.missing.size();
        return a.missing.total().compareTo(b.missing.total()) < 0;
    }

    /**
     * Order level: soft limit 0.5 s stops improving, hard limit 1 s aborts. B itself always finishes
     * (unless the hard limit hits).
     */
    public static OrderResult order(Snapshot snap, StockSnapshot stock, int target, Amount amount, OrderMode mode,
                                    Amount freeCapacity, Overlay overlay, Deadline dl) {
        try {
            Plan r = plan(snap, stock, target, amount, overlay, dl);
            if (mode == OrderMode.BEST_EFFORT && !r.isComplete() && !dl.softPassed()) {
                // binary search the largest makeable amount; stops at the best so far when time is up
                Amount lo = Amount.ZERO;
                Amount hi = amount;
                Plan loPlan = null;
                while (hi.sub(lo).compareTo(Amount.ONE) > 0 && !dl.softPassed()) {
                    Amount mid = lo.add(hi).half();
                    Plan p = plan(snap, stock, target, mid, overlay, dl);
                    if (p.isComplete()) {
                        lo = mid;
                        loPlan = p;
                    } else
                        hi = mid;
                }
                if (loPlan != null)
                    r = loPlan;
            }
            // capacity (AE2's bytes: amounts asked for, runs, nodes) is shared by all running jobs
            if (r.usage.compareTo(freeCapacity) > 0) {
                Amount m = r.amount.mul(freeCapacity).div(r.usage.max(Amount.ONE));
                // fixed overheads and rounding make the ratio estimate too high: step down until it fits
                while (m.signum() > 0) {
                    dl.checkHard();
                    Plan p = plan(snap, stock, target, m, overlay, dl);
                    if (p.usage.compareTo(freeCapacity) <= 0)
                        break;
                    Amount next = m.mul(freeCapacity).div(p.usage);
                    m = next.compareTo(m) < 0 ? next : m.sub(Amount.ONE);
                }
                return new OrderResult.Rejected(m, r.usage, r);
            }
            return new OrderResult.Accepted(r);
        } catch (Deadline.PlanTimeoutException e) {
            return new OrderResult.TimedOut();
        }
    }
}
