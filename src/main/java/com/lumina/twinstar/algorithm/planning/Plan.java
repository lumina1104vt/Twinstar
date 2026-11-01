package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The result of planning one order. Everything the executor and the confirm screen need; item and
 * pattern ids refer to the snapshot of {@link #snapshotVersion}.
 */
public final class Plan {
    public final long snapshotVersion;
    public final int target;
    public final Amount amount;
    /** pattern → runs */
    public final AmountMap patternTimes;
    /** items taken from network storage (including {@link #drawLater}) */
    public final AmountMap pulled;
    /**
     * the part of {@link #pulled} that comes from infinite sources: not pulled at submit but drawn
     * while the job runs (an astronomical amount could never be pulled through AE2's long API)
     */
    public final AmountMap drawLater;
    /** shortages (raw level) */
    public final AmountMap missing;
    /** what is left in the CPU at the end and goes back to the network (leftovers, catalysts, containers, seeds) */
    public final AmountMap leftover;
    /**
     * black-hole capacity, in AE2's CPU bytes: what the plan asks for of every item (from stock or
     * made; one byte per item, per bucket of fluid), one per run, eight per item and pattern of the plan
     */
    public final Amount usage;
    /** firing orders for policy cycles the plain executor rules would get stuck on; key = scc index */
    public final Map<Integer, CycleContract> contracts;
    /** the seeds each policy cycle starts from (the planner picks among the loop's rotations); key = scc index */
    public final Map<Integer, AmountMap> loopSeeds;
    /** items a pattern of this plan gives back to its own inputs (the bucket of a water bucket): only seeds are planned */
    public final List<Recycle> recycles;
    /** per pattern run for outputs that come only with a probability: what the plan counts on; key = pattern */
    public final Map<Integer, ChanceNeed> chanceNeeds;
    /**
     * Machine patterns of this plan whose machine has no interface on the grid: pattern → the
     * machine's item id. Set on the best plan shown when no plan without them could be made.
     */
    public final Map<Integer, Integer> missingMachines;

    /**
     * One loop of an item the plan uses again: {@code seed} of {@code item} starts it, the rest of the
     * demand is what the loop gives back while the job runs. The seed is for the loop's own
     * {@code patterns} only until they have all run.
     */
    public record Recycle(int item, Amount seed, int[] patterns) {
        public boolean has(int pattern) {
            for (int p : patterns)
                if (p == pattern)
                    return true;
            return false;
        }

        @Override
        public String toString() {
            return "Recycle{" + item + " seed " + seed + ", patterns " + Arrays.toString(patterns) + "}";
        }
    }

    /**
     * A pattern run (also) for outputs that come only with a probability. Its runs in
     * {@link #patternTimes} cover {@code items} with {@link com.lumina.twinstar.algorithm.model.ChanceRuns#CONFIDENCE};
     * the plan counts on nothing else of those runs, so the job may stop the pattern once it has
     * {@code items} and at least {@code sureRuns} runs are done (the runs for outputs that always
     * come, which all run).
     */
    public record ChanceNeed(AmountMap items, Amount sureRuns) {
    }

    Plan(long snapshotVersion, int target, Amount amount, AmountMap patternTimes, AmountMap pulled, AmountMap drawLater,
         AmountMap missing, AmountMap leftover, Amount usage, Map<Integer, CycleContract> contracts,
         Map<Integer, AmountMap> loopSeeds, List<Recycle> recycles, Map<Integer, ChanceNeed> chanceNeeds) {
        this.snapshotVersion = snapshotVersion;
        this.target = target;
        this.amount = amount;
        this.patternTimes = patternTimes;
        this.pulled = pulled;
        this.drawLater = drawLater;
        this.missing = missing;
        this.leftover = leftover;
        this.usage = usage;
        this.contracts = Map.copyOf(contracts);
        this.loopSeeds = Map.copyOf(loopSeeds);
        this.recycles = List.copyOf(recycles);
        this.chanceNeeds = Map.copyOf(chanceNeeds);
        this.missingMachines = Map.of();
    }

    private Plan(Plan p, Map<Integer, Integer> missingMachines) {
        this.snapshotVersion = p.snapshotVersion;
        this.target = p.target;
        this.amount = p.amount;
        this.patternTimes = p.patternTimes;
        this.pulled = p.pulled;
        this.drawLater = p.drawLater;
        this.missing = p.missing;
        this.leftover = p.leftover;
        this.usage = p.usage;
        this.contracts = p.contracts;
        this.loopSeeds = p.loopSeeds;
        this.recycles = p.recycles;
        this.chanceNeeds = p.chanceNeeds;
        this.missingMachines = Map.copyOf(missingMachines);
    }

    /** A running job's plan read back from a save (ids already those of the restored job). */
    public static Plan restore(int target, Amount amount, AmountMap patternTimes, AmountMap pulled, AmountMap drawLater,
                               AmountMap leftover, Amount usage, Map<Integer, CycleContract> contracts,
                               Map<Integer, AmountMap> loopSeeds, List<Recycle> recycles, Map<Integer, ChanceNeed> chanceNeeds) {
        return new Plan(-1, target, amount, patternTimes, pulled, drawLater, new AmountMap(), leftover, usage, contracts,
                loopSeeds, recycles, chanceNeeds);
    }

    /** This plan with the machines it lacks marked (it cannot run until they are there). */
    public Plan withMissingMachines(Map<Integer, Integer> missingMachines) {
        return new Plan(this, missingMachines);
    }

    /** Can be made with what the network has (materials and machines). */
    public boolean isComplete() {
        return missing.isEmpty() && missingMachines.isEmpty();
    }

    @Override
    public String toString() {
        return "Plan{target=" + target + " ×" + amount + ", runs=" + patternTimes + ", pulled=" + pulled
                + ", missing=" + missing + ", leftover=" + leftover + ", usage=" + usage
                + (recycles.isEmpty() ? "" : ", recycles=" + recycles)
                + (chanceNeeds.isEmpty() ? "" : ", chanceNeeds=" + chanceNeeds) + "}";
    }
}
