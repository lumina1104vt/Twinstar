package com.lumina.twinstar.algorithm.ae2;

import com.lumina.twinstar.util.AEKeyCodec;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * Data component on a hub pattern: the materials and products whose NBT (data components) does not
 * matter. Such a material may be any stack of the same item (an enchanted one, one with a name);
 * such a product counts as the pattern's when the machine gives it with other data. Durability is not
 * NBT here: a worn tool is still told apart.
 */
public record PatternIgnoreNbt(List<AEKey> inputs, List<AEKey> outputs) {
    public static final Codec<PatternIgnoreNbt> CODEC = RecordCodecBuilder.create(i -> i.group(
            AEKeyCodec.KEY.listOf().optionalFieldOf("inputs", List.of()).forGetter(PatternIgnoreNbt::inputs),
            AEKeyCodec.KEY.listOf().optionalFieldOf("outputs", List.of()).forGetter(PatternIgnoreNbt::outputs)).apply(i, PatternIgnoreNbt::new));

    public PatternIgnoreNbt {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
    }

    /*
     * By item, not by key: some mods' data (Draconic Evolution's tools) is not equal to itself once saved
     * and read back, so the key in this list and the same key in the pattern's own lists differ.
     */
    public boolean ignoresInput(AEKey key) {
        return inputs.stream().anyMatch(k -> sameIgnoringNbt(k, key));
    }

    public boolean ignoresOutput(AEKey key) {
        return outputs.stream().anyMatch(k -> sameIgnoringNbt(k, key));
    }

    /** {@code a} and {@code b} are the same item with the same durability, whatever else is on them. */
    public static boolean sameIgnoringNbt(AEKey a, AEKey b) {
        if (a.equals(b))
            return true;
        if (!(a instanceof AEItemKey x) || !(b instanceof AEItemKey y) || x.getItem() != y.getItem())
            return false;
        return x.toStack().getDamageValue() == y.toStack().getDamageValue();
    }
}
