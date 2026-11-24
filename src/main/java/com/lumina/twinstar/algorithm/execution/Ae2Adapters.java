package com.lumina.twinstar.algorithm.execution;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.port.MachineStorage;
import com.lumina.twinstar.port.World;
import com.lumina.twinstar.port.WorldActions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The NeoForge 1.21.1 + AE2 side of {@link MachineStorage} and {@link WorldActions}: {@link MachineIO} and
 * {@link WorldIO} for one world. The engine gets them only through the interfaces.
 */
public final class Ae2Adapters {
    private Ae2Adapters() {
    }

    /** The world of a level (null for none). */
    public static @Nullable World world(@Nullable ServerLevel level) {
        return level == null ? null : new Ae2World(level);
    }

    static MachineStorage storage(ServerLevel level) {
        return new Storage(level);
    }

    static WorldActions actions(ServerLevel level) {
        return new Actions(level);
    }

    private record Storage(ServerLevel level) implements MachineStorage {
        public boolean hasItemStorage(BlockPos pos) {
            return !MachineIO.itemHandlers(level, pos).isEmpty();
        }

        public long insert(BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
            return MachineIO.insert(level, pos, faces, key, amount);
        }

        public long retrieve(BlockPos pos, AEItemKey key, long amount, BiConsumer<AEKey, Long> taken) {
            return MachineIO.retrieve(level, pos, key, amount, taken);
        }

        public void extractOutputs(BlockPos pos, Set<AEKey> wantOther, Set<AEKey> keep, BiConsumer<AEKey, Long> taken) {
            MachineIO.extractOutputs(level, pos, wantOther, keep, taken);
        }

        public boolean holdsAny(BlockPos pos, Set<AEKey> keys) {
            return MachineIO.holdsAny(level, pos, keys);
        }

        public void forget(BlockPos pos) {
            MachineIO.forget(level, pos);
        }

        public String describe(BlockPos pos) {
            return MachineIO.describe(level, pos);
        }

        public String contentsAt(BlockPos pos) {
            return MachineIO.contentsAt(level, pos);
        }

        public String describeInsert(BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
            return MachineIO.describeInsert(level, pos, faces, key, amount);
        }
    }

    private record Actions(ServerLevel level) implements WorldActions {
        public long rightClick(BlockPos pos, Direction face, AEItemKey key, long amount, BiConsumer<AEKey, Long> stray) {
            return WorldIO.rightClick(level, pos, face, key, amount, stray);
        }

        public @Nullable AEItemKey useTool(BlockPos pos, Direction face, AEItemKey tool, BiConsumer<AEKey, Long> stray) {
            return WorldIO.useTool(level, pos, face, tool, stray);
        }

        public void emptyClick(BlockPos pos, Direction face, BiConsumer<AEKey, Long> stray) {
            WorldIO.emptyClick(level, pos, face, stray);
        }

        public long insertViaGui(BlockPos pos, Direction face, AEItemKey key, long amount, int onlySlot,
                                 BiConsumer<AEKey, Long> stray) {
            return WorldIO.insertViaGui(level, pos, face, key, amount, onlySlot, stray);
        }

        public long extractViaGui(BlockPos pos, Direction face, Map<AEKey, Integer> products,
                                  BiConsumer<AEKey, Long> taken, BiConsumer<AEKey, Long> stray) {
            return WorldIO.extractViaGui(level, pos, face, products, taken, stray);
        }

        public BlockPos front(BlockPos pos, Direction interfaceFace, int distance) {
            return WorldIO.front(level, pos, interfaceFace, distance);
        }

        public long place(BlockPos cell, AEItemKey key) {
            return WorldIO.place(level, cell, key);
        }

        public long drop(BlockPos cell, AEItemKey key, long amount) {
            return WorldIO.drop(level, cell, key, amount);
        }

        public boolean holdsAny(BlockPos cell, Set<AEKey> keys) {
            return WorldIO.holdsAny(level, cell, keys);
        }

        public void pickUp(BlockPos cell, Predicate<AEKey> wanted, BiConsumer<AEKey, Long> taken) {
            WorldIO.pickUp(level, cell, wanted, taken);
        }

        public String lastClick() {
            return WorldIO.lastClick;
        }
    }
}
