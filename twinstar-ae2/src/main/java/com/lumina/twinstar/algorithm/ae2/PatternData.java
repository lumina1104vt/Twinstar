package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEItemKey;
import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * One piece of extra data on an encoded pattern (1.20.1 has no data components: it is NBT on the stack, in the compound
 * {@value #ROOT}, written with a codec). The pattern's AEItemKey carries it too, so it is part of what makes two patterns the same.
 */
public final class PatternData<T> {
    public static final String ROOT = "twinstar";

    private final String id;
    private final Codec<T> codec;

    public PatternData(String id, Codec<T> codec) {
        this.id = id;
        this.codec = codec;
    }

    public @Nullable T get(ItemStack stack) {
        return read(stack.getTag());
    }

    public @Nullable T get(AEItemKey key) {
        return read(key.getTag());
    }

    private @Nullable T read(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND))
            return null;
        Tag value = tag.getCompound(ROOT).get(id);
        return value == null ? null : codec.parse(NbtOps.INSTANCE, value).result().orElse(null);
    }

    public void set(ItemStack stack, T value) {
        codec.encodeStart(NbtOps.INSTANCE, value).result().ifPresent(t -> stack.getOrCreateTagElement(ROOT).put(id, t));
    }

    public void remove(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ROOT, Tag.TAG_COMPOUND))
            return;
        CompoundTag root = tag.getCompound(ROOT);
        root.remove(id);
        if (root.isEmpty())
            tag.remove(ROOT);
        if (tag.isEmpty())
            stack.setTag(null);
    }
}
