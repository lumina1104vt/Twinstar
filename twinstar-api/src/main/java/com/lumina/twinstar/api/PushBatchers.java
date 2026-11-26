package com.lumina.twinstar.api;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every {@link PushBatcher} there is; mods add theirs once at start-up. */
public final class PushBatchers {
    private static final List<PushBatcher> BATCHERS = new CopyOnWriteArrayList<>();

    private PushBatchers() {
    }

    /** Adds a batcher (the same instance twice is kept once). */
    public static void register(PushBatcher batcher) {
        if (!BATCHERS.contains(batcher))
            BATCHERS.add(batcher);
    }

    /** The runs one push may carry: the most any batcher allows, at least 1 and at most {@code wanted}. */
    public static long maxRuns(ICraftingProvider provider, IPatternDetails pattern, long wanted) {
        long most = 1;
        for (PushBatcher batcher : BATCHERS) {
            try {
                most = Math.max(most, batcher.maxRuns(provider, pattern, wanted));
            } catch (RuntimeException | LinkageError e) {
                // a batcher the other mod has outgrown: one run at a time there
            }
        }
        return Math.min(most, Math.max(1, wanted));
    }
}
