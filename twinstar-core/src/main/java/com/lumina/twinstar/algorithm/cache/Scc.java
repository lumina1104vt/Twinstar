package com.lumina.twinstar.algorithm.cache;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Mem;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.algorithm.model.SccKind;

/**
 * A policy cycle: the best patterns of these items form a loop. B treats it as one macro pattern
 * (D): solve how many runs of each pattern cover the demand, plus a fixed set of startup seeds.
 * Immutable.
 */
public final class Scc {
    public final int index;
    private final int[] members;            // item ids, all on the same level
    private final int[] policy;             // parallel to members: the pattern each member uses
    private final int[] patterns;           // distinct patterns of the loop
    public final SccKind kind;
    private final int[] seedItems;          // members needed to run one round
    private final Amount[] seedAmounts;
    private final AmountMap[] seedOptions;  // every rotation's seeds, fewest first; [0] = seedItems
    /** net[i][j] = how much of members[i] one run of patterns[j] adds (outputs − consumed inputs), exact. */
    private final Rational[][] net;

    Scc(int index, int[] members, int[] policy, int[] patterns, SccKind kind,
        int[] seedItems, Amount[] seedAmounts, AmountMap[] seedOptions, Rational[][] net) {
        this.index = index;
        this.members = members;
        this.policy = policy;
        this.patterns = patterns;
        this.kind = kind;
        this.seedItems = seedItems;
        this.seedAmounts = seedAmounts;
        this.seedOptions = seedOptions;
        this.net = net;
    }

    /** Java memory of this cycle (see {@link com.lumina.twinstar.algorithm.model.Mem}). */
    long memoryBytes() {
        long b = Mem.obj(4 + 7 * 4) + Mem.ints(members) + Mem.ints(policy) + Mem.ints(patterns) + Mem.ints(seedItems)
                + Mem.refs(seedAmounts.length) + Mem.refs(seedOptions.length) + Mem.refs(net.length);
        for (Amount a : seedAmounts)
            b += a.memoryBytes();
        for (AmountMap m : seedOptions)
            b += m.memoryBytes();
        for (Rational[] row : net) {
            b += Mem.refs(row.length);
            for (Rational r : row)
                b += r.memoryBytes();
        }
        return b;
    }

    /** The same cycle under another number (cycles are numbered in component order at publish). */
    Scc withIndex(int index) {
        return new Scc(index, members, policy, patterns, kind, seedItems, seedAmounts, seedOptions, net);
    }

    public int memberCount() {
        return members.length;
    }

    public int member(int i) {
        return members[i];
    }

    public int policy(int i) {
        return policy[i];
    }

    /** Index of {@code item} in the member list, or −1. */
    public int memberIndex(int item) {
        for (int i = 0; i < members.length; i++)
            if (members[i] == item)
                return i;
        return -1;
    }

    public int patternCount() {
        return patterns.length;
    }

    public int pattern(int j) {
        return patterns[j];
    }

    /**
     * Ways to start one round: the loop can be entered at any of its patterns, each needing other
     * members up front (seed 1, or crystal 1, …). The planner takes the one the stock covers.
     */
    public int seedOptionCount() {
        return seedOptions.length;
    }

    public AmountMap seedOption(int o) {
        return seedOptions[o].copy();
    }

    public int seedCount() {
        return seedItems.length;
    }

    public int seedItem(int i) {
        return seedItems[i];
    }

    public Amount seedAmount(int i) {
        return seedAmounts[i];
    }

    public Rational net(int member, int pattern) {
        return net[member][pattern];
    }
}
