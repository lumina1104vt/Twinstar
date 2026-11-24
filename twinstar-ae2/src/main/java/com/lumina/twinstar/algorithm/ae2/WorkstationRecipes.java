package com.lumina.twinstar.algorithm.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import com.lumina.twinstar.algorithm.ae2.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Whether a hub pattern whose "machine" is a crafting table, stonecutter or smithing table (no machine:
 * the black hole finishes it) is a real recipe of that block. A processing pattern holds whatever the
 * player typed into it, so without this one saying "1 dirt → 1000 diamonds" would make diamonds.
 * <p>
 * It is one when some recipe of the block's type has: the pattern's main product as its result, at most
 * the result's amount per run (k runs: k times as much); the pattern's materials covering all the
 * recipe's ingredients k times over (more is only wasted); any other product only what a material leaves
 * behind (an empty bucket), at most as many as those materials. Special recipes (dyed armour, fireworks)
 * and smithing trims say nothing checkable: never.
 */
public final class WorkstationRecipes {
    /** Named as a pattern's machine, these need none (the black hole does it). */
    public static final TagKey<Item> NO_MACHINE =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("twinstar", "no_machine"));
    private static final TagKey<Item> CRAFTING_TABLES =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "player_workstations/crafting_tables"));

    /** A pattern's material: what it may be, and how many. */
    public record In(List<AEKey> options, long amount) {
    }

    private WorkstationRecipes() {
    }

    public static boolean isWorkstation(Item item) {
        return item.builtInRegistryHolder().is(NO_MACHINE);
    }

    /** {@link #matches(Item, List, List, RecipeManager, RegistryAccess)} for a pattern as AE2 decoded it. */
    public static boolean matches(Item machine, IPatternDetails details, RecipeManager recipes, RegistryAccess registries) {
        List<In> ins = new ArrayList<>();
        for (IPatternDetails.IInput in : details.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible.length == 0)
                return false;
            List<AEKey> options = new ArrayList<>();
            for (GenericStack g : possible)
                options.add(g.what());
            ins.add(new In(options, Math.multiplyExact(possible[0].amount(), in.getMultiplier())));
        }
        return matches(machine, ins, java.util.Arrays.asList(details.getOutputs()), recipes, registries);
    }

    public static boolean matches(Item machine, List<In> inputs, List<GenericStack> outputs, RecipeManager recipes,
                                  RegistryAccess registries) {
        if (outputs.isEmpty() || inputs.isEmpty())
            return false;
        try {
            if (machine == Items.STONECUTTER) {
                for (RecipeHolder<?> h : RecipeHolder.all(recipes.getAllRecipesFor(RecipeType.STONECUTTING)))
                    if (check(h.value(), inputs, outputs, registries))
                        return true;
            } else if (machine == Items.SMITHING_TABLE) {
                for (RecipeHolder<?> h : RecipeHolder.all(recipes.getAllRecipesFor(RecipeType.SMITHING)))
                    if (check(h.value(), inputs, outputs, registries))
                        return true;
            } else if (machine == Items.CRAFTING_TABLE || machine.builtInRegistryHolder().is(CRAFTING_TABLES)) {
                for (RecipeHolder<?> h : RecipeHolder.all(recipes.getAllRecipesFor(RecipeType.CRAFTING)))
                    if (check(h.value(), inputs, outputs, registries))
                        return true;
            }
        } catch (RuntimeException e) {
            // a mod's recipe that cannot answer outside its machine: not one we can vouch for
        }
        return false;   // a loom, a cartography table, or no recipe like it
    }

    private static boolean check(Recipe<?> recipe, List<In> inputs, List<GenericStack> outputs, RegistryAccess registries) {
        if (recipe.isSpecial() || recipe instanceof SmithingTrimRecipe)
            return false;
        ItemStack result = recipe.getResultItem(registries);
        if (result.isEmpty())
            return false;
        // the main product: the recipe's result, so many runs of it
        int main = -1;
        for (int o = 0; o < outputs.size() && main < 0; o++)
            if (outputs.get(o).what() instanceof AEItemKey k && k.matches(result))
                main = o;
        if (main < 0)
            return false;
        long runs = Math.max(1, -Math.floorDiv(-outputs.get(main).amount(), result.getCount()));
        List<Predicate<ItemStack>> needs = new ArrayList<>();
        if (recipe instanceof SmithingRecipe smithing) {
            needs.add(smithing::isTemplateIngredient);
            needs.add(smithing::isBaseIngredient);
            needs.add(smithing::isAdditionIngredient);
        } else {
            for (Ingredient ing : recipe.getIngredients())
                if (!ing.isEmpty())
                    needs.add(ing);
        }
        if (needs.isEmpty() || !covers(needs, runs, inputs))
            return false;
        // anything else that comes out: only what a material leaves behind
        for (int o = 0; o < outputs.size(); o++) {
            if (o == main)
                continue;
            GenericStack extra = outputs.get(o);
            long from = 0;
            for (In in : inputs)
                for (AEKey opt : in.options())
                    if (opt instanceof AEItemKey item) {
                        ItemStack left = item.toStack().getCraftingRemainingItem();
                        if (!left.isEmpty() && extra.what() instanceof AEItemKey k && k.matches(left)) {
                            from += in.amount() * left.getCount();
                            break;
                        }
                    }
            if (extra.amount() > from)
                return false;
        }
        return true;
    }

    /**
     * Each need {@code runs} times, each from a material that is right for it whatever option of it
     * comes: a max flow from the needs to the materials (a few nodes, small numbers).
     */
    private static boolean covers(List<Predicate<ItemStack>> needs, long runs, List<In> inputs) {
        int n = needs.size(), m = inputs.size();
        boolean[][] fits = new boolean[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) {
                boolean all = true;
                for (AEKey opt : inputs.get(j).options())
                    if (!(opt instanceof AEItemKey item) || !needs.get(i).test(item.toStack())) {
                        all = false;
                        break;
                    }
                fits[i][j] = all;
            }
        // nodes: 0 source, 1..n needs, n+1..n+m materials, n+m+1 sink
        int size = n + m + 2, sink = size - 1;
        long[][] cap = new long[size][size];
        for (int i = 0; i < n; i++) {
            cap[0][1 + i] = runs;
            for (int j = 0; j < m; j++)
                if (fits[i][j])
                    cap[1 + i][1 + n + j] = Long.MAX_VALUE / 4;
        }
        for (int j = 0; j < m; j++)
            cap[1 + n + j][sink] = inputs.get(j).amount();
        long flow = 0, want = runs * n;
        while (flow < want) {
            int[] prev = new int[size];
            java.util.Arrays.fill(prev, -1);
            prev[0] = 0;
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(0);
            while (!queue.isEmpty() && prev[sink] < 0) {
                int u = queue.poll();
                for (int v = 0; v < size; v++)
                    if (prev[v] < 0 && cap[u][v] > 0) {
                        prev[v] = u;
                        queue.add(v);
                    }
            }
            if (prev[sink] < 0)
                break;
            long push = Long.MAX_VALUE;
            for (int v = sink; v != 0; v = prev[v])
                push = Math.min(push, cap[prev[v]][v]);
            for (int v = sink; v != 0; v = prev[v]) {
                cap[prev[v]][v] -= push;
                cap[v][prev[v]] += push;
            }
            flow += push;
        }
        return flow >= want;
    }
}
