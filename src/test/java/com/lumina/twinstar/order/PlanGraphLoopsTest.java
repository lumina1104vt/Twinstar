package com.lumina.twinstar.order;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** The crafting graph's loops: items made, in the end, from themselves. */
class PlanGraphLoopsTest {

    private static List<List<Integer>> graph(int n, int[][] edges) {
        List<List<Integer>> g = new ArrayList<>();
        for (int i = 0; i < n; i++)
            g.add(new ArrayList<>());
        for (int[] e : edges)
            g.get(e[0]).add(e[1]);
        return g;
    }

    @Test
    void noLoopInATree() {
        // 0 ← 1, 2; 1 ← 3
        assertArrayEquals(new int[]{-1, -1, -1, -1}, PlanGraphBuilder.loops(graph(4, new int[][]{{0, 1}, {0, 2}, {1, 3}})));
    }

    @Test
    void twoItemsMadeFromEachOther() {
        // target 0 ← A(1) ⇄ B(2), B ← coal(3)
        int[] loops = PlanGraphBuilder.loops(graph(4, new int[][]{{0, 1}, {1, 2}, {2, 1}, {2, 3}}));
        assertEquals(-1, loops[0]);
        assertEquals(0, loops[1]);
        assertEquals(0, loops[2]);
        assertEquals(-1, loops[3]);
    }

    @Test
    void itemMadeFromItselfIsALoop() {
        // the water bucket: a bucket gives a bucket back
        assertArrayEquals(new int[]{-1, 0}, PlanGraphBuilder.loops(graph(2, new int[][]{{0, 1}, {1, 1}})));
    }

    @Test
    void separateLoopsAreNotMerged() {
        // 0 ← 1 ⇄ 2, 0 ← 3 ⇄ 4
        int[] loops = PlanGraphBuilder.loops(graph(5, new int[][]{{0, 1}, {1, 2}, {2, 1}, {0, 3}, {3, 4}, {4, 3}}));
        assertEquals(loops[1], loops[2]);
        assertEquals(loops[3], loops[4]);
        assertNotEquals(loops[1], loops[3]);
    }
}
