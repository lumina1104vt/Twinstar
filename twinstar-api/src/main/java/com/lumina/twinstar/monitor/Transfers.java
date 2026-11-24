package com.lumina.twinstar.monitor;

import com.lumina.bridge.Place;
import com.lumina.bridge.key.Key;
import org.jetbrains.annotations.Nullable;

/**
 * The monitor's entry for whatever moves material between blocks. A block that sends something out (an ME export bus,
 * a pattern provider, a pipe of another mod) calls {@link #record} with what it sent and how much the other side took;
 * the machine usage screen lists it under the sender. Nothing is counted by itself: a mod that does not call it shows
 * nothing, so every sender we know about is hooked here by us (see the mixins of the core) or by the addon's own code.
 * <p>
 * Call it from the server thread, after the move happened, with the amount that really went in (not the amount tried).
 * It does nothing when no monitor is listening (a client, a server without the core).
 */
public final class Transfers {
    /** a pattern provider pushed the materials of a run */
    public static final int PROVIDER = 4;
    /** an ME export bus moved something out of the network */
    public static final int EXPORT_BUS = 8;
    /** an ME import bus moved something into the network */
    public static final int IMPORT_BUS = 16;
    /** something was taken out of an ME interface */
    public static final int INTERFACE = 32;
    /** any other sender (an addon's own block) */
    public static final int OTHER = 64;

    /** Whoever keeps the books. */
    public interface Sink {
        /**
         * @param from   the block that sent it
         * @param to     the block that took it, when the sender knows (null otherwise)
         * @param kind   one of the constants above
         * @param amount what went in, in the key's own unit (items, millibuckets...)
         */
        void record(Place from, @Nullable Place to, Key what, long amount, int kind);
    }

    private static volatile @Nullable Sink sink;

    private Transfers() {
    }

    /** The monitor of the core sets itself here at start-up. */
    public static void setSink(@Nullable Sink s) {
        sink = s;
    }

    /** {@code amount} of {@code what} went from {@code from} to {@code to} (null when not known). */
    public static void record(Place from, @Nullable Place to, Key what, long amount, int kind) {
        Sink s = sink;
        if (s != null && amount > 0)
            s.record(from, to, what, amount, kind);
    }

    /** Shorter, for a sender of another mod: kind {@link #OTHER}, destination not given. */
    public static void record(Place from, Key what, long amount) {
        record(from, null, what, amount, OTHER);
    }
}
