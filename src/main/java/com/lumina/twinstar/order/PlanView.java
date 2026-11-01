package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What the plan screen shows, built on the server from a twin-star plan and sent once per plan.
 * Amounts are display text (a number that fits a long as plain digits, the client groups them;
 * anything larger in unit form): exact BigIntegers for a few hundred rows of 1e10000 orders would
 * be megabytes.
 *
 * @param fits      the plan's usage fits the black hole's free capacity when it was made (the server
 *                  compares: the client only has text)
 * @param rows      the crafting tree, depth first; an item already listed appears again as {@code shared}
 * @param truncated more rows than {@link PlanTree#MAX_ROWS}
 * @param graph     the same plan as a graph, for the crafting graph screen
 */
public record PlanView(AEKey target, String amount, int patterns, long planNanos, String usage, String freeCapacity,
                       String parallel, boolean complete, boolean fits, List<Row> rows, boolean truncated,
                       List<Missing> missing, PlanGraph graph) {

    /** {@code freeCapacity} / {@code parallel} for "no limit". */
    public static final String UNLIMITED = "";

    /**
     * One item in the tree: how much of it the plan uses, and where that comes from (any of: crafted
     * in {@code runs} runs, taken from stock, an infinite source, missing). Empty text = none.
     */
    public record Row(int depth, AEKey key, String amount, String runs, String stock, boolean infinite, String missing,
                      boolean shared, @Nullable AEKey missingMachine) {
    }

    /** {@code machine}: a machine with no interface on the grid ({@code key} is the machine, no amount). */
    public record Missing(AEKey key, String amount, boolean machine) {
    }

    public void write(RegistryFriendlyByteBuf buf) {
        AEKey.writeKey(buf, target);
        buf.writeUtf(amount);
        buf.writeVarInt(patterns);
        buf.writeVarLong(planNanos);
        buf.writeUtf(usage);
        buf.writeUtf(freeCapacity);
        buf.writeUtf(parallel);
        buf.writeBoolean(complete);
        buf.writeBoolean(fits);
        buf.writeVarInt(rows.size());
        for (Row r : rows) {
            buf.writeVarInt(r.depth());
            AEKey.writeKey(buf, r.key());
            buf.writeUtf(r.amount());
            buf.writeUtf(r.runs());
            buf.writeUtf(r.stock());
            buf.writeBoolean(r.infinite());
            buf.writeUtf(r.missing());
            buf.writeBoolean(r.shared());
            buf.writeBoolean(r.missingMachine() != null);
            if (r.missingMachine() != null)
                AEKey.writeKey(buf, r.missingMachine());
        }
        buf.writeBoolean(truncated);
        buf.writeVarInt(missing.size());
        for (Missing m : missing) {
            AEKey.writeKey(buf, m.key());
            buf.writeUtf(m.amount());
            buf.writeBoolean(m.machine());
        }
        graph.write(buf);
    }

    public static PlanView read(RegistryFriendlyByteBuf buf) {
        AEKey target = AEKey.readKey(buf);
        String amount = buf.readUtf();
        int patterns = buf.readVarInt();
        long nanos = buf.readVarLong();
        String usage = buf.readUtf();
        String free = buf.readUtf();
        String parallel = buf.readUtf();
        boolean complete = buf.readBoolean();
        boolean fits = buf.readBoolean();
        int n = buf.readVarInt();
        List<Row> rows = new ArrayList<>(Math.min(n, PlanTree.MAX_ROWS));
        for (int i = 0; i < n; i++)
            rows.add(new Row(buf.readVarInt(), AEKey.readKey(buf), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                    buf.readBoolean(), buf.readUtf(), buf.readBoolean(), buf.readBoolean() ? AEKey.readKey(buf) : null));
        boolean truncated = buf.readBoolean();
        int m = buf.readVarInt();
        List<Missing> missing = new ArrayList<>(Math.min(m, PlanTree.MAX_ROWS));
        for (int i = 0; i < m; i++)
            missing.add(new Missing(AEKey.readKey(buf), buf.readUtf(), buf.readBoolean()));
        return new PlanView(target, amount, patterns, nanos, usage, free, parallel, complete, fits, rows, truncated, missing,
                PlanGraph.read(buf));
    }
}
