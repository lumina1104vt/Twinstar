package com.lumina.twinstar.algorithm.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.function.IntFunction;

/** Iterative Tarjan (deep crafting chains must not overflow the call stack). */
public final class Tarjan {
    private Tarjan() {
    }

    /**
     * Strongly connected components of the subgraph on {@code nodes}; successors outside
     * {@code nodes} are ignored. Returned sources first (every edge goes from an earlier or the same
     * component to a later one).
     */
    public static List<int[]> components(int[] nodes, IntFunction<int[]> successors) {
        int n = nodes.length;
        HashMap<Integer, Integer> local = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++)
            local.put(nodes[i], i);

        int[][] succ = new int[n][];
        int[] index = new int[n];
        int[] low = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        int[] stack = new int[n];
        int sp = 0;
        int[] callNode = new int[n];
        int[] callIter = new int[n];
        int counter = 0;
        List<int[]> out = new ArrayList<>();

        for (int s = 0; s < n; s++) {
            if (index[s] != -1)
                continue;
            int top = 0;
            callNode[0] = s;
            callIter[0] = 0;
            index[s] = low[s] = counter++;
            stack[sp++] = s;
            onStack[s] = true;
            while (top >= 0) {
                int v = callNode[top];
                if (succ[v] == null)
                    succ[v] = toLocal(successors.apply(nodes[v]), local);
                if (callIter[top] < succ[v].length) {
                    int w = succ[v][callIter[top]++];
                    if (index[w] == -1) {
                        index[w] = low[w] = counter++;
                        stack[sp++] = w;
                        onStack[w] = true;
                        top++;
                        callNode[top] = w;
                        callIter[top] = 0;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                    continue;
                }
                if (low[v] == index[v]) {
                    int start = sp;
                    do {
                        start--;
                    } while (stack[start] != v);
                    int[] comp = new int[sp - start];
                    for (int i = start; i < sp; i++) {
                        comp[i - start] = nodes[stack[i]];
                        onStack[stack[i]] = false;
                    }
                    Arrays.sort(comp);
                    sp = start;
                    out.add(comp);
                }
                top--;
                if (top >= 0) {
                    int u = callNode[top];
                    low[u] = Math.min(low[u], low[v]);
                }
            }
        }
        // Tarjan emits sinks first
        java.util.Collections.reverse(out);
        return out;
    }

    private static int[] toLocal(int[] global, HashMap<Integer, Integer> local) {
        int[] tmp = new int[global.length];
        int k = 0;
        for (int g : global) {
            Integer l = local.get(g);
            if (l != null)
                tmp[k++] = l;
        }
        return k == tmp.length ? tmp : Arrays.copyOf(tmp, k);
    }
}
