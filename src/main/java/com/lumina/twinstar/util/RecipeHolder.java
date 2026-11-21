package com.lumina.twinstar.util;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

/** A recipe with its id, as 1.21 hands recipes around; 1.20.1's recipes carry their own id, so this only pairs them up. */
public record RecipeHolder<T extends Recipe<?>>(ResourceLocation id, T value) {
    public static <T extends Recipe<?>> RecipeHolder<T> of(T recipe) {
        return new RecipeHolder<>(recipe.getId(), recipe);
    }

    /** Every recipe of a collection, with its id. */
    public static java.util.List<RecipeHolder<?>> all(java.util.Collection<? extends Recipe<?>> recipes) {
        java.util.List<RecipeHolder<?>> out = new java.util.ArrayList<>(recipes.size());
        for (Recipe<?> r : recipes)
            out.add(of(r));
        return out;
    }
}
