package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.order.PlanGraph;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Where a crafting graph's boxes go (the plan's, the theory viewer's): layers from the target down (an item's materials one layer
 * below it), ordered so lines cross little, each loop as one box (folded) or a frame with its items
 * and recipes on a ring (unfolded). World units; the screen pans and zooms them.
 */
final class GraphLayout {
    enum Kind { ITEM, RECIPE, LOOP }

    /** The texts in the boxes (their widths size the boxes). */
    interface Labels {
        String itemText(int item);

        String recipeText(int recipe);
    }

    static final int ITEM_H = 22, RECIPE_H = 14, LOOP_H = 24, FRAME_TITLE_H = 13;
    private static final int GAP_X = 14, GAP_Y = 34, LOOP_ICONS = 4, FRAME_PAD = 10, GROUP_GAP = 60;

    static final class Node {
        final Kind kind;
        /** item / recipe / loop index in the graph */
        final int ref;
        int x, y, w, h;
        /** an unfolded loop: its items and recipes, placed inside this frame */
        final List<Node> inner = new ArrayList<>();
        boolean unfolded;
        int layer = -1;
        double order;
        /** boxes joined to it (between boxes of the layers); with {@link #top}, how much it stands out when far away */
        int degree;
        /** nothing above it: a product at the top of its part */
        boolean top;

        Node(Kind kind, int ref, int w, int h) {
            this.kind = kind;
            this.ref = ref;
            this.w = w;
            this.h = h;
        }

        float cx() {
            return x + w / 2f;
        }

        float cy() {
            return y + h / 2f;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /**
     * A line from a material to what uses it, or from a recipe to what it makes.
     *
     * @param amount the flow's text, "" when several flows were merged into one line
     * @param kind   input kind ordinal (from an item) or output kind (from a recipe)
     * @param inner  both ends inside the same unfolded loop
     * @param recipe the recipe and item it joins (graph indices, also when drawn to a folded loop)
     */
    record Edge(Node from, Node to, String amount, int kind, boolean output, boolean inner, int recipe, int item) {
    }

    final PlanGraph graph;
    /** the boxes the layers are made of (items, recipes, loops); an unfolded loop's insides are in its {@link Node#inner} */
    final List<Node> nodes = new ArrayList<>();
    final List<Edge> edges = new ArrayList<>();
    /** the box everything leads to; null when the graph has many tops (the theory map) */
    final @Nullable Node target;
    private final Node[] itemNode, recipeNode;
    private final Node[] loopNode;

    /**
     * @param unfolded  loops shown open
     * @param oneTarget everything leads to item 0 (a plan); otherwise each box without products is a top,
     *                  and parts not joined to each other are laid out side by side
     * @param textWidth width of a text in the font
     */
    GraphLayout(PlanGraph graph, Set<Integer> unfolded, boolean oneTarget, Labels labels, ToIntFunction<String> textWidth) {
        this.graph = graph;
        itemNode = new Node[graph.items().size()];
        recipeNode = new Node[graph.recipes().size()];
        loopNode = new Node[graph.loops().size()];

        // boxes
        for (int l = 0; l < loopNode.length; l++) {
            Node n = new Node(Kind.LOOP, l, 0, LOOP_H);
            n.unfolded = unfolded.contains(l);
            loopNode[l] = n;
        }
        for (int i = 0; i < itemNode.length; i++) {
            PlanGraph.Item it = graph.items().get(i);
            String text = labels.itemText(i);
            Node n = new Node(Kind.ITEM, i, text.isEmpty() ? 22 : 22 + textWidth.applyAsInt(text) + 5, ITEM_H);
            itemNode[i] = n;
            if (it.loop() >= 0)
                loopNode[it.loop()].inner.add(n);
        }
        for (int r = 0; r < recipeNode.length; r++) {
            PlanGraph.Recipe rc = graph.recipes().get(r);
            Node n = new Node(Kind.RECIPE, r, (rc.machine() != null ? 13 : 4) + textWidth.applyAsInt(labels.recipeText(r)) + 4, RECIPE_H);
            recipeNode[r] = n;
            if (rc.loop() >= 0)
                loopNode[rc.loop()].inner.add(n);
        }
        for (Node l : loopNode) {
            if (l.unfolded)
                ring(l);
            else {
                int items = (int) l.inner.stream().filter(n -> n.kind == Kind.ITEM).count();
                int more = items - LOOP_ICONS;
                l.w = 14 + Math.min(items, LOOP_ICONS) * 17 + (more > 0 ? textWidth.applyAsInt("+" + more) + 3 : 0) + 3;
            }
        }
        for (Node n : itemNode)
            if (graph.items().get(n.ref).loop() < 0)
                nodes.add(n);
        for (Node n : recipeNode)
            if (graph.recipes().get(n.ref).loop() < 0)
                nodes.add(n);
        nodes.addAll(List.of(loopNode));
        target = oneTarget && itemNode.length > 0 ? outer(itemNode[0]) : null;

        // lines: to the inside of an unfolded loop, to the loop's box when folded; merged when they fall together
        Map<List<Node>, Edge> merged = new LinkedHashMap<>();
        for (int r = 0; r < recipeNode.length; r++) {
            PlanGraph.Recipe rc = graph.recipes().get(r);
            for (PlanGraph.Flow f : rc.inputs())
                addEdge(merged, shown(itemNode[f.item()]), shown(recipeNode[r]), f, false, r);
            for (PlanGraph.Flow f : rc.outputs())
                addEdge(merged, shown(recipeNode[r]), shown(itemNode[f.item()]), f, true, r);
        }
        edges.addAll(merged.values());

        layers();
        adjacency();
        List<List<Node>> groups = groups();
        for (List<Node> group : groups) {
            List<List<Node>> layers = byLayer(group);
            order(layers);
            place(layers);
        }
        pack(groups);
        for (Node n : nodes)
            if (n.kind == Kind.LOOP && n.unfolded)
                for (Node in : n.inner) {
                    in.x += n.x;
                    in.y += n.y;
                }
    }

    /** The box of the layers a node belongs to: itself, or its loop's. */
    Node outer(Node n) {
        int loop = n.kind == Kind.ITEM ? graph.items().get(n.ref).loop()
                : n.kind == Kind.RECIPE ? graph.recipes().get(n.ref).loop() : -1;
        return loop >= 0 ? loopNode[loop] : n;
    }

    /** What a line to the node is drawn to: itself, or its folded loop's box. */
    private Node shown(Node n) {
        Node o = outer(n);
        return o.kind == Kind.LOOP && !o.unfolded ? o : n;
    }

    /** The loop box of an item or recipe, if it is in one. */
    @Nullable Node loopOf(Node n) {
        Node o = outer(n);
        return o == n ? null : o;
    }

    Node loopNode(int loop) {
        return loopNode[loop];
    }

    private void addEdge(Map<List<Node>, Edge> merged, Node from, Node to, PlanGraph.Flow f, boolean output, int recipe) {
        if (from == to)
            return;
        boolean inner = outer(from) == outer(to) && outer(from).kind == Kind.LOOP;
        merged.merge(List.of(from, to), new Edge(from, to, f.amount(), f.kind(), output, inner, recipe, f.item()),
                (a, b) -> new Edge(a.from(), a.to(), "", a.kind(), a.output(), a.inner(), a.recipe(), a.item()));
    }

    // ── unfolded loop ──

    /** Puts the loop's items and recipes on a ring inside its frame, in the order the loop goes round. */
    private void ring(Node loop) {
        List<Node> round = new ArrayList<>();
        Set<Node> seen = new HashSet<>();
        Deque<Node> stack = new ArrayDeque<>();
        loop.inner.stream().filter(n -> n.kind == Kind.ITEM).findFirst().ifPresent(stack::push);
        // item → the loop's recipe making it → its loop material → …
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            if (!seen.add(n))
                continue;
            round.add(n);
            List<Node> next = new ArrayList<>();
            if (n.kind == Kind.ITEM) {
                for (Node r : loop.inner)
                    if (r.kind == Kind.RECIPE && graph.recipes().get(r.ref).outputs().stream().anyMatch(f -> f.item() == n.ref))
                        next.add(r);
            } else
                for (PlanGraph.Flow f : graph.recipes().get(n.ref).inputs())
                    if (graph.items().get(f.item()).loop() == loop.ref)
                        next.add(itemNode[f.item()]);
            for (int i = next.size() - 1; i >= 0; i--)
                stack.push(next.get(i));
        }
        for (Node n : loop.inner)
            if (seen.add(n))
                round.add(n);

        double around = 0;
        int maxW = 0, maxH = 0;
        for (Node n : round) {
            around += Math.max(n.w, n.h) + 16;
            maxW = Math.max(maxW, n.w);
            maxH = Math.max(maxH, n.h);
        }
        int radius = (int) Math.max(round.size() <= 1 ? 0 : 26, around / (2 * Math.PI));
        loop.w = 2 * radius + maxW + 2 * FRAME_PAD;
        loop.h = FRAME_TITLE_H + 2 * radius + maxH + 2 * FRAME_PAD;
        // relative to the frame for now; made absolute in place()
        float cx = loop.w / 2f, cy = FRAME_TITLE_H + FRAME_PAD + radius + maxH / 2f;
        for (int i = 0; i < round.size(); i++) {
            double a = -Math.PI / 2 + 2 * Math.PI * i / round.size();     // first one at the top, clockwise
            Node n = round.get(i);
            n.x = Math.round(cx + (float) (radius * Math.cos(a)) - n.w / 2f);
            n.y = Math.round(cy + (float) (radius * Math.sin(a)) - n.h / 2f);
        }
        loop.inner.clear();
        loop.inner.addAll(round);
    }

    // ── layers ──

    /** Products of each box (an edge goes from material to product), between boxes of the layers. */
    private Map<Node, List<Node>> products() {
        Map<Node, Set<Node>> m = new HashMap<>();
        for (Node n : nodes)
            m.put(n, new LinkedHashSet<>());
        for (Edge e : edges) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a != b)
                m.get(a).add(b);
        }
        Map<Node, List<Node>> out = new HashMap<>();
        m.forEach((k, v) -> out.put(k, new ArrayList<>(v)));
        return out;
    }

    private void layers() {
        Map<Node, List<Node>> products = products();
        Map<Node, List<Node>> makers = new HashMap<>();
        for (Node n : nodes)
            makers.put(n, new ArrayList<>());
        products.forEach((n, ps) -> ps.forEach(p -> makers.get(p).add(n)));
        // with one target, a box whose products are all unused leftovers (or none) hangs from what makes
        // it; without one, every box without products is a top
        Set<Node> sinks = new HashSet<>();
        if (target != null)
            for (Node n : nodes)
                if (n != target && products.get(n).isEmpty())
                    sinks.add(n);
        Map<Node, Integer> memo = new HashMap<>();
        for (Node n : nodes)
            if (!sinks.contains(n))
                n.layer = depth(n, products, sinks, memo, new HashSet<>());
        // the rest: just above the highest box that leads to them
        Deque<Node> todo = new ArrayDeque<>();
        for (Node n : nodes)
            if (n.layer < 0)
                todo.add(n);
        for (int guard = todo.size() * 2 + 2; !todo.isEmpty() && guard > 0; guard--) {
            Node n = todo.poll();
            int best = Integer.MAX_VALUE;
            for (Node m : makers.get(n))
                if (m.layer >= 0)
                    best = Math.min(best, m.layer);
            if (best != Integer.MAX_VALUE)
                n.layer = Math.max(0, best - 1);
            else
                todo.add(n);
        }
        for (Node n : nodes)
            if (n.layer < 0)
                n.layer = 0;
    }

    /** Longest way from the box up to a top, in boxes; −1 when it does not lead to the target. */
    private int depth(Node n, Map<Node, List<Node>> products, Set<Node> sinks, Map<Node, Integer> memo, Set<Node> open) {
        if (target != null ? n == target : products.get(n).isEmpty())
            return 0;
        Integer known = memo.get(n);
        if (known != null)
            return known;
        if (!open.add(n))
            return -1;      // the folded graph has no cycles; guards a malformed one
        int best = -1;
        for (Node p : products.get(n))
            if (!sinks.contains(p)) {
                int d = depth(p, products, sinks, memo, open);
                if (d >= 0)
                    best = Math.max(best, d + 1);
            }
        open.remove(n);
        memo.put(n, best);
        return best;
    }

    // ── groups, order within the layers and coordinates ──

    /** each box's neighbours a layer up / down (or more), between boxes of the layers */
    private final Map<Node, List<Node>> above = new HashMap<>(), below = new HashMap<>();

    private void adjacency() {
        for (Node n : nodes) {
            above.put(n, new ArrayList<>());
            below.put(n, new ArrayList<>());
        }
        Set<List<Node>> seen = new HashSet<>();
        for (Edge e : edges) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a == b || a.layer == b.layer || !seen.add(List.of(a, b)))
                continue;
            Node up = a.layer < b.layer ? a : b, down = up == a ? b : a;
            below.get(up).add(down);
            above.get(down).add(up);
        }
        for (Node n : nodes) {
            n.degree = above.get(n).size() + below.get(n).size();
            n.top = above.get(n).isEmpty();
        }
    }

    /** Parts of the graph joined by lines, largest first (a plan is one). */
    private List<List<Node>> groups() {
        Map<Node, Integer> group = new HashMap<>();
        List<List<Node>> groups = new ArrayList<>();
        for (Node start : nodes) {
            if (group.containsKey(start))
                continue;
            List<Node> g = new ArrayList<>();
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(start);
            group.put(start, groups.size());
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                g.add(n);
                for (List<Node> ns : List.of(above.get(n), below.get(n)))
                    for (Node m : ns)
                        if (group.putIfAbsent(m, groups.size()) == null)
                            stack.push(m);
            }
            groups.add(g);
        }
        // boxes joined only within one layer (no layer gap) are still apart here: fine, they sit side by side
        groups.sort(Comparator.comparingInt((List<Node> g) -> g.size()).reversed());
        return groups;
    }

    private static List<List<Node>> byLayer(List<Node> group) {
        int max = group.stream().mapToInt(n -> n.layer).max().orElse(0);
        int min = group.stream().mapToInt(n -> n.layer).min().orElse(0);
        List<List<Node>> layers = new ArrayList<>();
        for (int i = min; i <= max; i++)
            layers.add(new ArrayList<>());
        for (Node n : group)
            layers.get(n.layer - min).add(n);
        layers.removeIf(List::isEmpty);
        return layers;
    }

    /** Barycentre sweeps: each box goes under the average place of the boxes it is joined to. */
    private void order(List<List<Node>> layers) {
        for (List<Node> layer : layers)
            for (int i = 0; i < layer.size(); i++)
                layer.get(i).order = i;
        for (int pass = 0; pass < 6; pass++) {
            boolean down = pass % 2 == 0;
            for (int k = 0; k < layers.size(); k++) {
                List<Node> layer = layers.get(down ? k : layers.size() - 1 - k);
                for (Node n : layer) {
                    List<Node> ns = down ? above.get(n) : below.get(n);
                    if (!ns.isEmpty()) {
                        double sum = 0;
                        for (Node m : ns)
                            sum += m.order;
                        n.order = sum / ns.size();
                    }
                }
                layer.sort(Comparator.comparingDouble(m -> m.order));
                for (int i = 0; i < layer.size(); i++)
                    layer.get(i).order = i;
            }
        }
    }

    /** Coordinates within the group, its top left at about (0, 0). */
    private void place(List<List<Node>> layers) {
        int y = 0;
        for (List<Node> layer : layers) {
            layer.sort(Comparator.comparingDouble(m -> m.order));
            int h = layer.stream().mapToInt(n -> n.h).max().orElse(0);
            int x = 0;
            for (Node n : layer) {
                n.x = x;
                n.y = y + (h - n.h) / 2;
                x += n.w + GAP_X;
            }
            y += h + GAP_Y;
        }
        // pull boxes under the boxes they are joined to, keeping the order and the gaps
        for (int pass = 0; pass < 4; pass++)
            for (List<Node> layer : layers) {
                float[] want = new float[layer.size()];
                for (int i = 0; i < layer.size(); i++) {
                    Node n = layer.get(i);
                    List<Node> ns = above.get(n).isEmpty() ? below.get(n) : above.get(n);
                    if (ns.isEmpty())
                        want[i] = n.cx();
                    else {
                        float sum = 0;
                        for (Node m : ns)
                            sum += m.cx();
                        want[i] = sum / ns.size();
                    }
                }
                float[] left = new float[layer.size()], right = new float[layer.size()];
                float edge = -Float.MAX_VALUE;
                for (int i = 0; i < layer.size(); i++) {
                    Node n = layer.get(i);
                    left[i] = Math.max(want[i] - n.w / 2f, edge);
                    edge = left[i] + n.w + GAP_X;
                }
                edge = Float.MAX_VALUE;
                for (int i = layer.size() - 1; i >= 0; i--) {
                    Node n = layer.get(i);
                    right[i] = Math.min(want[i] - n.w / 2f, edge - n.w);
                    edge = right[i] - GAP_X;
                }
                // both passes keep the gaps; their average does too
                for (int i = 0; i < layer.size(); i++)
                    layer.get(i).x = Math.round((left[i] + right[i]) / 2);
                for (int i = 1; i < layer.size(); i++) {
                    Node a = layer.get(i - 1), b = layer.get(i);
                    if (b.x < a.x + a.w + GAP_X)
                        b.x = a.x + a.w + GAP_X;
                }
            }
    }

    /** Puts the groups in rows, largest first, the rows about as wide as the whole is tall. */
    private static void pack(List<List<Node>> groups) {
        if (groups.size() == 1) {
            List<Node> g = groups.get(0);
            int minX = g.stream().mapToInt(n -> n.x).min().orElse(0), maxX = g.stream().mapToInt(n -> n.x + n.w).max().orElse(0);
            int shift = -(minX + maxX) / 2;     // a plan: centred on x = 0
            for (Node n : g)
                n.x += shift;
            return;
        }
        double area = 0;
        int widest = 0;
        int[][] box = new int[groups.size()][];
        for (int i = 0; i < groups.size(); i++) {
            List<Node> g = groups.get(i);
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (Node n : g) {
                minX = Math.min(minX, n.x);
                minY = Math.min(minY, n.y);
                maxX = Math.max(maxX, n.x + n.w);
                maxY = Math.max(maxY, n.y + n.h);
            }
            box[i] = new int[]{minX, minY, maxX - minX, maxY - minY};
            area += (double) (maxX - minX + GROUP_GAP) * (maxY - minY + GROUP_GAP);
            widest = Math.max(widest, maxX - minX);
        }
        int rowWidth = Math.max(widest, (int) (Math.sqrt(area) * 1.6));
        int x = 0, y = 0, rowH = 0;
        for (int i = 0; i < groups.size(); i++) {
            int[] b = box[i];
            if (x > 0 && x + b[2] > rowWidth) {
                x = 0;
                y += rowH + GROUP_GAP;
                rowH = 0;
            }
            for (Node n : groups.get(i)) {
                n.x += x - b[0];
                n.y += y - b[1];
            }
            x += b[2] + GROUP_GAP;
            rowH = Math.max(rowH, b[3]);
        }
    }

    /** All boxes and the insides of unfolded loops. */
    List<Node> all() {
        List<Node> all = new ArrayList<>(nodes);
        for (Node n : nodes)
            all.addAll(n.inner.isEmpty() || !n.unfolded ? List.of() : n.inner);
        return all;
    }
}
