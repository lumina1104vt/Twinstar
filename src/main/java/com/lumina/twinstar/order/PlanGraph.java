package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The plan as a graph for the plan screen's crafting graph: each item once, each pattern the plan
 * runs once, and the cycles of the plan (items made, in the end, from themselves) as loops the
 * screen shows folded. Item 0 is the target. Amounts are display text, as in {@link PlanView}.
 *
 * @param truncated more items or recipes than {@link PlanGraphBuilder#MAX_NODES}: the rest is left out
 */
public record PlanGraph(List<Item> items, List<Recipe> recipes, List<Loop> loops, boolean truncated) {

    public static final PlanGraph EMPTY = new PlanGraph(List.of(), List.of(), List.of(), false);

    /**
     * How much the plan uses of an item and where it comes from (empty text = none); {@code loop}:
     * the index of its loop, −1 if none.
     */
    public record Item(AEKey key, String used, String made, String stock, boolean infinite, String missing,
                       String leftover, int loop) {
    }

    /**
     * A pattern of the plan, run {@code runs} times. Flows are totals over all runs; an input's
     * {@code kind} is its {@link com.lumina.twinstar.algorithm.model.InputKind} ordinal, an
     * output's is 0 (main) or 1 (byproduct).
     *
     * @param machine        the hub machine it runs in, null for an ordinary provider pattern
     * @param machineMissing that machine has no interface on the grid
     */
    public record Recipe(String runs, @Nullable AEKey machine, boolean machineMissing, int loop, List<Flow> inputs,
                         List<Flow> outputs) {
    }

    public record Flow(int item, String amount, int kind) {
    }

    /** A cycle of the plan; its items and recipes name it by index. {@code seeds}: what it starts from. */
    public record Loop(List<Flow> seeds) {
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeVarInt(items.size());
        for (Item i : items) {
            AEKey.writeKey(buf, i.key());
            buf.writeUtf(i.used());
            buf.writeUtf(i.made());
            buf.writeUtf(i.stock());
            buf.writeBoolean(i.infinite());
            buf.writeUtf(i.missing());
            buf.writeUtf(i.leftover());
            buf.writeVarInt(i.loop() + 1);
        }
        buf.writeVarInt(recipes.size());
        for (Recipe r : recipes) {
            buf.writeUtf(r.runs());
            buf.writeBoolean(r.machine() != null);
            if (r.machine() != null)
                AEKey.writeKey(buf, r.machine());
            buf.writeBoolean(r.machineMissing());
            buf.writeVarInt(r.loop() + 1);
            writeFlows(buf, r.inputs());
            writeFlows(buf, r.outputs());
        }
        buf.writeVarInt(loops.size());
        for (Loop l : loops)
            writeFlows(buf, l.seeds());
        buf.writeBoolean(truncated);
    }

    public static PlanGraph read(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Item> items = new ArrayList<>(Math.min(n, PlanGraphBuilder.MAX_NODES));
        for (int i = 0; i < n; i++)
            items.add(new Item(AEKey.readKey(buf), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(),
                    buf.readUtf(), buf.readUtf(), buf.readVarInt() - 1));
        int m = buf.readVarInt();
        List<Recipe> recipes = new ArrayList<>(Math.min(m, PlanGraphBuilder.MAX_NODES));
        for (int i = 0; i < m; i++)
            recipes.add(new Recipe(buf.readUtf(), buf.readBoolean() ? AEKey.readKey(buf) : null, buf.readBoolean(),
                    buf.readVarInt() - 1, readFlows(buf), readFlows(buf)));
        int k = buf.readVarInt();
        List<Loop> loops = new ArrayList<>(Math.min(k, PlanGraphBuilder.MAX_NODES));
        for (int i = 0; i < k; i++)
            loops.add(new Loop(readFlows(buf)));
        return new PlanGraph(items, recipes, loops, buf.readBoolean());
    }

    private static void writeFlows(RegistryFriendlyByteBuf buf, List<Flow> flows) {
        buf.writeVarInt(flows.size());
        for (Flow f : flows) {
            buf.writeVarInt(f.item());
            buf.writeUtf(f.amount());
            buf.writeVarInt(f.kind());
        }
    }

    private static List<Flow> readFlows(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Flow> flows = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++)
            flows.add(new Flow(buf.readVarInt(), buf.readUtf(), buf.readVarInt()));
        return flows;
    }
}
