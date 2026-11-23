package com.lumina.twinstar.usage;

import appeng.api.stacks.AEKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the server tells the usage screen: for every machine of the network its interface, its name and what it used up in each
 * window (1, 5 and 30 minutes), and how long each window really covers (a rate is the amount over that time).
 *
 * @param allowed false when the player may not look at this network (nothing else is set then)
 * @param spans   ticks each window covers, as {@link UsageLedger#spanTicks}
 * @param dropped machines the ledger had no room to count
 * @param memory  bytes the ledger holds (all networks together)
 */
public record UsageSnapshot(boolean allowed, long[] spans, long dropped, long memory, List<Machine> machines) {
    /** the windows, in ticks: 1, 5 and 30 minutes */
    public static final int[] WINDOW_TICKS = {1200, 6000, 36000};
    public static final int WINDOWS = WINDOW_TICKS.length;
    public static final int MAX_MACHINES = 600;
    public static final int MAX_ROWS = 40;

    public static final UsageSnapshot DENIED = new UsageSnapshot(false, new long[WINDOWS], 0, 0, List.of());
    public static final UsageSnapshot NONE = new UsageSnapshot(true, new long[]{1200, 6000, 36000}, 0, 0, List.of());

    /** One kind of material and what was used of it in each window. */
    public record Row(AEKey key, long[] totals) {
    }

    /**
     * One machine as one interface stands for it. {@code kinds} is a bit set ({@link UsageStore#HUB}, {@link UsageStore#STOCK});
     * {@code other} is what the materials that did not fit the list came to.
     */
    public record Machine(String dim, long pos, String ifaceDim, long ifacePos, int side, Component name, @Nullable Component ifaceName,
                          boolean loaded, int kinds, List<Row> rows, long[] other) {
        /** the same interface (for grouping) */
        public String ifaceId() {
            return ifaceDim + "|" + ifacePos + "|" + side;
        }

        /** the same machine as one interface stands for it */
        public String id() {
            return dim + "|" + pos + "|" + ifaceId();
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeBoolean(allowed);
        if (!allowed)
            return;
        for (int w = 0; w < WINDOWS; w++)
            buf.writeVarLong(spans[w]);
        buf.writeVarLong(dropped);
        buf.writeVarLong(memory);
        buf.writeVarInt(machines.size());
        for (Machine m : machines) {
            buf.writeUtf(m.dim);
            buf.writeLong(m.pos);
            buf.writeUtf(m.ifaceDim);
            buf.writeLong(m.ifacePos);
            buf.writeVarInt(m.side + 1);
            ComponentSerialization.STREAM_CODEC.encode(buf, m.name);
            buf.writeBoolean(m.ifaceName != null);
            if (m.ifaceName != null)
                ComponentSerialization.STREAM_CODEC.encode(buf, m.ifaceName);
            buf.writeBoolean(m.loaded);
            buf.writeVarInt(m.kinds);
            for (int w = 0; w < WINDOWS; w++)
                buf.writeVarLong(m.other[w]);
            buf.writeVarInt(m.rows.size());
            for (Row r : m.rows) {
                AEKey.writeKey(buf, r.key);
                for (int w = 0; w < WINDOWS; w++)
                    buf.writeVarLong(r.totals[w]);
            }
        }
    }

    public static UsageSnapshot read(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean())
            return DENIED;
        long[] spans = new long[WINDOWS];
        for (int w = 0; w < WINDOWS; w++)
            spans[w] = buf.readVarLong();
        long dropped = buf.readVarLong(), memory = buf.readVarLong();
        int n = buf.readVarInt();
        List<Machine> machines = new ArrayList<>(Math.min(n, MAX_MACHINES));
        for (int i = 0; i < n; i++) {
            String dim = buf.readUtf();
            long pos = buf.readLong();
            String ifaceDim = buf.readUtf();
            long ifacePos = buf.readLong();
            int side = buf.readVarInt() - 1;
            Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
            Component ifaceName = buf.readBoolean() ? ComponentSerialization.STREAM_CODEC.decode(buf) : null;
            boolean loaded = buf.readBoolean();
            int kinds = buf.readVarInt();
            long[] other = new long[WINDOWS];
            for (int w = 0; w < WINDOWS; w++)
                other[w] = buf.readVarLong();
            int rows = buf.readVarInt();
            List<Row> list = new ArrayList<>(Math.min(rows, MAX_ROWS));
            for (int r = 0; r < rows; r++) {
                AEKey key = AEKey.readKey(buf);
                long[] totals = new long[WINDOWS];
                for (int w = 0; w < WINDOWS; w++)
                    totals[w] = buf.readVarLong();
                if (key != null)
                    list.add(new Row(key, totals));
            }
            machines.add(new Machine(dim, pos, ifaceDim, ifacePos, side, name, ifaceName, loaded, kinds, list, other));
        }
        return new UsageSnapshot(true, spans, dropped, memory, machines);
    }
}
