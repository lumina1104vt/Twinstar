package com.lumina.twinstar.theory;

import appeng.api.stacks.AEKey;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole theory layer for the theory viewer's map: every item the engine knows and every
 * pattern, with the engine's best route of each item. Sent in {@link Part}s (a large pack has tens
 * of thousands of patterns: more than one packet holds).
 */
public record TheoryMap(long version, List<Item> items, List<Pattern> patterns) {
    public static final int RAW = 1, MINED = 2, INFINITE = 4, CANNOT = 8;

    /**
     * @param logCost log10 of the cost of one (raw units); +∞ = cannot be made
     * @param best    index in {@link #patterns()} of the engine's route for it, −1 if none
     * @param loop    the engine's policy loop it is in, −1 if none
     */
    public record Item(AEKey key, int level, double logCost, int flags, int best, int loop) {
        public boolean is(int flag) {
            return (flags & flag) != 0;
        }
    }

    /** A pattern; amounts per run; item numbers index {@link #items()}. */
    public record Pattern(@Nullable AEKey machine, List<In> inputs, List<Out> outputs) {
    }

    /** {@code kind}: {@link com.lumina.twinstar.algorithm.model.InputKind} ordinal. */
    public record In(int item, String amount, int kind) {
    }

    /** {@code logCost}: one of the item through this pattern (+∞ for a byproduct or when it cannot run). */
    public record Out(int item, String amount, boolean byproduct, double logCost) {
    }

    /** One packet's share: a slice of the items, then of the patterns. */
    public record Part(long version, int index, int count, int itemTotal, int patternTotal, int itemStart, List<Item> items,
                       int patternStart, List<Pattern> patterns) {

        public void write(FriendlyByteBuf buf) {
            buf.writeLong(version);
            buf.writeVarInt(index);
            buf.writeVarInt(count);
            buf.writeVarInt(itemTotal);
            buf.writeVarInt(patternTotal);
            buf.writeVarInt(itemStart);
            buf.writeVarInt(items.size());
            for (Item i : items) {
                AEKey.writeKey(buf, i.key());
                buf.writeVarInt(i.level());
                buf.writeDouble(i.logCost());
                buf.writeByte(i.flags());
                buf.writeVarInt(i.best() + 1);
                buf.writeVarInt(i.loop() + 1);
            }
            buf.writeVarInt(patternStart);
            buf.writeVarInt(patterns.size());
            for (Pattern p : patterns) {
                buf.writeBoolean(p.machine() != null);
                if (p.machine() != null)
                    AEKey.writeKey(buf, p.machine());
                buf.writeVarInt(p.inputs().size());
                for (In in : p.inputs()) {
                    buf.writeVarInt(in.item());
                    buf.writeUtf(in.amount());
                    buf.writeVarInt(in.kind());
                }
                buf.writeVarInt(p.outputs().size());
                for (Out out : p.outputs()) {
                    buf.writeVarInt(out.item());
                    buf.writeUtf(out.amount());
                    buf.writeBoolean(out.byproduct());
                    buf.writeDouble(out.logCost());
                }
            }
        }

        public static Part read(FriendlyByteBuf buf) {
            long version = buf.readLong();
            int index = buf.readVarInt(), count = buf.readVarInt();
            int itemTotal = buf.readVarInt(), patternTotal = buf.readVarInt();
            int itemStart = buf.readVarInt();
            int n = buf.readVarInt();
            List<Item> items = new ArrayList<>(Math.min(n, TheoryQueries.ITEMS_PER_PART));
            for (int i = 0; i < n; i++)
                items.add(new Item(AEKey.readKey(buf), buf.readVarInt(), buf.readDouble(), buf.readByte(),
                        buf.readVarInt() - 1, buf.readVarInt() - 1));
            int patternStart = buf.readVarInt();
            int m = buf.readVarInt();
            List<Pattern> patterns = new ArrayList<>(Math.min(m, TheoryQueries.PATTERNS_PER_PART));
            for (int i = 0; i < m; i++) {
                AEKey machine = buf.readBoolean() ? AEKey.readKey(buf) : null;
                int a = buf.readVarInt();
                List<In> ins = new ArrayList<>(Math.min(a, 64));
                for (int j = 0; j < a; j++)
                    ins.add(new In(buf.readVarInt(), buf.readUtf(), buf.readVarInt()));
                int b = buf.readVarInt();
                List<Out> outs = new ArrayList<>(Math.min(b, 64));
                for (int j = 0; j < b; j++)
                    outs.add(new Out(buf.readVarInt(), buf.readUtf(), buf.readBoolean(), buf.readDouble()));
                patterns.add(new Pattern(machine, ins, outs));
            }
            return new Part(version, index, count, itemTotal, patternTotal, itemStart, items, patternStart, patterns);
        }
    }
}
