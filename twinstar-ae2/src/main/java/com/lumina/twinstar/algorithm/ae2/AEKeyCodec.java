package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import net.minecraft.nbt.CompoundTag;

/** AE2 15 (1.20.1) has no codec for its keys: they go through the NBT AE2 writes them as. */
public final class AEKeyCodec {
    public static final Codec<AEKey> KEY = CompoundTag.CODEC.flatXmap(
            tag -> {
                AEKey key = AEKey.fromTagGeneric(tag);
                return key == null ? DataResult.error(() -> "not an AE key: " + tag) : DataResult.success(key);
            },
            key -> DataResult.success(key.toTagGeneric()));

    private AEKeyCodec() {
    }
}
