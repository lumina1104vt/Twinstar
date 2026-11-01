package com.lumina.twinstar.algorithm.model;

public enum InputKind {
    /** Used up by every run (most inputs). */
    CONSUME,
    /** Must be present but is not used up; a holding requirement (max over all uses, not a sum). */
    CATALYST,
    /** Durability tool: one tool lasts {@code durability} runs. */
    TOOL,
    /** Given back after the run, so one copy is enough (reused, returned when the job ends). */
    CONTAINER
}
