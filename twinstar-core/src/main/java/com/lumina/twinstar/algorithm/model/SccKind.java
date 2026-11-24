package com.lumina.twinstar.algorithm.model;

/** A policy cycle (the best patterns themselves form a loop). */
public enum SccKind {
    /** Some member comes out in larger amounts than the loop puts in (seeds multiply). */
    GROWTH,
    /** Members are turned into each other; external inputs drive it. */
    ENTANGLED
}
