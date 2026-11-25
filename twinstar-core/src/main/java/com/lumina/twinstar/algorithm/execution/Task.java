package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;

/** One node of a job's progress DAG: a pattern and how many runs are left. */
public final class Task {
    public final int pattern;
    public final int level;
    /** policy cycle this pattern belongs to, or −1 */
    public final int scc;
    Amount remaining;
    /** runs pushed to machines whose outputs have not come back yet (display only) */
    Amount inFlight = Amount.ZERO;
    boolean queued;
    /** its last dispatch to machines took longer than the tick's whole budget: it goes after the others next time */
    boolean heavy;

    Task(int pattern, int level, int scc, Amount remaining) {
        this.pattern = pattern;
        this.level = level;
        this.scc = scc;
        this.remaining = remaining;
    }

    public Amount remaining() {
        return remaining;
    }

    public Amount inFlight() {
        return inFlight;
    }
}
