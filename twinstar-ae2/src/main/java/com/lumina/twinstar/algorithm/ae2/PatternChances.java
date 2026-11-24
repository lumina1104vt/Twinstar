package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.List;

/**
 * Data component on an encoded pattern: the outputs that only come with some probability. AE2's
 * pattern formats have no place for it, so {@code gen_patterns} puts it on the pattern item and
 * {@link PatternCompiler} reads it back. An output not listed always comes. Keyed by the output's
 * key: AE2 merges outputs of the same key, so their order is not kept.
 */
public record PatternChances(List<Entry> entries) {
    public record Entry(AEKey key, double chance) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                AEKey.CODEC.fieldOf("key").forGetter(Entry::key),
                Codec.DOUBLE.fieldOf("chance").forGetter(Entry::chance)).apply(i, Entry::new));
        static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC = StreamCodec.composite(
                AEKey.STREAM_CODEC, Entry::key, ByteBufCodecs.DOUBLE, Entry::chance, Entry::new);
    }

    public static final Codec<PatternChances> CODEC = Entry.CODEC.listOf()
            .xmap(PatternChances::new, PatternChances::entries);
    public static final StreamCodec<RegistryFriendlyByteBuf, PatternChances> STREAM_CODEC = Entry.STREAM_CODEC
            .apply(ByteBufCodecs.list()).map(PatternChances::new, PatternChances::entries);

    public PatternChances {
        entries = List.copyOf(entries);
    }

    /** The probability of {@code key} (1 when it always comes). */
    public double chanceOf(AEKey key) {
        for (Entry e : entries)
            if (e.key().equals(key))
                return e.chance();
        return 1;
    }
}
