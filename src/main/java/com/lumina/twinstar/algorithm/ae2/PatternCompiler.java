package com.lumina.twinstar.algorithm.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.pattern.AEProcessingPattern;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.api.PatternMachines;
import com.lumina.twinstar.storage.TwinstarContent;
import appeng.api.stacks.AEItemKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Turns the network's AE2 patterns into internal patterns: the first registered adapter that
 * handles one translates it, otherwise its declared lists are used as they are.
 * <p>
 * Default translation, slot by slot (input slot i = {@code getInputs()[i]}, so the executor can
 * build {@code pushPattern}'s per-slot inputs):
 * <ul>
 *   <li>item = the first possible input, amount = its amount × the slot multiplier; the other
 *   possible inputs become alternatives and mark the pattern as allowing substitution;</li>
 *   <li>a remaining key equal to the input (the item comes back unchanged) makes the slot a
 *   CONTAINER; a different remaining key (water bucket → bucket, damaged tool) stays CONSUME and the
 *   remainder is added as a byproduct: the pattern does not list it, so it is no route to that item,
 *   but the ledger keeps it for the order's later steps. A hub pattern finished by the black hole takes
 *   the item's own crafting remainder when its pattern gives none (processing patterns never do);</li>
 *   <li>AE2 patterns carry no durability, so TOOL only comes from adapters;</li>
 *   <li>an output listed in the pattern's {@link PatternChances} (written by {@code gen_patterns}
 *   from the recipe) comes only with that probability. Such patterns only run in the hub: one in
 *   an ordinary pattern provider is not used;</li>
 *   <li>a hub pattern that names a machine ({@link PatternMachines}) is {@code HUB_MACHINE}: sent to
 *   that machine through machine access interfaces, its materials through the faces the pattern
 *   gives them ({@link PatternInputFaces}), or to where it says ({@link PatternInputTargets}): the
 *   sub-machines near it, or the cell in front of it;</li>
 *   <li>a material whose NBT does not matter ({@link PatternIgnoreNbt}) takes, as alternatives, every
 *   stack of the same item the network has (durability aside); a product so marked counts when its
 *   machine gives it with other NBT. A crafting table and the like ({@link #NO_MACHINE}) only say how
 *   the player would make it: such a pattern is finished by the black hole, with no machine.</li>
 * </ul>
 * A hub pattern the black hole would finish is used only when its products are real: AE2's crafting,
 * stonecutting and smithing patterns (AE2 builds them from the recipe), or one naming a crafting table
 * or the like that is that block's recipe ({@link WorkstationRecipes}). One naming no machine at all, or
 * not matching, is left out: a processing pattern says whatever the player typed into it.
 */
public final class PatternCompiler {
    /** Named as a pattern's machine, these need none. */
    private static final TagKey<Item> NO_MACHINE = WorkstationRecipes.NO_MACHINE;

    private final List<PatternAdapter> adapters = new ArrayList<>();
    private final ItemIndex index;
    private final AtomicInteger nextId = new AtomicInteger();

    /** A pattern id no pattern has had yet (patterns read back from a save get new ones). */
    public int newPatternId() {
        return nextId.getAndIncrement();
    }

    public PatternCompiler(ItemIndex index) {
        this.index = index;
    }

    /** The stacks of an item the network has (for materials whose NBT does not matter); none by default. */
    private java.util.function.Function<AEItemKey, List<AEItemKey>> variants = key -> List.of();
    /** The stacks of an item that patterns on the network make; none by default. */
    private java.util.function.Function<AEItemKey, List<AEItemKey>> craftable = key -> List.of();

    public void setVariants(java.util.function.Function<AEItemKey, List<AEItemKey>> variants,
                            java.util.function.Function<AEItemKey, List<AEItemKey>> craftable) {
        this.variants = variants;
        this.craftable = craftable;
    }

    /** Whether a hub pattern naming a crafting table or the like is that block's real recipe; none is by default. */
    private java.util.function.BiPredicate<IPatternDetails, Item> workstationCheck = (details, machine) -> false;

    public void setWorkstationCheck(java.util.function.BiPredicate<IPatternDetails, Item> check) {
        this.workstationCheck = check;
    }

    /** AE2's own crafting, stonecutting and smithing patterns: AE2 built them from the server's recipe. */
    private static boolean checkedByAe2(IPatternDetails details) {
        return details instanceof appeng.crafting.pattern.AECraftingPattern
                || details instanceof appeng.crafting.pattern.AEStonecuttingPattern
                || details instanceof appeng.crafting.pattern.AESmithingTablePattern;
    }

    public void register(PatternAdapter adapter) {
        adapters.add(adapter);
    }

    /**
     * @param priority    the provider's pattern priority (AE2, higher first)
     * @param hubInternal the pattern belongs to the twin-star hub itself (finished by the black hole)
     */
    public List<Pattern> compile(IPatternDetails details, int priority, boolean hubInternal) {
        PatternLocation where = hubInternal ? PatternLocation.HUB_INTERNAL
                : details instanceof AEProcessingPattern ? PatternLocation.EXTERNAL_PROCESS
                : PatternLocation.EXTERNAL_CRAFT;
        PatternChances chances = details.getDefinition().get(TwinstarContent.PATTERN_CHANCES.get());
        if (!hubInternal && chances != null && !chances.entries().isEmpty())
            return List.of();   // a pattern with probable outputs only runs in the hub
        Item machine = hubInternal ? PatternMachines.of(details.getDefinition()) : null;
        if (hubInternal && !checkedByAe2(details)) {
            // the black hole makes what the pattern says: only what a real machine does, or a real
            // recipe of a crafting table and the like, may come out
            if (machine == null)
                return List.of();
            if (WorkstationRecipes.isWorkstation(machine) && !workstationCheck.test(details, machine))
                return List.of();
        }
        if (machine != null && machine.builtInRegistryHolder().is(NO_MACHINE))
            machine = null;
        if (machine == null)
            for (PatternAdapter a : adapters)
                if (a.handles(details))
                    return a.translate(details, index, where, priority, nextId::getAndIncrement);
        List<Item> subs = new ArrayList<>();
        if (machine != null)
            for (Item sub : PatternMachines.subMachinesOf(details.getDefinition()))
                if (sub != null && !sub.builtInRegistryHolder().is(NO_MACHINE))
                    subs.add(sub);
        return List.of(translateDeclared(details, where, priority, chances, machine, subs));
    }

    /** One slot of a processing pattern, as it is: its material, all of it, and nothing left over. */
    private record SparseInput(GenericStack stack) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return new GenericStack[]{stack};
        }

        @Override
        public long getMultiplier() {
            return 1;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return input.equals(stack.what());
        }

        @Override
        public @Nullable AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }

    private Pattern translateDeclared(IPatternDetails details, PatternLocation where, int priority, @Nullable PatternChances chances,
                                      @Nullable Item machine, List<Item> subMachines) {
        Pattern.Builder b = new Pattern.Builder().where(where).priority(priority).source(details);
        if (machine != null) {
            b.machine(index.idOf(AEItemKey.of(machine)));
            for (Item sub : subMachines)
                b.subMachine(index.idOf(AEItemKey.of(sub)));
        }
        PatternInputFaces faces = machine != null ? details.getDefinition().get(TwinstarContent.PATTERN_INPUT_FACES.get()) : null;
        PatternInputTargets targets = machine != null ? details.getDefinition().get(TwinstarContent.PATTERN_INPUT_TARGETS.get()) : null;
        PatternInputGuiSlots guiSlots = machine != null ? details.getDefinition().get(TwinstarContent.PATTERN_INPUT_GUI_SLOTS.get()) : null;
        PatternOutputGuiSlots outGuiSlots = machine != null ? details.getDefinition().get(TwinstarContent.PATTERN_OUTPUT_GUI_SLOTS.get()) : null;
        PatternInputRoles roles = machine != null ? details.getDefinition().get(TwinstarContent.PATTERN_INPUT_ROLES.get()) : null;
        PatternIgnoreNbt ignoreNbt = details.getDefinition().get(TwinstarContent.PATTERN_IGNORE_NBT.get());
        boolean subst = false;
        Rational energy = Rational.ZERO;
        List<GenericStack> remainders = new ArrayList<>();
        int ordinal = -1;
        // AE2 merges identical materials of a processing pattern into one input; a pattern for a machine keeps its slots
        // apart (the same material goes into many places: slots of the GUI, or one input bus each, in order)
        List<IPatternDetails.IInput> inputList = machine != null && details instanceof AEProcessingPattern processing
                ? processing.getSparseInputs().stream().filter(java.util.Objects::nonNull).map(SparseInput::new)
                        .<IPatternDetails.IInput>map(i -> i).toList()
                : List.of(details.getInputs());
        for (IPatternDetails.IInput in : inputList) {
            ordinal++;
            GenericStack[] possible = in.getPossibleInputs();
            AEKey key = possible[0].what();
            long amount = Math.multiplyExact(possible[0].amount(), in.getMultiplier());
            List<Integer> altList = new ArrayList<>();
            for (int j = 1; j < possible.length; j++)
                altList.add(index.idOf(possible[j].what()));
            AEKey main = key;
            if (ignoreNbt != null && ignoreNbt.ignoresInput(key) && key instanceof AEItemKey item) {
                // what a pattern makes of the item is what the slot asks for, so the plan can make it when
                // none is stored: the pattern's own copy may differ from the made one only in its data
                List<AEItemKey> made = craftable.apply(item).stream()
                        .filter(v -> PatternIgnoreNbt.sameIgnoringNbt(v, key)).toList();
                if (!made.isEmpty() && !made.contains(key)) {
                    main = made.get(0);
                    altList.add(index.idOf(key));
                }
                List<AEItemKey> others = new ArrayList<>(variants.apply(item));
                others.addAll(made);
                for (AEItemKey v : others) {
                    int id = index.idOf(v);
                    if (!v.equals(main) && PatternIgnoreNbt.sameIgnoringNbt(v, key) && !altList.contains(id))
                        altList.add(id);
                }
            }
            int[] alts = altList.stream().mapToInt(Integer::intValue).toArray();
            if (alts.length > 0)
                subst = true;
            AEKey rest = in.getRemainingKey(key);
            if (rest == null && where == PatternLocation.HUB_INTERNAL && key instanceof AEItemKey item) {
                // finished by the black hole as a crafting table would: what crafting leaves of the item (a
                // master infusion crystal leaves itself) comes back; a processing pattern does not say so
                net.minecraft.world.item.ItemStack left = item.toStack().getCraftingRemainingItem();
                if (!left.isEmpty())
                    rest = AEItemKey.of(left);
            }
            int role = roles != null ? roles.roleOf(key) : PatternInputRoles.NORMAL;
            // a catalyst or a tool is not used up: a container's way, one copy is enough and it comes back
            InputKind kind = role != PatternInputRoles.NORMAL || rest != null && rest.equals(key) ? InputKind.CONTAINER : InputKind.CONSUME;
            if (role == PatternInputRoles.NORMAL && rest != null && !rest.equals(key))
                remainders.add(new GenericStack(rest, amount));
            b.input(index.idOf(main), Amount.of(amount), kind, 0, alts);
            if (role != PatternInputRoles.NORMAL)
                b.role(role);
            if (guiSlots != null && guiSlots.slotAt(ordinal) > 0)
                b.guiSlot(guiSlots.slotAt(ordinal));
            if (faces != null)
                b.faces(faces.facesOf(key));
            if (targets != null)
                b.target(targets.targetOf(key));
            energy = energy.add(Rational.of(java.math.BigInteger.valueOf(amount),
                    java.math.BigInteger.valueOf(Math.max(1, key.getAmountPerOperation()))));
        }
        Item tool = machine != null ? PatternMachines.toolOf(details.getDefinition()) : null;
        if (tool != null) {
            // not put into the machine: it right-clicks it once a run's materials are all in, then goes back to the job
            // wear does not matter: the same tool in any state of wear (any damage) does as well, in stock or made
            AEItemKey toolKey = AEItemKey.of(tool);
            List<Integer> worn = new ArrayList<>();
            for (AEItemKey v : variants.apply(toolKey))
                if (!v.equals(toolKey) && v.getItem() == tool && !worn.contains(index.idOf(v)))
                    worn.add(index.idOf(v));
            for (AEItemKey v : craftable.apply(toolKey))
                if (!v.equals(toolKey) && v.getItem() == tool && !worn.contains(index.idOf(v)))
                    worn.add(index.idOf(v));
            b.input(index.idOf(toolKey), Amount.of(1), InputKind.CONTAINER, 0, worn.stream().mapToInt(Integer::intValue).toArray());
            b.role(PatternInputRoles.TOOL);
            if (!worn.isEmpty())
                subst = true;
        }
        for (GenericStack out : details.getOutputs()) {
            double chance = chances == null ? 1 : chances.chanceOf(out.what());
            b.output(index.idOf(out.what()), Amount.of(out.amount()), chance > 0 && chance < 1 ? chance : 1);
            if (outGuiSlots != null && outGuiSlots.slotOf(out.what()) > 0)
                b.outputGuiSlot(index.idOf(out.what()), outGuiSlots.slotOf(out.what()));
        }
        for (GenericStack r : remainders)
            b.byproduct(index.idOf(r.what()), r.amount());
        if (ignoreNbt != null && machine != null)
            for (GenericStack out : details.getOutputs())
                if (ignoreNbt.ignoresOutput(out.what()))
                    b.ignoreNbtOf(index.idOf(out.what()));
        return b.allowSubst(subst).energyPerRun(energy).build(nextId.getAndIncrement());
    }
}
