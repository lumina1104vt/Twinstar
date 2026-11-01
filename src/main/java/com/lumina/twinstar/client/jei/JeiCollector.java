package com.lumina.twinstar.client.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.tools.JeiPackets;
import com.lumina.twinstar.tools.JeiRecipeData;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client side of {@code gen_patterns} / {@code dump_recipes}: walks every JEI category and sends
 * what each recipe shows to the server. Laying a recipe out is how JEI tells its slots, so this
 * runs on the client thread a little each tick ({@link #TICK_BUDGET_NANOS}) and sends batches of
 * {@link #BATCH} recipes. Only loaded when JEI is there.
 */
public final class JeiCollector {
    static volatile @Nullable IJeiRuntime runtime;

    private static final long TICK_BUDGET_NANOS = 8_000_000L;
    private static final int BATCH = 64;
    /**
     * Categories left out: crafting and stonecutter patterns are made from the server's recipes;
     * information pages, fuel and composting have no output; the anvil's result depends on the
     * actual items.
     */
    private static final Set<ResourceLocation> LEFT_OUT = Set.of(RecipeTypes.CRAFTING.getUid(),
            RecipeTypes.STONECUTTING.getUid(), RecipeTypes.INFORMATION.getUid(), RecipeTypes.FUELING.getUid(),
            RecipeTypes.COMPOSTING.getUid(), RecipeTypes.ANVIL.getUid());

    private static @Nullable JeiCollector active;
    private static final Consumer<ClientTickEvent.Post> TICK = e -> {
        if (active != null && active.step())
            active = null;
    };
    private static boolean listening;

    private final int request;
    private final IRecipeManager recipes;
    private final IFocusGroup noFocus;
    private final Iterator<IRecipeCategory<?>> categories;
    private @Nullable IRecipeCategory<?> category;
    private @Nullable Iterator<?> inCategory;
    private final List<JeiRecipeData> batch = new ArrayList<>();
    private final Map<ResourceLocation, Integer> skipped = new LinkedHashMap<>();

    private JeiCollector(int request, IJeiRuntime rt) {
        this.request = request;
        this.recipes = rt.getRecipeManager();
        this.noFocus = rt.getJeiHelpers().getFocusFactory().getEmptyFocusGroup();
        this.categories = recipes.createRecipeCategoryLookup().get().toList().iterator();
    }

    /** Starts answering request {@code request} (a new request replaces one still running). */
    public static void start(int request) {
        IJeiRuntime rt = runtime;
        if (rt == null) {
            PacketDistributor.sendToServer(JeiPackets.Recipes.failure(request, "JEI 還沒載入完成，請稍後再試"));
            return;
        }
        active = new JeiCollector(request, rt);
        if (!listening) {
            NeoForge.EVENT_BUS.addListener(TICK);
            listening = true;
        }
    }

    /** Works for one tick's budget; true when everything is sent. */
    private boolean step() {
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        try {
            while (System.nanoTime() < deadline) {
                if (inCategory == null || !inCategory.hasNext()) {
                    if (!categories.hasNext()) {
                        PacketDistributor.sendToServer(new JeiPackets.Recipes(request, List.copyOf(batch), skipped, true, ""));
                        return true;
                    }
                    category = categories.next();
                    inCategory = nextCategory(category);
                    continue;
                }
                one(category, inCategory.next());
                if (batch.size() >= BATCH) {
                    PacketDistributor.sendToServer(new JeiPackets.Recipes(request, List.copyOf(batch), Map.of(), false, ""));
                    batch.clear();
                }
            }
        } catch (RuntimeException e) {
            Twinstar.LOGGER.error("[雙星] 讀取 JEI 配方時出錯", e);
            PacketDistributor.sendToServer(JeiPackets.Recipes.failure(request, "讀取 JEI 配方時出錯：" + e));
            return true;
        }
        return false;
    }

    /** The category's recipes, or nothing (counted as left out) for the categories that make no patterns. */
    private Iterator<?> nextCategory(IRecipeCategory<?> c) {
        RecipeType<?> type = c.getRecipeType();
        List<?> all = recipes.createRecipeLookup(type).get().toList();
        if (LEFT_OUT.contains(type.getUid())) {
            if (!all.isEmpty())
                skipped.merge(type.getUid(), all.size(), Integer::sum);
            return List.of().iterator();
        }
        return all.iterator();
    }

    @SuppressWarnings("unchecked")
    private <T> void one(IRecipeCategory<T> c, Object recipe) {
        T r = (T) recipe;
        ResourceLocation id;
        try {
            id = c.getRegistryName(r);
        } catch (RuntimeException e) {
            id = null;
        }
        Optional<IRecipeLayoutDrawable<T>> layout = recipes.createRecipeLayoutDrawable(c, r, noFocus);
        if (layout.isEmpty()) {
            skipped.merge(c.getRecipeType().getUid(), 1, Integer::sum);
            return;
        }
        List<List<GenericStack>> in = slots(layout.get().getRecipeSlotsView().getSlotViews(RecipeIngredientRole.INPUT));
        List<List<GenericStack>> out = slots(layout.get().getRecipeSlotsView().getSlotViews(RecipeIngredientRole.OUTPUT));
        batch.add(new JeiRecipeData(id, c.getRecipeType().getUid(), in, out));
    }

    /** Per slot what it cycles through, as AE2 stacks (items and fluids; anything else is left out of the slot). */
    private static List<List<GenericStack>> slots(List<IRecipeSlotView> views) {
        List<List<GenericStack>> out = new ArrayList<>();
        for (IRecipeSlotView v : views) {
            if (v.isEmpty())
                continue;
            List<GenericStack> options = new ArrayList<>();
            for (ITypedIngredient<?> t : v.getAllIngredientsList()) {
                GenericStack s = stackOf(t);
                if (s != null && !options.contains(s))
                    options.add(s);
                if (options.size() >= JeiRecipeData.MAX_OPTIONS)
                    break;
            }
            out.add(options);     // an empty list: a slot with something AE2 cannot hold (the server skips the recipe)
        }
        return out;
    }

    private static @Nullable GenericStack stackOf(ITypedIngredient<?> t) {
        Optional<ItemStack> item = t.getIngredient(VanillaTypes.ITEM_STACK);
        if (item.isPresent() && !item.get().isEmpty())
            return new GenericStack(AEItemKey.of(item.get()), item.get().getCount());
        Optional<FluidStack> fluid = t.getIngredient(NeoForgeTypes.FLUID_STACK);
        if (fluid.isPresent() && !fluid.get().isEmpty())
            return new GenericStack(AEFluidKey.of(fluid.get()), fluid.get().getAmount());
        return null;
    }
}
