package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Amount;

/** What {@link Planner#order} hands to the confirm screen. */
public sealed interface OrderResult permits OrderResult.Accepted, OrderResult.Rejected, OrderResult.TimedOut {

    /** A plan to show (it may still have missing items). */
    record Accepted(Plan plan) implements OrderResult {
    }

    /**
     * More than the black hole's free capacity. {@code suggested} is the largest amount that fits
     * (checked with B), {@code usage} the capacity the full order would need. The list of jobs
     * holding capacity is added on the MC side.
     */
    record Rejected(Amount suggested, Amount usage, Plan plan) implements OrderResult {
    }

    /** The hard limit (1 s) ran out. */
    record TimedOut() implements OrderResult {
    }
}
