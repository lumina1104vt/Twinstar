package com.lumina.twinstar.port;

import com.lumina.bridge.key.Key;
import com.lumina.bridge.Pos;
import com.lumina.bridge.Face;
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
    boolean hasItemStorage(Pos pos);

    /** Puts in; returns how much went in. {@code faces}: the sides to try, null = no side. */
    long insert(Pos pos, List<@Nullable Face> faces, Key key, long amount);

    /** Takes out up to {@code amount} of an item; what is taken is also reported to {@code taken}. */
    long retrieve(Pos pos, Key key, long amount, BiConsumer<Key, Long> taken);

    /** Takes what the machine made: every output except {@code keep}; {@code wantOther} are also taken. */
    void extractOutputs(Pos pos, Set<Key> wantOther, Set<Key> keep, Set<Key> products, BiConsumer<Key, Long> taken);

    boolean holdsAny(Pos pos, Set<Key> keys);

    /** Forgets what was learnt about the machine at the position (its slot roles). */
    void forget(Pos pos);

    /** Text for the debug log. */
    String describe(Pos pos);

    String contentsAt(Pos pos);

    String describeInsert(Pos pos, List<@Nullable Face> faces, Key key, long amount);
}
