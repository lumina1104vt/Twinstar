package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
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

    public boolean hasMissing() {
        return items.stream().anyMatch(i -> !i.missing().isEmpty());
    }

    /**
     * Only why something is missing: the missing items, what is made from them, and so on up to the
     * target, with the lines between those. Everything the plan has enough of is left out. The target
     * stays item 0. Empty when nothing is missing.
     */
    public PlanGraph missingOnly() {
        boolean[] keep = new boolean[items.size()];
        Deque<Integer> todo = new ArrayDeque<>();
        for (int i = 0; i < keep.length; i++)
            if (!items.get(i).missing().isEmpty()) {
                keep[i] = true;
                todo.add(i);
            }
        if (todo.isEmpty())
            return EMPTY;
        List<List<Recipe>> usedBy = new ArrayList<>();
        for (int i = 0; i < keep.length; i++)
            usedBy.add(new ArrayList<>());
        for (Recipe r : recipes)
            for (Flow f : r.inputs())
                usedBy.get(f.item()).add(r);
        while (!todo.isEmpty())
            for (Recipe r : usedBy.get(todo.pop()))
                for (Flow f : r.outputs())
                    if (f.kind() == 0 && !keep[f.item()]) {
                        keep[f.item()] = true;
                        todo.add(f.item());
                    }
        keep[0] = true;

        int[] item = new int[keep.length], loop = new int[loops.size()];
        Arrays.fill(item, -1);
        Arrays.fill(loop, -1);
        int next = 0, nextLoop = 0;
        for (int i = 0; i < keep.length; i++)
            if (keep[i]) {
                item[i] = next++;
                int l = items.get(i).loop();
                if (l >= 0 && loop[l] < 0)
                    loop[l] = nextLoop++;
            }
        List<Item> keptItems = new ArrayList<>(next);
        for (int i = 0; i < keep.length; i++)
            if (keep[i]) {
                Item it = items.get(i);
                keptItems.add(new Item(it.key(), it.used(), it.made(), it.stock(), it.infinite(), it.missing(),
                        it.leftover(), it.loop() < 0 ? -1 : loop[it.loop()]));
            }
        List<Recipe> keptRecipes = new ArrayList<>();
        for (Recipe r : recipes) {
            List<Flow> in = kept(r.inputs(), item), out = kept(r.outputs(), item);
            // a step on the way: it takes something missing (or on the way) and makes something that is
            if (!in.isEmpty() && out.stream().anyMatch(f -> f.kind() == 0))
                keptRecipes.add(new Recipe(r.runs(), r.machine(), r.machineMissing(), r.loop() < 0 ? -1 : loop[r.loop()], in, out));
        }
        Loop[] keptLoops = new Loop[nextLoop];
        for (int l = 0; l < loop.length; l++)
            if (loop[l] >= 0)
                keptLoops[loop[l]] = new Loop(kept(loops.get(l).seeds(), item));
        return new PlanGraph(keptItems, keptRecipes, List.of(keptLoops), truncated);
    }

    private static List<Flow> kept(List<Flow> flows, int[] item) {
        List<Flow> out = new ArrayList<>(flows.size());
        for (Flow f : flows)
            if (item[f.item()] >= 0)
                out.add(new Flow(item[f.item()], f.amount(), f.kind()));
        return out;
    }

    public void write(FriendlyByteBuf buf) {
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

    public static PlanGraph read(FriendlyByteBuf buf) {
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

    private static void writeFlows(FriendlyByteBuf buf, List<Flow> flows) {
        buf.writeVarInt(flows.size());
        for (Flow f : flows) {
            buf.writeVarInt(f.item());
            buf.writeUtf(f.amount());
            buf.writeVarInt(f.kind());
        }
    }

    private static List<Flow> readFlows(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Flow> flows = new ArrayList<>(Math.min(n, 64));
        for (int i = 0; i < n; i++)
            flows.add(new Flow(buf.readVarInt(), buf.readUtf(), buf.readVarInt()));
        return flows;
    }
}
