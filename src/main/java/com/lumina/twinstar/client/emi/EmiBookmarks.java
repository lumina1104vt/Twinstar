package com.lumina.twinstar.client.emi;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.runtime.EmiFavorites;
import dev.emi.emi.runtime.EmiReloadManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidType;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * EMI side of {@link com.lumina.twinstar.client.Bookmarks}: EMI's favourites. EMI has no public API
 * for adding one from code, so this calls its runtime class {@code EmiFavorites} directly; if a
 * version of EMI changes that, the call fails and {@code Bookmarks} turns EMI off. Only loaded when EMI is there.
 */
public final class EmiBookmarks {
    private EmiBookmarks() {
    }

    public static boolean ready() {
        return EmiReloadManager.isLoaded();
    }

    /** The EMI stack of a key (items and fluids; other keys have no EMI form). */
    public static @Nullable EmiStack stack(AEKey key) {
        if (key instanceof AEItemKey item)
            return EmiStack.of(item.toStack());
        if (key instanceof AEFluidKey fluid) {
            FluidStack stack = fluid.toStack(FluidType.BUCKET_VOLUME);
            return EmiStack.of(stack.getFluid(), stack.getTag(), FluidType.BUCKET_VOLUME);
        }
        return null;
    }

    /** The keys that were new among the favourites. */
    public static Set<AEKey> add(Collection<AEKey> keys) {
        Set<AEKey> added = new LinkedHashSet<>();
        for (AEKey key : keys) {
            EmiStack stack = stack(key);
            if (stack == null)
                continue;
            boolean have = EmiFavorites.favorites.stream().anyMatch(f -> EmiIngredient.areEqual(f.getStack(), stack));
            if (!have) {
                EmiFavorites.addFavorite(stack);
                added.add(key);
            }
        }
        return added;
    }
}
