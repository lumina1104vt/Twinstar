package com.lumina.twinstar.algorithm.model;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One internal pattern: what goes in and what comes out of ONE run. Immutable; arrays are copied on
 * construction and never handed out.
 * <p>
 * Outputs are main products, except byproducts: what a recipe leaves behind without the pattern
 * saying so (the bucket of a water bucket). A byproduct comes out like any output (the ledger and the
 * executor take it), but it is no route to the item: costs, routes and the theory layer's graph only
 * see main products.
 * <p>
 * An output may come only with some probability per run (all of its amount or nothing). Costs
 * count its expected amount; the planner runs the pattern often enough to cover the demand with
 * {@link ChanceRuns#CONFIDENCE} ({@link #runsFor}); what such an output may give beyond that
 * counts as nothing ({@link #sureOutputAmount}). Input slot {@code i} matches AE2's
 * {@code IPatternDetails.getInputs()[i]} for patterns translated from AE2, so the executor can build
 * the per-slot {@code KeyCounter[]} for {@code pushPattern} directly.
 */
public final class Pattern {
    public final int id;

    private final int[] inputItems;
    private final Amount[] inputAmounts;
    private final InputKind[] inputKinds;
    private final int[] inputDurability;        // TOOL only: runs one tool lasts
    private final int[][] inputAlternatives;    // other items accepted in the slot (allowSubst only)
    private final int[] inputFaces;             // HUB_MACHINE: machine faces the slot goes in through (bits), 0 = not set
    private final int[] inputGuiSlots;         // HUB_MACHINE: GUI slot the material goes into, 0 = the first that takes it
    private final int[] inputRoles;             // HUB_MACHINE: PatternInputRoles per slot, 0 = used up
    private final int[] inputTargets;           // HUB_MACHINE: where the slot goes (PatternInputTargets), 0 = the machine

    private final int[] outputItems;
    private final Amount[] outputAmounts;
    private final boolean[] outputByproduct;
    private final double[] outputChance;        // 1: always
    private final java.util.Map<Integer, Integer> outputGuiSlots;   // HUB_MACHINE: product item id -> GUI slot it is taken out of
    private final int[] nbtFreeOutputs;         // HUB_MACHINE: products that count whatever their NBT

    public final PatternLocation where;
    /** HUB_MACHINE: the item id of the machine it runs in; −1 otherwise */
    public final int machine;
    /** HUB_MACHINE: item ids of the blocks near the machine that materials sent to sub-machines go into, in order */
    private final int[] subMachines;
    public final int priority;                  // provider priority (AE2, higher first)
    public final boolean allowSubst;
    public final Rational energyPerRun;         // AE energy per run, exact (AE2: sum of inputs / amount per operation)
    /** The original AE2 {@code IPatternDetails}; null for patterns built from ids in tests. */
    private final @Nullable Object source;

    private Pattern(Builder b, int id) {
        this.id = id;
        int n = b.inItems.size();
        this.inputItems = new int[n];
        this.inputAmounts = new Amount[n];
        this.inputKinds = new InputKind[n];
        this.inputDurability = new int[n];
        this.inputAlternatives = new int[n][];
        this.inputFaces = new int[n];
        this.inputTargets = new int[n];
        this.inputRoles = new int[n];
        this.inputGuiSlots = new int[n];
        for (int i = 0; i < n; i++) {
            inputItems[i] = b.inItems.get(i);
            inputAmounts[i] = b.inAmounts.get(i);
            inputKinds[i] = b.inKinds.get(i);
            inputDurability[i] = b.inDurability.get(i);
            inputAlternatives[i] = b.inAlternatives.get(i).clone();
            inputFaces[i] = i < b.inFaces.size() ? b.inFaces.get(i) : 0;
            inputTargets[i] = i < b.inTargets.size() ? b.inTargets.get(i) : 0;
            inputRoles[i] = i < b.inRoles.size() ? b.inRoles.get(i) : 0;
            inputGuiSlots[i] = i < b.inGuiSlots.size() ? b.inGuiSlots.get(i) : 0;
        }
        // outputs: merge repeated items so outputAmount(x) is a single lookup
        // (an item that is also a main output is a main output for the whole amount)
        List<Integer> oi = new ArrayList<>();
        List<Amount> oa = new ArrayList<>();
        List<Boolean> ob = new ArrayList<>();
        List<Double> oc = new ArrayList<>();
        for (int i = 0; i < b.outItems.size(); i++) {
            int item = b.outItems.get(i);
            int at = oi.indexOf(item);
            if (at >= 0) {
                // the same item twice, one with a probability: the expected amount over both
                Amount a = oa.get(at), c = b.outAmounts.get(i);
                Amount total = a.add(c);
                if (oc.get(at) < 1 || b.outChance.get(i) < 1) {
                    double expected = Math.pow(10, a.log10()) * oc.get(at) + Math.pow(10, c.log10()) * b.outChance.get(i);
                    oc.set(at, Math.min(1, expected / Math.pow(10, total.log10())));
                }
                oa.set(at, total);
                ob.set(at, ob.get(at) && b.outByproduct.get(i));
            } else {
                oi.add(item);
                oa.add(b.outAmounts.get(i));
                ob.add(b.outByproduct.get(i));
                oc.add(b.outChance.get(i));
            }
        }
        this.outputItems = oi.stream().mapToInt(Integer::intValue).toArray();
        this.outputAmounts = oa.toArray(new Amount[0]);
        this.outputByproduct = new boolean[ob.size()];
        for (int i = 0; i < ob.size(); i++)
            outputByproduct[i] = ob.get(i);
        this.outputChance = oc.stream().mapToDouble(Double::doubleValue).toArray();
        this.outputGuiSlots = java.util.Map.copyOf(b.outGuiSlots);
        this.nbtFreeOutputs = b.nbtFreeOutputs.stream().mapToInt(Integer::intValue).toArray();
        this.where = b.where;
        this.machine = b.machine;
        this.subMachines = b.subMachines.stream().mapToInt(Integer::intValue).toArray();
        this.priority = b.priority;
        this.allowSubst = b.allowSubst;
        this.energyPerRun = b.energyPerRun;
        this.source = b.source;
    }

    /** The GUI slot product {@code item} is taken out of (1 and up); 0 = from any slot that does not take it. */
    public int outputGuiSlotOf(int item) {
        return outputGuiSlots.getOrDefault(item, 0);
    }

    /** Product {@code item} counts when a machine gives it with other NBT (durability aside). */
    public boolean ignoresNbtOf(int item) {
        for (int o : nbtFreeOutputs)
            if (o == item)
                return true;
        return false;
    }

    public int[] nbtFreeOutputs() {
        return nbtFreeOutputs.clone();
    }

    public int subMachineCount() {
        return subMachines.length;
    }

    public int subMachine(int i) {
        return subMachines[i];
    }

    public int inputCount() {
        return inputItems.length;
    }

    public int inputItem(int i) {
        return inputItems[i];
    }

    public Amount inputAmount(int i) {
        return inputAmounts[i];
    }

    public InputKind inputKind(int i) {
        return inputKinds[i];
    }

    public int inputDurability(int i) {
        return inputDurability[i];
    }

    /** Machine faces input slot {@code i} goes in through (bits by {@code Direction.get3DDataValue}); 0 = not set. */
    public int inputFaces(int i) {
        return inputFaces[i];
    }

    /** The GUI slot input slot {@code i} goes into (1 and up); 0 = the first slot that takes it. */
    public int inputGuiSlot(int i) {
        return inputGuiSlots[i];
    }

    /** What input slot {@code i} is besides used up ({@code PatternInputRoles}): 0 = nothing, it is used up. */
    public int inputRole(int i) {
        return inputRoles[i];
    }

    /** Where input slot {@code i} goes ({@code PatternInputTargets}); 0 = into the machine. */
    public int inputTarget(int i) {
        return inputTargets[i];
    }

    public int alternativeCount(int i) {
        return inputAlternatives[i].length;
    }

    public int alternative(int i, int j) {
        return inputAlternatives[i][j];
    }

    public int outputCount() {
        return outputItems.length;
    }

    public int outputItem(int i) {
        return outputItems[i];
    }

    public Amount outputAmount(int i) {
        return outputAmounts[i];
    }

    public boolean isByproduct(int i) {
        return outputByproduct[i];
    }

    /** Probability that one run gives output {@code i} (all of its amount); 1 for most outputs. */
    public double outputChance(int i) {
        return outputChance[i];
    }

    public boolean isChance(int i) {
        return outputChance[i] < 1;
    }

    public boolean hasChanceOutput() {
        for (double c : outputChance)
            if (c < 1)
                return true;
        return false;
    }

    /** What one run surely gives of output {@code i}: nothing for an output that comes with a probability. */
    public Amount sureOutputAmount(int i) {
        return outputChance[i] < 1 ? Amount.ZERO : outputAmounts[i];
    }

    /** {@link #outputAmountOf}, nothing when it comes with a probability. */
    public Amount sureOutputAmountOf(int item) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item)
                return sureOutputAmount(i);
        return Amount.ZERO;
    }

    /** Probability of {@code item} as an output; 1 when it always comes or is no output. */
    public double chanceOf(int item) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item)
                return outputChance[i];
        return 1;
    }

    /**
     * log10 of the expected main amount of {@code item} per run (amount × probability), what costs
     * count; −∞ ({@link LogMath#ZERO}) when it is no main product.
     */
    public double mainOutputLog10(int item) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item)
                return outputByproduct[i] ? LogMath.ZERO : outputAmounts[i].log10() + Math.log10(outputChance[i]);
        return LogMath.ZERO;
    }

    /**
     * Runs that cover {@code demand} of main product {@code item}: a plain division, or for an
     * output that comes with a probability enough runs for {@link ChanceRuns#CONFIDENCE}.
     */
    public Amount runsFor(int item, Amount demand) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item && !outputByproduct[i])
                return outputChance[i] < 1 ? ChanceRuns.runs(outputAmounts[i], outputChance[i], demand)
                        : demand.ceilDiv(outputAmounts[i]);
        throw new IllegalArgumentException("P" + id + " makes no " + item);
    }

    /** Amount of {@code item} one run produces, byproducts included; ZERO if it is not an output. */
    public Amount outputAmountOf(int item) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item)
                return outputAmounts[i];
        return Amount.ZERO;
    }

    /** Amount of {@code item} one run makes as a main product: what a route to {@code item} counts; ZERO otherwise. */
    public Amount mainOutputAmountOf(int item) {
        for (int i = 0; i < outputItems.length; i++)
            if (outputItems[i] == item)
                return outputByproduct[i] ? Amount.ZERO : outputAmounts[i];
        return Amount.ZERO;
    }

    /** Any output, byproducts included. */
    public boolean produces(int item) {
        for (int o : outputItems)
            if (o == item)
                return true;
        return false;
    }

    /** A main product: the pattern is a route to {@code item}. */
    public boolean producesMain(int item) {
        return mainOutputAmountOf(item).signum() > 0;
    }

    /** Amount of {@code item} one run consumes (CONSUME slots only). */
    public Amount consumedAmountOf(int item) {
        Amount sum = Amount.ZERO;
        for (int i = 0; i < inputItems.length; i++)
            if (inputItems[i] == item && inputKinds[i] == InputKind.CONSUME)
                sum = sum.add(inputAmounts[i]);
        return sum;
    }

    public boolean hasInput(int item) {
        for (int in : inputItems)
            if (in == item)
                return true;
        return false;
    }

    public @Nullable Object source() {
        return source;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("P").append(id).append('[');
        for (int i = 0; i < inputItems.length; i++) {
            if (i > 0)
                sb.append(" + ");
            sb.append(inputAmounts[i]).append('×').append(inputItems[i]);
            if (inputKinds[i] != InputKind.CONSUME)
                sb.append('(').append(inputKinds[i]).append(')');
        }
        sb.append(" → ");
        for (int i = 0; i < outputItems.length; i++) {
            if (i > 0)
                sb.append(" + ");
            sb.append(outputAmounts[i]).append('×').append(outputItems[i]);
            if (outputByproduct[i])
                sb.append("(BYPRODUCT)");
            if (outputChance[i] < 1)
                sb.append('(').append(outputChance[i]).append(')');
        }
        return sb.append(']').toString();
    }

    public static final class Builder {
        private final List<Integer> inItems = new ArrayList<>();
        private final List<Amount> inAmounts = new ArrayList<>();
        private final List<InputKind> inKinds = new ArrayList<>();
        private final List<Integer> inDurability = new ArrayList<>();
        private final List<int[]> inAlternatives = new ArrayList<>();
        private final List<Integer> outItems = new ArrayList<>();
        private final List<Amount> outAmounts = new ArrayList<>();
        private final List<Boolean> outByproduct = new ArrayList<>();
        private final List<Double> outChance = new ArrayList<>();
        private final java.util.Map<Integer, Integer> outGuiSlots = new java.util.HashMap<>();
        private PatternLocation where = PatternLocation.HUB_INTERNAL;
        private int machine = -1;
        private final List<Integer> subMachines = new ArrayList<>();
        private final List<Integer> inFaces = new ArrayList<>();
        private final List<Integer> inTargets = new ArrayList<>();
        private final List<Integer> inRoles = new ArrayList<>();
        private final List<Integer> inGuiSlots = new ArrayList<>();
        private final List<Integer> nbtFreeOutputs = new ArrayList<>();
        private int priority;
        private boolean allowSubst;
        private Rational energyPerRun = Rational.ZERO;
        private @Nullable Object source;

        public Builder input(int item, long amount) {
            return input(item, Amount.of(amount), InputKind.CONSUME, 0, new int[0]);
        }

        public Builder input(int item, Amount amount, InputKind kind, int durability, int[] alternatives) {
            if (amount.signum() <= 0)
                throw new IllegalArgumentException("input amount must be positive");
            inItems.add(item);
            inAmounts.add(amount);
            inKinds.add(kind);
            inDurability.add(durability);
            inAlternatives.add(alternatives.clone());
            return this;
        }

        public Builder catalyst(int item, long amount) {
            return input(item, Amount.of(amount), InputKind.CATALYST, 0, new int[0]);
        }

        public Builder container(int item, long amount) {
            return input(item, Amount.of(amount), InputKind.CONTAINER, 0, new int[0]);
        }

        public Builder tool(int item, int durability) {
            return input(item, Amount.ONE, InputKind.TOOL, durability, new int[0]);
        }

        /** The GUI slot product {@code item} is taken out of. */
        public Builder outputGuiSlot(int item, int slot) {
            outGuiSlots.put(item, slot);
            return this;
        }

        public Builder output(int item, long amount) {
            return output(item, Amount.of(amount));
        }

        public Builder output(int item, Amount amount) {
            return output(item, amount, false);
        }

        /** What the recipe leaves behind without the pattern saying so: comes out, but is no route to the item. */
        public Builder byproduct(int item, long amount) {
            return output(item, Amount.of(amount), true);
        }

        public Builder byproduct(int item, Amount amount) {
            return output(item, amount, true);
        }

        /** A main product that one run gives (all of {@code amount}) only with probability {@code chance}. */
        public Builder output(int item, Amount amount, double chance) {
            if (!(chance > 0 && chance <= 1))
                throw new IllegalArgumentException("output chance must be in (0, 1]: " + chance);
            return output(item, amount, false, chance);
        }

        public Builder output(int item, long amount, double chance) {
            return output(item, Amount.of(amount), chance);
        }

        private Builder output(int item, Amount amount, boolean byproduct) {
            return output(item, amount, byproduct, 1);
        }

        /** Any output as {@link #outputCount() a built pattern} lists it (restoring a saved pattern). */
        public Builder output(int item, Amount amount, boolean byproduct, double chance) {
            if (amount.signum() <= 0)
                throw new IllegalArgumentException("output amount must be positive");
            outItems.add(item);
            outAmounts.add(amount);
            outByproduct.add(byproduct);
            outChance.add(chance);
            return this;
        }

        /** A hub pattern for a machine (item id): {@link PatternLocation#HUB_MACHINE}. */
        public Builder machine(int machine) {
            this.machine = machine;
            this.where = PatternLocation.HUB_MACHINE;
            return this;
        }

        /** A machine tried after the main one (and the sub-machines added before it). */
        public Builder subMachine(int machine) {
            if (machine >= 0 && machine != this.machine && !subMachines.contains(machine))
                subMachines.add(machine);
            return this;
        }

        /** Product {@code item} counts whatever its NBT. */
        public Builder ignoreNbtOf(int item) {
            if (!nbtFreeOutputs.contains(item))
                nbtFreeOutputs.add(item);
            return this;
        }

        /** Where the last input added goes ({@code PatternInputTargets}). */
        public Builder target(int target) {
            while (inTargets.size() < inItems.size())
                inTargets.add(0);
            inTargets.set(inItems.size() - 1, target);
            return this;
        }

        /** The GUI slot the last input added goes into. */
        public Builder guiSlot(int slot) {
            while (inGuiSlots.size() < inItems.size())
                inGuiSlots.add(0);
            inGuiSlots.set(inItems.size() - 1, slot);
            return this;
        }

        /** What the last input added is besides used up ({@code PatternInputRoles}). */
        public Builder role(int role) {
            while (inRoles.size() < inItems.size())
                inRoles.add(0);
            inRoles.set(inItems.size() - 1, role);
            return this;
        }

        /** The machine faces the last input added goes in through. */
        public Builder faces(int faces) {
            while (inFaces.size() < inItems.size())
                inFaces.add(0);
            inFaces.set(inItems.size() - 1, faces);
            return this;
        }

        public Builder where(PatternLocation where) {
            this.where = where;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder allowSubst(boolean allowSubst) {
            this.allowSubst = allowSubst;
            return this;
        }

        public Builder energyPerRun(Rational energyPerRun) {
            this.energyPerRun = energyPerRun;
            return this;
        }

        public Builder source(@Nullable Object source) {
            this.source = source;
            return this;
        }

        public Pattern build(int id) {
            if (!outByproduct.contains(false))
                throw new IllegalStateException("a pattern needs at least one main output");
            return new Pattern(this, id);
        }
    }
}
