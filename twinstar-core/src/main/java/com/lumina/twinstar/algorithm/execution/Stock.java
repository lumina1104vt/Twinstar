package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.key.Key;
import com.lumina.twinstar.algorithm.model.Amount;

/** The network's storage as the engine uses it: put in, take out, ask whether a stack is an endless source. */
public interface Stock {
    /** Puts up to {@code amount} in; returns what went in. */
    Amount insert(Key key, Amount amount);

    /** Takes up to {@code amount} out; returns what came out. */
    Amount extract(Key key, Amount amount);

    /** The network hands out as much of it as is asked for (a creative or infinity cell). */
    boolean isInfinite(Key key);
}
