package com.lumina.twinstar.algorithm.model;

/** Where a pattern runs. The ordinal is the rank in the route key: hub-internal first. */
public enum PatternLocation {
    HUB_INTERNAL,       // finished instantly by the black hole
    HUB_MACHINE,        // a hub pattern for a machine: sent through a machine access interface
    EXTERNAL_CRAFT,     // AE2 crafting pattern, sent to a molecular assembler
    EXTERNAL_PROCESS    // AE2 processing pattern, sent to a machine
}
