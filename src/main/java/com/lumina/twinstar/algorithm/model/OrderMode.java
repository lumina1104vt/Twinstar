package com.lumina.twinstar.algorithm.model;

public enum OrderMode {
    /** Plan exactly the amount asked for; shortages are reported. */
    EXACT,
    /** Make as many as possible (binary search on the amount). */
    BEST_EFFORT
}
