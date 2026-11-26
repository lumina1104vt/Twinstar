package com.lumina.twinstar.api;

import org.jetbrains.annotations.Nullable;

/**
 * Tells the engine how to count another mod's storage exactly. Registered with {@link StorageAdapters}; the engine
 * itself knows no such mod.
 */
public interface StorageAdapter {
    /**
     * @param storage a cell in a drive or ME chest, or the owner of a grid node
     * @return the engine's view of it, or null when it is not this adapter's
     */
    @Nullable IBigStorage adapt(Object storage);
}
