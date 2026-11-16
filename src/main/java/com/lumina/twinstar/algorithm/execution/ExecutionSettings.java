package com.lumina.twinstar.algorithm.execution;

/**
 * Numbers of the execution the mod around the engine sets (the engine has no config of its own): how often a GUI machine is looked
 * into and how long the tool waits. The defaults are what the engine does without any.
 */
public final class ExecutionSettings {
    private static volatile long guiPollTicks = 1, toolDelayTicks = 1;

    private ExecutionSettings() {
    }

    public static void set(long guiPollTicks, long toolDelayTicks) {
        ExecutionSettings.guiPollTicks = Math.max(1, guiPollTicks);
        ExecutionSettings.toolDelayTicks = Math.max(0, toolDelayTicks);
    }

    /** How often (ticks) a machine without item slots has its GUI opened to see whether a product is in it. */
    static long guiPollTicks() {
        return guiPollTicks;
    }

    /** How long (ticks) the machine is left alone between its last material and the tool right-click. */
    static long toolDelayTicks() {
        return toolDelayTicks;
    }
}
