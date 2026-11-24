package com.lumina.twinstar.platform;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.bridge.data.Data;
import com.lumina.bridge.data.Nbt;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.algorithm.execution.SaveCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** The NeoForge 1.21.1 + AE2 side of {@link SaveCodec}: AE2's own key tags and pattern decoding. */
public final class Ae2SaveCodec implements SaveCodec {
    private final HolderLookup.Provider regs;
    private final Level level;

    public Ae2SaveCodec(HolderLookup.Provider regs, Level level) {
        this.regs = regs;
        this.level = level;
    }

    @Override
    public Data writeKey(Key key) {
        return Nbt.fromNbt(Keys.toAe(key).toTagGeneric(regs));
    }

    @Override
    public @Nullable Key readKey(Data data) {
        AEKey key = readAe(data);
        return key == null ? null : Keys.of(key);
    }

    @Override
    public @Nullable Data writeSource(@Nullable Object source) {
        AEItemKey definition = source instanceof IPatternDetails details ? details.getDefinition()
                : source instanceof AEItemKey undecoded ? undecoded : null;
        return definition == null ? null : Nbt.fromNbt(definition.toTagGeneric(regs));
    }

    @Override
    public @Nullable Object readSource(Data data) {
        if (!(readAe(data) instanceof AEItemKey item))
            return null;
        IPatternDetails details = null;
        try {
            details = PatternDetailsHelper.decodePattern(item, level);
        } catch (RuntimeException e) {
            // undecodable now: kept as the key, so the save keeps it and it waits
        }
        return details != null ? details : item;
    }

    private @Nullable AEKey readAe(Data data) {
        try {
            return AEKey.fromTagGeneric(regs, Nbt.toNbt(data));
        } catch (RuntimeException e) {
            return null;        // its mod is gone
        }
    }
}
