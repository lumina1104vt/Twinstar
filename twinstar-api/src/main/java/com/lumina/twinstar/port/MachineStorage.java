package com.lumina.twinstar.port;

import com.lumina.bridge.key.Key;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * What the engine does to a machine's own containers: put in, take out, look inside. The engine only knows this
 * interface; the version's adapter fills it in (capabilities differ between Minecraft versions). One instance
 * is for one world. Server thread only.
 */
public interface MachineStorage {
    /** Whether the position has any item container (what a machine with only a GUI does not have). */
    boolean hasItemStorage(BlockPos pos);

    /** Puts in; returns how much went in. {@code faces}: the sides to try, null = no side. */
    long insert(BlockPos pos, List<@Nullable Direction> faces, Key key, long amount);

    /** Takes out up to {@code amount} of an item; what is taken is also reported to {@code taken}. */
    long retrieve(BlockPos pos, Key key, long amount, BiConsumer<Key, Long> taken);

    /** Takes what the machine made: every output except {@code keep}; {@code wantOther} are also taken. */
    void extractOutputs(BlockPos pos, Set<Key> wantOther, Set<Key> keep, BiConsumer<Key, Long> taken);

    boolean holdsAny(BlockPos pos, Set<Key> keys);

    /** Forgets what was learnt about the machine at the position (its slot roles). */
    void forget(BlockPos pos);

    /** Text for the debug log. */
    String describe(BlockPos pos);

    String contentsAt(BlockPos pos);

    String describeInsert(BlockPos pos, List<@Nullable Direction> faces, Key key, long amount);
}
