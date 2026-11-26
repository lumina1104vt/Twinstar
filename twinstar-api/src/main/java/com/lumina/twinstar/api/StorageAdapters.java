package com.lumina.twinstar.api;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every {@link StorageAdapter} there is; mods add theirs once at start-up. */
public final class StorageAdapters {
    private static final List<StorageAdapter> ADAPTERS = new CopyOnWriteArrayList<>();

    private StorageAdapters() {
    }

    /** Adds an adapter (the same instance twice is kept once). */
    public static void register(StorageAdapter adapter) {
        if (!ADAPTERS.contains(adapter))
            ADAPTERS.add(adapter);
    }

    /** {@code storage} as the engine counts it: itself, or what the first adapter that knows it says; else null. */
    public static @Nullable IBigStorage of(@Nullable Object storage) {
        if (storage instanceof IBigStorage big)
            return big;
        if (storage == null)
            return null;
        for (StorageAdapter adapter : ADAPTERS) {
            try {
                IBigStorage big = adapter.adapt(storage);
                if (big != null)
                    return big;
            } catch (RuntimeException | LinkageError e) {
                // an adapter the other mod has outgrown leaves its storage to AE2
            }
        }
        return null;
    }
}
