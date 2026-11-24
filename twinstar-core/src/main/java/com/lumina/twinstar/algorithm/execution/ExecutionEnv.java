package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.port.PatternSites;
import com.lumina.twinstar.port.Power;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.api.IMachine;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Function;

/**
 * What one executor tick needs from the network (main thread). Machines are AE2's own
 * {@link ICraftingProvider}s; there is no extra machine abstraction.
 *
 * @param store           AE2 storage plus the grid's big storages
 * @param providers       who takes a pattern's runs
 * @param hubEnergy       the hub's own energy storage (used first), or null
 * @param machines        the machine access interfaces on the grid
 * @param parallelism     white hole: runs per tick each job may send in total, any size; null = unlimited
 * @param tickBudgetNanos compute budget for the whole hub tick
 */
public record ExecutionEnv(KeyIndex index, Stock store, Power energy,
                           @Nullable ITwinStarEnergySource hubEnergy,
                           PatternSites providers, List<? extends IMachine> machines,
                           @Nullable Amount parallelism, long nowNanos, long tickBudgetNanos) {
}
