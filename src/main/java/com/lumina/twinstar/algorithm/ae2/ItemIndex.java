package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Key ↔ item id. Ids are stable within a network: new keys get new ids, nothing is ever renumbered. Keys compare
 * exactly (item + data components), which is what AE2 does too.
 * <p>
 * The index holds neutral {@link Key}s. The {@code AEKey} overloads are the AE2 side, for the code that still
 * speaks AE2 keys; they only wrap and unwrap.
 */
public final class ItemIndex {
    private final HashMap<Key, Integer> ids = new HashMap<>();
    private final ArrayList<Key> keys = new ArrayList<>();

    /** The id of {@code key}, allocating a new one the first time. */
    public synchronized int idOf(Key key) {
        Integer id = ids.get(key);
        if (id != null)
            return id;
        int n = keys.size();
        ids.put(key, n);
        keys.add(key);
        return n;
    }

    /** −1 when the key has never been seen. */
    public synchronized int find(Key key) {
        Integer id = ids.get(key);
        return id == null ? -1 : id;
    }

    public synchronized @Nullable Key key(int id) {
        return id >= 0 && id < keys.size() ? keys.get(id) : null;
    }

    public synchronized int size() {
        return keys.size();
    }

    // ---- AE2 side ----

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
