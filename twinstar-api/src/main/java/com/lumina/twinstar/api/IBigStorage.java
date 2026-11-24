package com.lumina.twinstar.api;

import appeng.api.stacks.AEKey;

import java.math.BigInteger;
import java.util.function.BiConsumer;

/**
 * Network storage that counts in BigInteger. AE2 still sees it through its long-based MEStorage
 * (amounts saturate there); the twin-star engine talks to it directly, so astronomical amounts go
 * in and out in one call. Implemented by the owner of a grid node; the engine finds it on the grid.
 */
public interface IBigStorage {
    BigInteger getAmount(AEKey key);

    /** @return what was (or, simulated, would be) taken in */
    BigInteger insertBig(AEKey key, BigInteger amount, boolean simulate);

    /** @return what was (or, simulated, would be) taken out */
    BigInteger extractBig(AEKey key, BigInteger amount, boolean simulate);

    void forEachStored(BiConsumer<AEKey, BigInteger> action);
}
