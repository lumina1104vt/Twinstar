package com.lumina.twinstar.host;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.security.IActionHost;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * Implemented by the owner of a grid node that hosts the engine (Gravity Tech's twin-star hub).
 * The service picks hosts up in {@code addNode} by checking the node owner.
 * <p>
 * Not a public extension point yet: only Gravity Tech implements it in the first version, and its
 * shape may still change.
 */
public interface ITwinStarHost extends IActionHost {
    /**
     * White hole: operations per tick each job may run (internal runs and external pushes both count;
     * the runs of all patterns sent in a tick add up to at most this). Any size; null = unlimited.
     */
    @Nullable BigInteger getHostParallelism();

    /**
     * Black hole: items all running jobs may hold at once (materials, intermediates and finals).
     * Any size; null = unlimited.
     */
    @Nullable BigInteger getHostCapacity();

    /**
     * The hub's own energy storage, used before the AE2 network (not limited by AE2's doubles).
     * Null when there is none.
     */
    default @Nullable ITwinStarEnergySource getHostEnergySource() {
        return null;
    }

    /**
     * False while the host can't work (not formed, unpowered, ...). The grid then falls back to AE2.
     */
    boolean isHostActive();

    CpuSelectionMode getHostWorkerMode();

    /**
     * Jobs the engine leaves with this hub when its node leaves the grid (chunk unloaded, network
     * split): the block saves them and hands them back ({@link #takeParkedJobs}) when it joins a
     * grid again. Null clears them.
     */
    default void setParkedJobs(@Nullable CompoundTag jobs) {
    }

    /** The parked jobs, cleared; null when there are none. */
    default @Nullable CompoundTag takeParkedJobs() {
        return null;
    }

    /**
     * The jobs saved with this hub changed. The game only writes a chunk marked as changed, so
     * without this the save on disk stays as old as the last time something else changed there.
     */
    default void markJobsChanged() {
    }
}
