package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.order.PlanGraph;
import com.lumina.twinstar.order.PlanGraphBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The crafting graphs' layout: layers, groups side by side, and a theory map's size. */
class GraphLayoutTest {
    private static final GraphLayout.Labels NO_TEXT = new GraphLayout.Labels() {
        @Override
        public String itemText(int item) {
            return "";
        }

        @Override
        public String recipeText(int recipe) {
            return "×1";
        }
    };

    /** recipes as {output, input, input…}; loops found as the screens find them */
    private static PlanGraph graph(int items, int[][] recipes) {
        List<List<Integer>> materials = new ArrayList<>();
        for (int i = 0; i < items; i++)
            materials.add(new ArrayList<>());
        for (int[] r : recipes)
            for (int k = 1; k < r.length; k++)
                materials.get(r[0]).add(r[k]);
        int[] loopOf = PlanGraphBuilder.loops(materials);
        int loops = 0;
        for (int l : loopOf)
            loops = Math.max(loops, l + 1);
        List<PlanGraph.Item> is = new ArrayList<>();
        for (int i = 0; i < items; i++)
            is.add(new PlanGraph.Item(null, "", "", "", false, "", "", loopOf[i]));
        List<PlanGraph.Recipe> rs = new ArrayList<>();
        for (int[] r : recipes) {
            List<PlanGraph.Flow> in = new ArrayList<>();
            for (int k = 1; k < r.length; k++)
                in.add(new PlanGraph.Flow(r[k], "1", 0));
            int loop = -1;
            for (int k = 1; k < r.length; k++)
                if (loopOf[r[0]] >= 0 && loopOf[r[0]] == loopOf[r[k]])
                    loop = loopOf[r[0]];
            rs.add(new PlanGraph.Recipe("1", null, false, loop, in, List.of(new PlanGraph.Flow(r[0], "1", 0))));
        }
        List<PlanGraph.Loop> ls = new ArrayList<>();
        for (int l = 0; l < loops; l++)
            ls.add(new PlanGraph.Loop(List.of()));
        return new PlanGraph(is, rs, ls, false);
    }

    private static GraphLayout layout(PlanGraph g, boolean oneTarget) {
        return new GraphLayout(g, Set.of(), oneTarget, NO_TEXT, s -> s.length() * 6);
    }

    private static boolean overlap(GraphLayout.Node a, GraphLayout.Node b) {
        return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    }

    @Test
    void materialsGoBelowWhatTheyMake() {
        // 0 ← 1 ← 2
        GraphLayout l = layout(graph(3, new int[][]{{0, 1}, {1, 2}}), true);
        GraphLayout.Node a = l.nodes.get(0), b = l.nodes.get(1), c = l.nodes.get(2);
        assertTrue(a.y < b.y && b.y < c.y);
    }

    @Test
    void separatePartsSitSideBySide() {
        // 0 ← 1 and, apart, 2 ← 3
        GraphLayout l = layout(graph(4, new int[][]{{0, 1}, {2, 3}}), false);
        for (GraphLayout.Node a : l.nodes)
            for (GraphLayout.Node b : l.nodes)
                if (a != b)
                    assertFalse(overlap(a, b), "boxes overlap");
        assertEquals(null, l.target);
    }

    @Test
    void aLoopIsOneBox() {
        // 0 ← 1 ⇄ 2
        GraphLayout l = layout(graph(3, new int[][]{{0, 1}, {1, 2}, {2, 1}}), true);
        long loops = l.nodes.stream().filter(n -> n.kind == GraphLayout.Kind.LOOP).count();
        assertEquals(1, loops);
    }

    @Test
    void aLargeMapLaysOutQuickly() {
        // 6000 items in 40 unrelated packs, each a random layered recipe web
        Random random = new Random(1);
        int items = 6000, packs = 40, per = items / packs;
        List<int[]> recipes = new ArrayList<>();
        for (int x = 0; x < items; x++) {
            int pack = x / per, inPack = x % per;
            if (inPack >= per - 30)
                continue;   // raws
            for (int r = 0; r < 1 + random.nextInt(2); r++) {
                int[] rec = new int[1 + 2 + random.nextInt(3)];
                rec[0] = x;
                for (int k = 1; k < rec.length; k++)
                    rec[k] = pack * per + inPack + 1 + random.nextInt(per - inPack - 1);
                recipes.add(rec);
            }
        }
        long t = System.nanoTime();
        GraphLayout l = layout(graph(items, recipes.toArray(int[][]::new)), false);
        long ms = (System.nanoTime() - t) / 1_000_000;
        assertTrue(ms < 5000, "layout took " + ms + " ms");
        assertTrue(l.nodes.size() > items);
    }

    @Test
    void targetInsideALoop() {
        // the certus plan: dust (target) ← purified ← seed ← dust + sand
        int dust = 0, pure = 1, seed = 2, sand = 3;
        PlanGraph g = graph(4, new int[][]{{dust, pure}, {seed, dust, sand}, {pure, seed}});
        for (Set<Integer> open : List.of(Set.<Integer>of(), Set.of(0))) {
            GraphLayout l = new GraphLayout(g, open, true, NO_TEXT, s -> s.length() * 6);
            assertTrue(l.target != null && l.target.kind == GraphLayout.Kind.LOOP);
            assertFalse(l.all().isEmpty());
        }
    }

    /** The certus network from the game, planned, built as the server builds it, laid out as the client does. */
    @Test
    void certusPlanFromPlannerToLayout() {
        int dust = 0, seed = 1, pure = 2, sand = 3;
        for (long amount : new long[]{1, 5000})
            for (boolean dustless : new boolean[]{true, false}) {
                com.lumina.twinstar.algorithm.TestGraphs g = new com.lumina.twinstar.algorithm.TestGraphs();
                g.pattern(b -> b.input(pure, 1).output(dust, 1));
                g.pattern(b -> b.input(dust, 8).input(sand, 32).output(seed, 32));
                g.pattern(b -> b.input(seed, 1).output(pure, 1));
                g.stock(sand, Integer.MAX_VALUE).infinite(sand);
                if (!dustless)
                    g.stock(pure, 1000).stock(seed, 100);
                var snap = g.flush();
                var plan = com.lumina.twinstar.algorithm.planning.Planner.plan(snap, g.stockSnapshot(), dust,
                        com.lumina.twinstar.algorithm.model.Amount.of(amount), new com.lumina.twinstar.algorithm.model.Overlay(),
                        com.lumina.twinstar.algorithm.model.Deadline.NONE);
                PlanGraph pg = com.lumina.twinstar.order.PlanGraphBuilder.build(plan, snap, new com.lumina.twinstar.algorithm.ae2.ItemIndex());
                for (Set<Integer> open : List.of(Set.<Integer>of(), Set.of(0))) {
                    GraphLayout l = new GraphLayout(pg, open, true, NO_TEXT, s -> s.length() * 6);
                    assertFalse(l.all().isEmpty(), "amount " + amount + " dustless " + dustless + " " + plan);
                }
            }
    }
}
