package com.lumina.twinstar.algorithm.execution;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.port.World;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * Transitional: {@link World}'s storage and actions with AE2 keys in and out, for code that has not moved to
 * {@link Key} yet. Goes away with the last AE2 key in the execution code.
 */
final class AeIO {
    private AeIO() {
    }

    private static Set<Key> set(Set<AEKey> keys) {
        Set<Key> out = new HashSet<>();
        for (AEKey k : keys)
            out.add(Keys.of(k));
        return out;
    }

    private static BiConsumer<Key, Long> sink(BiConsumer<AEKey, Long> to) {
        return (k, n) -> to.accept(Keys.toAe(k), n);
    }

    static long insert(World w, BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
        return w.storage().insert(pos, faces, Keys.of(key), amount);
    }

    static long retrieve(World w, BlockPos pos, AEItemKey key, long amount, BiConsumer<AEKey, Long> taken) {
        return w.storage().retrieve(pos, Keys.of(key), amount, sink(taken));
    }

    static void extractOutputs(World w, BlockPos pos, Set<AEKey> wantOther, Set<AEKey> keep, BiConsumer<AEKey, Long> taken) {
        w.storage().extractOutputs(pos, set(wantOther), set(keep), sink(taken));
    }

    static boolean storageHoldsAny(World w, BlockPos pos, Set<AEKey> keys) {
        return w.storage().holdsAny(pos, set(keys));
    }

    static String describeInsert(World w, BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
        return w.storage().describeInsert(pos, faces, Keys.of(key), amount);
    }

    static long rightClick(World w, BlockPos pos, Direction face, AEItemKey key, long amount, BiConsumer<AEKey, Long> stray) {
        return w.actions().rightClick(pos, face, Keys.of(key), amount, sink(stray));
    }

    static @Nullable AEItemKey useTool(World w, BlockPos pos, Direction face, AEItemKey tool, BiConsumer<AEKey, Long> stray) {
        Key back = w.actions().useTool(pos, face, Keys.of(tool), sink(stray));
        return back == null ? null : back.handle(AEItemKey.class);
    }

    static void emptyClick(World w, BlockPos pos, Direction face, BiConsumer<AEKey, Long> stray) {
        w.actions().emptyClick(pos, face, sink(stray));
    }

    static long insertViaGui(World w, BlockPos pos, Direction face, AEItemKey key, long amount, int onlySlot,
                             BiConsumer<AEKey, Long> stray) {
        return w.actions().insertViaGui(pos, face, Keys.of(key), amount, onlySlot, sink(stray));
    }

    static long extractViaGui(World w, BlockPos pos, Direction face, Map<AEKey, Integer> products,
                              BiConsumer<AEKey, Long> taken, BiConsumer<AEKey, Long> stray) {
        Map<Key, Integer> in = new HashMap<>();
        products.forEach((k, v) -> in.put(Keys.of(k), v));
        return w.actions().extractViaGui(pos, face, in, sink(taken), sink(stray));
    }

    static long place(World w, BlockPos cell, AEItemKey key) {
        return w.actions().place(cell, Keys.of(key));
    }

    static long drop(World w, BlockPos cell, AEItemKey key, long amount) {
        return w.actions().drop(cell, Keys.of(key), amount);
    }

    static boolean actionsHoldsAny(World w, BlockPos cell, Set<AEKey> keys) {
        return w.actions().holdsAny(cell, set(keys));
    }

    static void pickUp(World w, BlockPos cell, Predicate<AEKey> wanted, BiConsumer<AEKey, Long> taken) {
        w.actions().pickUp(cell, k -> wanted.test(Keys.toAe(k)), sink(taken));
    }
}
