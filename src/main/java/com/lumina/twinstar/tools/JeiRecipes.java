package com.lumina.twinstar.tools;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import com.lumina.twinstar.util.RecipeHolder;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What {@code gen_patterns} and {@code dump_recipes} make of a recipe from the player's JEI: the
 * same rules for both, so the dump is what the patterns are. {@link Tally} counts what is left out
 * and why.
 */
final class JeiRecipes {
    private JeiRecipes() {
    }

    /**
     * A recipe that makes a processing pattern. {@code inputs}: per distinct option list (first
     * option first) its amount, the first option's; {@code outputs}: per key its amount, with the
     * probability of the outputs that do not always come.
     */
    record Converted(RecipeHolder<?> holder, Map<List<AEKey>, Long> inputs, Map<AEKey, Long> outputs,
                     Map<AEKey, Double> chances) {
        /** The inputs as a processing pattern takes them: first options, merged by key. */
        List<GenericStack> patternInputs() {
            Map<AEKey, Long> merged = new LinkedHashMap<>();
            inputs.forEach((options, n) -> merged.merge(options.get(0), n, Long::sum));
            List<GenericStack> out = new ArrayList<>();
            merged.forEach((k, v) -> out.add(new GenericStack(k, v)));
            return out;
        }

        List<GenericStack> patternOutputs() {
            List<GenericStack> out = new ArrayList<>();
            outputs.forEach((k, v) -> out.add(new GenericStack(k, v)));
            return out;
        }
    }

    /** What was left out, overall and per JEI category. */
    static final class Tally {
        /** per JEI category: patterns made, recipes left out */
        final Map<ResourceLocation, int[]> perCategory = new TreeMap<>();
        int noId, unknown, trims, special, selfLoops, unholdable, chanceOutputs, chanceRecipes;
        final List<String> unknownIds = new ArrayList<>();
        final List<String> selfLoopIds = new ArrayList<>();

        void made(ResourceLocation category) {
            perCategory.computeIfAbsent(category, k -> new int[2])[0]++;
        }

        void left(ResourceLocation category, int n) {
            perCategory.computeIfAbsent(category, k -> new int[2])[1] += n;
        }

        /** Counts a recipe that was made, for the probability part of the summary. */
        void chances(Converted c) {
            if (!c.chances().isEmpty()) {
                chanceRecipes++;
                chanceOutputs += c.chances().size();
            }
        }

        /** The left-out part of a summary ("、" before each item), empty when nothing was. */
        String leftOut() {
            StringBuilder sb = new StringBuilder();
            if (noId + unknown > 0) {
                sb.append("、").append(noId + unknown).append(" 條判斷不了機率的配方（JEI 沒有 ID ").append(noId)
                        .append("，伺服器找不到 ").append(unknown);
                if (!unknownIds.isEmpty())
                    sb.append("：").append(String.join("、", unknownIds)).append(unknown > unknownIds.size() ? "…" : "");
                sb.append("）");
            }
            if (trims > 0)
                sb.append("、").append(trims).append(" 條鍛造台紋飾");
            if (special > 0)
                sb.append("、").append(special).append(" 條 JEI 裡的特殊配方");
            if (unholdable > 0)
                sb.append("、").append(unholdable).append(" 條有 AE2 放不進樣板的材料或產物");
            return sb.toString();
        }

        /** Per category made / left out, the biggest first; at most {@code shown} of them, all when {@code shown} is negative. */
        String categories(int shown) {
            List<Map.Entry<ResourceLocation, int[]>> cats = new ArrayList<>(perCategory.entrySet());
            cats.sort((a, b) -> Integer.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
            StringBuilder sb = new StringBuilder();
            int n = shown < 0 ? cats.size() : Math.min(shown, cats.size());
            for (int i = 0; i < n; i++) {
                if (shown < 0)
                    sb.append("\n  ").append(cats.get(i).getKey()).append("：");
                else
                    sb.append(i > 0 ? "、" : "").append(cats.get(i).getKey()).append(" ");
                sb.append(cats.get(i).getValue()[0]).append("／").append(cats.get(i).getValue()[1]);
            }
            if (n < cats.size())
                sb.append("…（共 ").append(cats.size()).append(" 個分類）");
            return sb.toString();
        }
    }

    /**
     * {@code r} as a processing pattern, or null (counted in {@code tally}) when it makes none: no
     * id or one the server does not know (the probabilities are read from the server's recipe), a
     * crafting or stonecutter recipe (made from the server's recipe), a smithing trim, a special
     * recipe, pure A → A, or a slot AE2 cannot hold. Per-category counts are left to the caller.
     */
    static @Nullable Converted convert(JeiRecipeData r, ServerLevel level, Tally tally) {
        if (r.id() == null) {
            tally.noId++;
            return null;
        }
        RecipeHolder<?> holder = level.getRecipeManager().byKey(r.id()).map(x -> (RecipeHolder<?>) RecipeHolder.of(x)).orElse(null);
        if (holder == null) {
            tally.unknown++;
            if (tally.unknownIds.size() < 5)
                tally.unknownIds.add(r.id().toString());
            return null;
        }
        Recipe<?> recipe = holder.value();
        if (recipe instanceof CraftingRecipe || recipe instanceof StonecutterRecipe)
            return null;    // made from the server's recipe
        if (recipe instanceof SmithingTrimRecipe) {
            tally.trims++;
            return null;
        }
        try {
            if (recipe.isSpecial()) {
                tally.special++;
                return null;
            }
            if (PatternGen.pureSelfRecipe(recipe, level.registryAccess())) {
                tally.selfLoops++;
                if (tally.selfLoopIds.size() < 5)
                    tally.selfLoopIds.add(r.id().toString());
                return null;
            }
        } catch (RuntimeException e) {
            // a mod's recipe that cannot answer outside its machine: JEI's view is enough
        }
        Map<List<AEKey>, Long> in = new LinkedHashMap<>();
        for (List<GenericStack> slot : r.inputs()) {
            if (slot.isEmpty()) {
                tally.unholdable++;
                return null;
            }
            List<AEKey> options = new ArrayList<>();
            for (GenericStack s : slot)
                if (!options.contains(s.what()))
                    options.add(s.what());
            in.merge(List.copyOf(options), Math.max(1, slot.get(0).amount()), Long::sum);
        }
        Map<String, Double> byId = RecipeChances.of(recipe, level.registryAccess());
        Map<AEKey, Long> out = new LinkedHashMap<>();
        Map<AEKey, Double> chances = new LinkedHashMap<>();
        for (List<GenericStack> slot : r.outputs()) {
            if (slot.isEmpty())
                continue;   // something AE2 cannot hold comes out too: it goes elsewhere
            GenericStack s = slot.get(0);
            Double c = byId.get(s.what().getId().toString());
            if (out.containsKey(s.what()) && (c != null || chances.containsKey(s.what()))) {
                tally.unholdable++;     // the same output twice, one with a probability: AE2 would merge them
                return null;
            }
            out.merge(s.what(), Math.max(1, s.amount()), Long::sum);
            if (c != null)
                chances.put(s.what(), c);
        }
        if (in.isEmpty() || out.isEmpty()) {
            tally.unholdable++;
            return null;
        }
        return new Converted(holder, in, out, chances);
    }
}
