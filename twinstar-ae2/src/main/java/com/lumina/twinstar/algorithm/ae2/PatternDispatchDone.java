package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.util.AEKeyCodec;
import com.mojang.serialization.Codec;

import java.util.List;

/**
 * Data on a hub pattern: the products that count as made the moment the run's materials are all in the machine ("done
 * on dispatch"). The job does not wait for them to come back; a multiblock whose product leaves by its own output hatch
 * needs this. Keyed by the product's key. A product that comes with a probability is never listed here (its own rules win).
 */
public record PatternDispatchDone(List<AEKey> keys) {
    public static final Codec<PatternDispatchDone> CODEC = AEKeyCodec.KEY.listOf()
            .xmap(PatternDispatchDone::new, PatternDispatchDone::keys);

    public PatternDispatchDone {
        keys = List.copyOf(keys);
    }

    public boolean has(AEKey key) {
        return keys.contains(key);
    }
}
