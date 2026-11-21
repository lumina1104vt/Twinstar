package com.lumina.twinstar.tools;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import net.minecraft.core.HolderLookup;

/**
 * How keys are named in the dump files. Keys compare like the engine's {@code ItemIndex} (item +
 * data components), so the name keeps the components: an item without any is its plain id, anything
 * else (components, fluids, other key types) is AE2's full tag of the key as SNBT. The recipe dump and
 * the network dump name keys the same way.
 */
final class KeyNames {
    private KeyNames() {
    }

    static String of(AEKey key, HolderLookup.Provider registries) {
        if (key instanceof AEItemKey item && !item.hasTag())
            return item.getId().toString();
        return key.toTagGeneric().toString();
    }
}
