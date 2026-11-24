package com.lumina.twinstar.port;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
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
    long rightClick(BlockPos pos, Direction face, AEItemKey key, long amount, BiConsumer<AEKey, Long> stray);

    /** Uses the tool on the machine; returns the tool as it is after (null if it is gone). */
    @Nullable AEItemKey useTool(BlockPos pos, Direction face, AEItemKey tool, BiConsumer<AEKey, Long> stray);

    void emptyClick(BlockPos pos, Direction face, BiConsumer<AEKey, Long> stray);

    long insertViaGui(BlockPos pos, Direction face, AEItemKey key, long amount, int onlySlot,
                      BiConsumer<AEKey, Long> stray);

    long extractViaGui(BlockPos pos, Direction face, Map<AEKey, Integer> products, BiConsumer<AEKey, Long> taken,
                       BiConsumer<AEKey, Long> stray);

    /** The position {@code distance} blocks in front of the interface's face. */
    BlockPos front(BlockPos pos, Direction interfaceFace, int distance);

    long place(BlockPos cell, AEItemKey key);

    long drop(BlockPos cell, AEItemKey key, long amount);

    boolean holdsAny(BlockPos cell, Set<AEKey> keys);

    void pickUp(BlockPos cell, Predicate<AEKey> wanted, BiConsumer<AEKey, Long> taken);

    /** What the last click did, for the debug log. */
    String lastClick();
}
