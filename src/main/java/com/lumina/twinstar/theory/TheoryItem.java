package com.lumina.twinstar.theory;

import appeng.api.stacks.AEKey;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the theory layer says about one item, for the theory viewer: how deep it is, what it costs,
 * the routes that make it in the engine's order (the first usable one is what a plan takes when
 * stock and marks don't change it), the raw materials one of it comes to, and its loop.
 *
 * @param known   the engine has the item at all (false: nothing else is set)
 * @param level   0 = raw; one more than its deepest material otherwise
 * @param logCost log10 of the cost of one, in raw-material units; +∞ = cannot be made
 * @param mined   only made by loops without an outside input (raw iron ⇄ raw iron block): priced by stock
 * @param loop    its policy loop, null when it is in none
 */
public record TheoryItem(AEKey key, boolean known, int level, double logCost, boolean raw, boolean mined,
                         boolean infinite, List<Route> routes, int moreRoutes, List<Raw> bom, @Nullable Loop loop) {

    /** Routes sent at most; raw materials listed at most. */
    public static final int MAX_ROUTES = 24, MAX_BOM = 16, MAX_LIST = 64;

    /**
     * A pattern making the item; amounts per run.
     *
     * @param pattern the engine's pattern id (one pattern seen from several items is one recipe)
     * @param best    the engine's choice for the item
     * @param logCost log10 of the cost of one item through this route; +∞ when it cannot be made
     */
    public record Route(int pattern, boolean best, double logCost, @Nullable AEKey machine, List<Stack> inputs,
                        List<Stack> outputs) {
    }

    /** {@code kind}: inputs {@link com.lumina.twinstar.algorithm.model.InputKind} ordinal, outputs 0 main / 1 byproduct. */
    public record Stack(AEKey key, String amount, int kind) {
    }

    /** One raw material: log10 of how much one of the item needs (an estimate, as the engine's). */
    public record Raw(AEKey key, double log) {
    }

    /** A loop of the engine's best routes; {@code seeds} start one round. */
    public record Loop(int index, List<AEKey> members, List<Stack> seeds) {
    }

    public static TheoryItem unknown(AEKey key) {
        return new TheoryItem(key, false, 0, Double.POSITIVE_INFINITY, true, false, false, List.of(), 0, List.of(), null);
    }

    public void write(FriendlyByteBuf buf) {
        AEKey.writeKey(buf, key);
        buf.writeBoolean(known);
        buf.writeVarInt(level);
        buf.writeDouble(logCost);
        buf.writeBoolean(raw);
        buf.writeBoolean(mined);
        buf.writeBoolean(infinite);
        buf.writeVarInt(routes.size());
        for (Route r : routes) {
            buf.writeVarInt(r.pattern());
            buf.writeBoolean(r.best());
            buf.writeDouble(r.logCost());
            writeNullableKey(buf, r.machine());
            writeStacks(buf, r.inputs());
            writeStacks(buf, r.outputs());
        }
        buf.writeVarInt(moreRoutes);
        buf.writeVarInt(bom.size());
        for (Raw raw : bom) {
            AEKey.writeKey(buf, raw.key());
            buf.writeDouble(raw.log());
        }
        buf.writeBoolean(loop != null);
        if (loop != null) {
            buf.writeVarInt(loop.index());
            buf.writeVarInt(loop.members().size());
            for (AEKey k : loop.members())
                AEKey.writeKey(buf, k);
            writeStacks(buf, loop.seeds());
        }
    }

    public static TheoryItem read(FriendlyByteBuf buf) {
        AEKey key = AEKey.readKey(buf);
        boolean known = buf.readBoolean();
        int level = buf.readVarInt();
        double logCost = buf.readDouble();
        boolean raw = buf.readBoolean(), mined = buf.readBoolean(), infinite = buf.readBoolean();
        int n = Math.min(buf.readVarInt(), MAX_ROUTES);
        List<Route> routes = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            routes.add(new Route(buf.readVarInt(), buf.readBoolean(), buf.readDouble(), readNullableKey(buf),
                    readStacks(buf), readStacks(buf)));
        int more = buf.readVarInt();
        int b = Math.min(buf.readVarInt(), MAX_BOM);
        List<Raw> bom = new ArrayList<>(b);
        for (int i = 0; i < b; i++)
            bom.add(new Raw(AEKey.readKey(buf), buf.readDouble()));
        Loop loop = null;
        if (buf.readBoolean()) {
            int index = buf.readVarInt();
            int m = Math.min(buf.readVarInt(), MAX_LIST);
            List<AEKey> members = new ArrayList<>(m);
            for (int i = 0; i < m; i++)
                members.add(AEKey.readKey(buf));
            loop = new Loop(index, members, readStacks(buf));
        }
        return new TheoryItem(key, known, level, logCost, raw, mined, infinite, routes, more, bom, loop);
    }

    private static void writeNullableKey(FriendlyByteBuf buf, @Nullable AEKey key) {
        buf.writeBoolean(key != null);
        if (key != null)
            AEKey.writeKey(buf, key);
    }

    private static @Nullable AEKey readNullableKey(FriendlyByteBuf buf) {
        return buf.readBoolean() ? AEKey.readKey(buf) : null;
    }

    static void writeStacks(FriendlyByteBuf buf, List<Stack> stacks) {
        buf.writeVarInt(stacks.size());
        for (Stack s : stacks) {
            AEKey.writeKey(buf, s.key());
            buf.writeUtf(s.amount());
            buf.writeVarInt(s.kind());
        }
    }

    static List<Stack> readStacks(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_LIST);
        List<Stack> stacks = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            stacks.add(new Stack(AEKey.readKey(buf), buf.readUtf(), buf.readVarInt()));
        return stacks;
    }
}
