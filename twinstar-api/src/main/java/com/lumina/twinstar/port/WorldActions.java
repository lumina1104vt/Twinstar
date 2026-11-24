package com.lumina.twinstar.port;

import com.lumina.bridge.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * What the engine does in the world the way a player would: right click, use a tool, work a machine's GUI,
 * place a block, drop an item. The version's adapter does it with a fake player. {@code stray} gets what a
 * click gave back that was not asked for. One instance is for one world. Server thread only.
 */
public interface WorldActions {
    long rightClick(BlockPos pos, Direction face, Key key, long amount, BiConsumer<Key, Long> stray);

    /** Uses the tool on the machine; returns the tool as it is after (null if it is gone). */
    @Nullable Key useTool(BlockPos pos, Direction face, Key tool, BiConsumer<Key, Long> stray);

    void emptyClick(BlockPos pos, Direction face, BiConsumer<Key, Long> stray);

    long insertViaGui(BlockPos pos, Direction face, Key key, long amount, int onlySlot,
                      BiConsumer<Key, Long> stray);

    long extractViaGui(BlockPos pos, Direction face, Map<Key, Integer> products, BiConsumer<Key, Long> taken,
                       BiConsumer<Key, Long> stray);

    /** The position {@code distance} blocks in front of the interface's face. */
    BlockPos front(BlockPos pos, Direction interfaceFace, int distance);

    long place(BlockPos cell, Key key);

    long drop(BlockPos cell, Key key, long amount);

    boolean holdsAny(BlockPos cell, Set<Key> keys);

    /** An item that is put down as a block (placed in front of the machine), not just dropped. */
    boolean isBlockItem(Key key);

    void pickUp(BlockPos cell, Predicate<Key> wanted, BiConsumer<Key, Long> taken);

    /** What the last click did, for the debug log. */
    String lastClick();
}
