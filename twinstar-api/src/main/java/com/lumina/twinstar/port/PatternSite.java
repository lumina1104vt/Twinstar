package com.lumina.twinstar.port;

import com.lumina.bridge.key.Key;

import java.util.List;
import java.util.Map;

/**
 * One place that takes a pattern's runs (on 1.21.1 an AE2 crafting provider). Equal sites are the same place.
 */
public interface PatternSite {
    /** Higher goes first. */
    int priority();

    boolean isBusy();

    /** The hub's own, not a machine on the network. */
    boolean isHub();

    /**
     * Sends one run. {@code inputs}: slot by slot, what goes in (an empty slot sends nothing, a null one is skipped).
     * False when it is refused.
     */
    boolean push(Object pattern, List<Map<Key, Long>> inputs);
}
