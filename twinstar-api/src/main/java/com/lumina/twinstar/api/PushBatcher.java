package com.lumina.twinstar.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

/**
 * Says which crafting providers take several runs of a pattern in one push: the pattern itself, with the inputs of
 * all those runs. Registered with {@link PushBatchers}; without one the engine pushes one run at a time.
 */
public interface PushBatcher {
    /** How many runs one push to {@code provider} may carry, of {@code wanted}; 1 or less: no batching. */
    long maxRuns(ICraftingProvider provider, IPatternDetails pattern, long wanted);
}
