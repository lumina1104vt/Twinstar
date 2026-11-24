package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.data.Data;
import com.lumina.bridge.key.Key;
import org.jetbrains.annotations.Nullable;

/**
 * What {@link JobStore} cannot do without the version: write a key (or the pattern a plan came from) as data and
 * read it back. The platform side fills it in.
 */
public interface SaveCodec {
    Data writeKey(Key key);

    /** The key a save holds; null when it cannot be made again (the mod that added it is gone). */
    @Nullable Key readKey(Data data);

    /**
     * The pattern behind a plan as data (the encoded pattern itself), or null when {@code source} says nothing
     * the platform can save.
     */
    @Nullable Data writeSource(@Nullable Object source);

    /**
     * The pattern source a save holds, made again: the live pattern when it can be decoded now, else the bare
     * encoded pattern (it waits until someone offers it again), else null.
     */
    @Nullable Object readSource(Data data);
}
