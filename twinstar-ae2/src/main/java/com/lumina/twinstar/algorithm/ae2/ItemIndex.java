package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.algorithm.execution.KeyIndex;
import org.jetbrains.annotations.Nullable;

/** The engine's {@link KeyIndex} with the AE2 side: AE2 keys wrapped on the way in, unwrapped on the way out. */
public final class ItemIndex extends KeyIndex {
    public int idOf(AEKey key) {
        return idOf(Keys.of(key));
    }

    public int find(AEKey key) {
        return find(Keys.of(key));
    }

    public @Nullable AEKey keyOf(int id) {
        Key k = key(id);
        return k == null ? null : Keys.toAe(k);
    }
}
