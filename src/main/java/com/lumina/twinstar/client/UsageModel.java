package com.lumina.twinstar.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import com.lumina.twinstar.usage.UsageSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.List;

/** {@link UsageOrder} over a snapshot's machines, and the texts the usage screen writes. */
public final class UsageModel {
    private UsageModel() {
    }

    public static final UsageOrder.Source<UsageSnapshot.Machine, UsageSnapshot.Row> SOURCE = new UsageOrder.Source<>() {
        @Override
        public String id(UsageSnapshot.Machine m) {
            return m.id();
        }

        @Override
        public String name(UsageSnapshot.Machine m) {
            return m.name().getString();
        }

        @Override
        public String ifaceId(UsageSnapshot.Machine m) {
            return m.ifaceId();
        }

        @Override
        public boolean sender(UsageSnapshot.Machine m) {
            return m.side() == com.lumina.twinstar.usage.UsageStore.TRANSFER_SIDE;
        }

        @Override
        public boolean importer(UsageSnapshot.Machine m) {
            return isImporter(m);
        }

        @Override
        public String ifaceName(UsageSnapshot.Machine m) {
            return ifaceTitle(m).getString();
        }

        @Override
        public String place(UsageSnapshot.Machine m) {
            return pos(m.pos());
        }

        @Override
        public List<UsageSnapshot.Row> rows(UsageSnapshot.Machine m) {
            return m.rows();
        }

        @Override
        public long[] totals(UsageSnapshot.Row r) {
            return r.totals();
        }

        @Override
        public String itemName(UsageSnapshot.Row r) {
            return AEKeyRendering.getDisplayName(r.key()).getString();
        }

        @Override
        public double unit(UsageSnapshot.Row r) {
            return r.key().getType().getAmountPerUnit();
        }
    };

    public static UsageOrder<UsageSnapshot.Machine, UsageSnapshot.Row> build(UsageSnapshot s, int window, boolean byInterface,
                                                                             UsageOrder.Sort sort, boolean ascending, String search) {
        return build(s, window, byInterface, sort, ascending, search, false);
    }

    /** @param hideIdle leave out the machines that used nothing in the window */
    public static UsageOrder<UsageSnapshot.Machine, UsageSnapshot.Row> build(UsageSnapshot s, int window, boolean byInterface,
                                                                             UsageOrder.Sort sort, boolean ascending, String search,
                                                                             boolean hideIdle) {
        List<UsageSnapshot.Machine> machines = s.machines();
        if (hideIdle)
            machines = machines.stream().filter(m -> !isIdle(m, window)).toList();
        return UsageOrder.build(machines, SOURCE, window, byInterface, sort, ascending, search);
    }

    /** Nothing used (or sent) in the window. */
    public static boolean isIdle(UsageSnapshot.Machine m, int window) {
        for (UsageSnapshot.Row r : m.rows())
            if (r.totals()[window] > 0)
                return false;
        return true;
    }

    public static int idleCount(UsageSnapshot s, int window) {
        int n = 0;
        for (UsageSnapshot.Machine m : s.machines())
            if (isIdle(m, window))
                n++;
        return n;
    }

    /** A block that did nothing but bring material into the network. */
    public static boolean isImporter(UsageSnapshot.Machine m) {
        int sends = com.lumina.twinstar.monitor.Transfers.PROVIDER | com.lumina.twinstar.monitor.Transfers.EXPORT_BUS
                | com.lumina.twinstar.monitor.Transfers.INTERFACE | com.lumina.twinstar.monitor.Transfers.OTHER;
        return m.side() == com.lumina.twinstar.usage.UsageStore.TRANSFER_SIDE
                && (m.kinds() & com.lumina.twinstar.monitor.Transfers.IMPORT_BUS) != 0 && (m.kinds() & sends) == 0;
    }

    /** The interface own name, or what it is called without one. */
    public static Component ifaceTitle(UsageSnapshot.Machine m) {
        return m.ifaceName() != null ? m.ifaceName() : Component.translatable("gui.twinstar.usage.interface_default");
    }

    public static String pos(long packed) {
        BlockPos p = BlockPos.of(packed);
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }

    /** "384/min", the amount in the key own short form. */
    public static String rateText(AEKey key, long total, long spanTicks) {
        return Component.translatable("gui.twinstar.usage.per_min", key.formatAmount(UsageOrder.perMinute(total, spanTicks), AmountFormat.SLOT))
                .getString();
    }
}
