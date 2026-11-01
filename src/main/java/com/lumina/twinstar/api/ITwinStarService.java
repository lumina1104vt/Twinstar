package com.lumina.twinstar.api;

import appeng.api.networking.IGridService;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * Public, read-only view of the twin-star engine on one grid. Get it with
 * {@code grid.getService(ITwinStarService.class)}.
 * <p>
 * Only stable queries go here. Everything the engine itself needs (lifecycle, mixin entry points)
 * lives on the implementation class.
 */
public interface ITwinStarService extends IGridService {
    /**
     * Whether this grid has an active twin-star hub. When false the engine stays out of the way and
     * AE2 plans and runs crafting jobs as usual.
     */
    boolean hasHub();

    /**
     * Parallel operations per tick each job gets (white hole). Null = unlimited; 0 without a hub.
     */
    @Nullable BigInteger getParallelism();

    /**
     * Item capacity shared by all jobs (black hole). Null = unlimited; 0 without a hub.
     */
    @Nullable BigInteger getCapacity();
}
