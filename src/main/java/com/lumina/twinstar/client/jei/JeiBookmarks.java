package com.lumina.twinstar.client.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IBookmarkManager;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.neoforged.neoforge.fluids.FluidType;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/** JEI side of {@link com.lumina.twinstar.client.Bookmarks}: JEI's public bookmark manager. Only loaded when JEI is there. */
public final class JeiBookmarks {
    private JeiBookmarks() {
    }

    public static boolean ready() {
        return JeiCollector.runtime != null;
    }

    /** The keys that were new in the bookmarks (items and fluids; other keys have no JEI form). */
    public static Set<AEKey> add(Collection<AEKey> keys) {
        Set<AEKey> added = new LinkedHashSet<>();
        IJeiRuntime rt = JeiCollector.runtime;
        if (rt == null)
            return added;
        IBookmarkManager manager = rt.getBookmarkManager();
        IIngredientManager ingredients = rt.getIngredientManager();
        for (AEKey key : keys) {
            ITypedIngredient<?> ingredient = typed(ingredients, key);
            if (ingredient != null && !manager.contains(ingredient) && manager.add(ingredient))
                added.add(key);
        }
        return added;
    }

    private static @Nullable ITypedIngredient<?> typed(IIngredientManager ingredients, AEKey key) {
        Optional<? extends ITypedIngredient<?>> typed = Optional.empty();
        if (key instanceof AEItemKey item)
            typed = ingredients.createTypedIngredient(VanillaTypes.ITEM_STACK, item.toStack(), true);
        else if (key instanceof AEFluidKey fluid)
            typed = ingredients.createTypedIngredient(NeoForgeTypes.FLUID_STACK, fluid.toStack(FluidType.BUCKET_VOLUME), true);
        return typed.orElse(null);
    }
}
