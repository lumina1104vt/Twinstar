package com.lumina.twinstar.algorithm.execution;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.ae2.NetworkStore;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.api.IMachineAccess;
import com.lumina.twinstar.host.ITwinStarEnergySource;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * What one executor tick needs from the network (main thread). Machines are AE2's own
 * {@link ICraftingProvider}s; there is no extra machine abstraction.
 *
 * @param store           AE2 storage plus the grid's big storages
 * @param providers       AE2's {@code CraftingService.getProviders(pattern)}
 * @param hubEnergy       the hub's own energy storage (used first), or null
 * @param machines        the machine access interfaces on the grid
 * @param parallelism     white hole: runs per tick each job may send in total, any size; null = unlimited
 * @param tickBudgetNanos compute budget for the whole hub tick
 */
public record ExecutionEnv(ItemIndex index, NetworkStore store, IEnergySource energy,
                           @Nullable ITwinStarEnergySource hubEnergy,
                           Function<IPatternDetails, Iterable<ICraftingProvider>> providers, List<IMachineAccess> machines,
                           @Nullable Amount parallelism, long nowNanos, long tickBudgetNanos) {
    public IActionSource source() {
        return store.source();
    }
}
