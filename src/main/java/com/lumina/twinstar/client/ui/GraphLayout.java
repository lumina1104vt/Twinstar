package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.order.PlanGraph;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
 * <p>
 * How the lines go is the player's {@link Style}. Level and upright: every box's lines to the layer above share one trunk, which runs
 * across the gap between the layers on a track of its own and branches there; a line over several
 * layers keeps a lane free of boxes in each of them (in a very large graph the longest ones go straight
 * up behind the boxes instead). With labels, a material used by
 * {@value #COMMON_USES} recipes or more gets no lines at all, only a small label in each box that uses it.
 */
final class GraphLayout {
    enum Kind { ITEM, RECIPE, LOOP }

    /** soft curves; level and upright lines; those, with labels instead of common materials' lines */
    enum Style { CURVES, SQUARE, LABELS }

    /** The texts in the boxes (their widths size the boxes). */
    interface Labels {
        String itemText(int item);

        String recipeText(int recipe);
    }

    static final int ITEM_H = 22, RECIPE_H = 14, LOOP_H = 24, FRAME_TITLE_H = 13;
    /** this many recipes using a material: labels instead of lines */
    static final int COMMON_USES = 6;
    /** a label's square and gap, and how many a box shows before "+n" */
    static final int TAG = 10, MAX_TAGS = 4;
    /** the gap between layers: its tracks' spacing, and the room above (under the upper boxes: amounts) and below them */
    private static final int TRACK = 4, TRACK_TOP = 17, TRACK_BOTTOM = 10;
    /** room per line arriving at a box, so each has its own place on the box's lower edge */
    private static final int PORT = 7, MAX_PORT_W = 90;
    /** lanes this many at most (shortest lines first); a gap's tracks this many, further ones share */
    private static final int LANES = 12000, MAX_TRACKS = 48;
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
        /** not a box: the room a long line keeps in a layer it passes */
        boolean dummy;
        /** a lane moved a little off its place, to go on straight from what is under it */
        float off;
        /** the common materials it uses (item indices), shown as labels in it */
        final List<Integer> tags = new ArrayList<>(0);
        /** width its labels take at its left end */
        int tagW;

        Node(Kind kind, int ref, int w, int h) {
            this.kind = kind;
            this.ref = ref;
            this.w = w;
            this.h = h;
        }

        float cx() {
            return x + w / 2f + off;
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
    /** lines not drawn (a common material to what uses it); they still say which layer a box is in */
    private final List<Edge> hidden = new ArrayList<>();
    /** a line's corners x, y, x, y… from its start to its end; no entry: inside a loop, or the graph is too large */
    final Map<Edge, float[]> routes = new IdentityHashMap<>();

    /** Where lines of one box branch. */
    record Dot(float x, float y, Node from) {
    }

    final List<Dot> dots = new ArrayList<>();
    /** common material → how many boxes use it */
    final Map<Integer, Integer> common = new HashMap<>();
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
        this(graph, unfolded, oneTarget, labels, textWidth, Style.LABELS);
    }

    GraphLayout(PlanGraph graph, Set<Integer> unfolded, boolean oneTarget, Labels labels, ToIntFunction<String> textWidth,
                Style style) {
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
        for (Node l : loopNode)
            l.unfolded = unfolded.contains(l.ref);

        // common materials: labels in what uses them
        Map<Integer, Set<Node>> users = new HashMap<>();
        for (int r = 0; r < recipeNode.length; r++)
            for (PlanGraph.Flow f : graph.recipes().get(r).inputs())
                if (!sameLoop(f.item(), r))
                    users.computeIfAbsent(f.item(), k -> new LinkedHashSet<>()).add(outer(recipeNode[r]));
        users.forEach((item, boxes) -> {
            if (style == Style.LABELS && boxes.size() >= COMMON_USES && !(oneTarget && item == 0))
                common.put(item, boxes.size());
        });
        for (int r = 0; r < recipeNode.length; r++)
            for (PlanGraph.Flow f : graph.recipes().get(r).inputs())
                if (common.containsKey(f.item()) && !sameLoop(f.item(), r)) {
                    Node at = shown(recipeNode[r]);
                    if (!at.tags.contains(f.item()))
                        at.tags.add(f.item());
                }
        for (Node n : recipeNode)
            n.w += n.tagW = tagsWidth(n, textWidth);

        // lines: to the inside of an unfolded loop, to the loop's box when folded; merged when they fall together
        Map<List<Node>, Edge> merged = new LinkedHashMap<>(), unseen = new LinkedHashMap<>();
        for (int r = 0; r < recipeNode.length; r++) {
            PlanGraph.Recipe rc = graph.recipes().get(r);
            for (PlanGraph.Flow f : rc.inputs())
                addEdge(common.containsKey(f.item()) && !sameLoop(f.item(), r) ? unseen : merged,
                        shown(itemNode[f.item()]), shown(recipeNode[r]), f, false, r);
            for (PlanGraph.Flow f : rc.outputs())
                addEdge(merged, shown(recipeNode[r]), shown(itemNode[f.item()]), f, true, r);
        }
        edges.addAll(merged.values());
        hidden.addAll(unseen.values());
        Map<Node, Integer> arriving = new HashMap<>();
        for (Edge e : edges)
            arriving.merge(e.to(), 1, Integer::sum);
        arriving.forEach((n, count) -> {
            if (style != Style.CURVES && n.kind != Kind.LOOP && count > 1)
                n.w = Math.max(n.w, Math.min(MAX_PORT_W, count * PORT + 6));
        });

        for (Node l : loopNode) {
            if (l.unfolded)
                ring(l);
            else {
                int items = (int) l.inner.stream().filter(n -> n.kind == Kind.ITEM).count();
                int more = items - LOOP_ICONS;
                l.tagW = tagsWidth(l, textWidth);
                l.w = l.tagW + 14 + Math.min(items, LOOP_ICONS) * 17 + (more > 0 ? textWidth.applyAsInt("+" + more) + 3 : 0) + 3;
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

        layers();
        standing();
        List<List<Node>> groups = groups();
        boolean routed = style != Style.CURVES;
        if (routed)
            chains(groups);
        adjacency();
        List<int[]> moved = new ArrayList<>();      // each group's first box's height before packing
        for (List<Node> group : groups) {
            List<List<Node>> layers = byLayer(group);
            order(layers);
            place(layers);
            if (routed)
                tracks(group);
            rows(layers);
            moved.add(new int[]{group.get(0).y});
        }
        pack(groups);
        for (Node n : nodes)
            if (n.kind == Kind.LOOP && n.unfolded)
                for (Node in : n.inner) {
                    in.x += n.x;
                    in.y += n.y;
                }
        if (routed) {
            for (int i = 0; i < groups.size(); i++) {
                int dy = groups.get(i).get(0).y - moved.get(i)[0];
                if (dy != 0)
                    for (Node n : groups.get(i))
                        for (Node source : n.kind == Kind.LOOP && n.unfolded ? n.inner : List.of(n))
                            for (Seg s : segs.getOrDefault(source, Map.of()).values())
                                s.y += dy;
            }
            route();
        }
        for (List<Node> group : groups)
            group.removeIf(n -> n.dummy);
    }

    /** The item and the recipe are in the same loop: their line is part of the loop's ring. */
    private boolean sameLoop(int item, int recipe) {
        int loop = graph.items().get(item).loop();
        return loop >= 0 && loop == graph.recipes().get(recipe).loop();
    }

    private static int tagsWidth(Node n, ToIntFunction<String> textWidth) {
        if (n.tags.isEmpty())
            return 0;
        int more = n.tags.size() - MAX_TAGS;
        return Math.min(n.tags.size(), MAX_TAGS) * TAG + (more > 0 ? textWidth.applyAsInt("+" + more) + 2 : 0) + 2;
    }

    /** The box lines of an item start from: its own, or its folded loop's. */
    Node shownItem(int item) {
        return shown(itemNode[item]);
    }

    /** The box of the layers a node belongs to: itself, or its loop's. */
    Node outer(Node n) {
        int loop = n.dummy ? -1 : n.kind == Kind.ITEM ? graph.items().get(n.ref).loop()
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
        for (Edge e : allEdges()) {
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

    private List<Edge> allEdges() {
        if (hidden.isEmpty())
            return edges;
        List<Edge> all = new ArrayList<>(edges.size() + hidden.size());
        all.addAll(edges);
        all.addAll(hidden);
        return all;
    }

    /** How much each box stands out far away: how many it is joined to, and whether nothing is above it. */
    private void standing() {
        Set<List<Node>> seen = new HashSet<>();
        Set<Node> under = new HashSet<>();
        for (Edge e : allEdges()) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a == b || a.layer == b.layer || !seen.add(List.of(a, b)))
                continue;
            a.degree++;
            b.degree++;
            under.add(a.layer < b.layer ? b : a);
        }
        for (Node n : nodes)
            n.top = !under.contains(n);
    }

    /** Parts of the graph joined by lines, largest first (a plan is one). */
    private List<List<Node>> groups() {
        Map<Node, List<Node>> joined = new HashMap<>();
        for (Edge e : allEdges()) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a != b && a.layer != b.layer) {
                joined.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                joined.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
            }
        }
        Set<Node> done = new HashSet<>();
        List<List<Node>> groups = new ArrayList<>();
        for (Node start : nodes) {
            if (!done.add(start))
                continue;
            List<Node> g = new ArrayList<>();
            Deque<Node> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                g.add(n);
                for (Node m : joined.getOrDefault(n, List.of()))
                    if (done.add(m))
                        stack.push(m);
            }
            groups.add(g);
        }
        // boxes joined only within one layer (no layer gap) are still apart here: fine, they sit side by side
        groups.sort(Comparator.comparingInt((List<Node> g) -> g.size()).reversed());
        return groups;
    }

    // ── lanes for long lines ──

    /** a line's source → the lanes it keeps, by layer */
    private final Map<Node, Map<Integer, Node>> lanes = new IdentityHashMap<>();
    private boolean routing;

    /** A line from a lower layer up to a higher one: routed through the gaps between them. */
    private boolean upward(Edge e) {
        return !e.inner() && outer(e.from()).layer > outer(e.to()).layer;
    }

    /** Gives long lines their lanes, added to their group: the shortest first, while there are lanes left. */
    private void chains(List<List<Node>> groups) {
        Map<Node, Integer> highest = new IdentityHashMap<>();
        for (Edge e : edges)
            if (upward(e))
                highest.merge(e.from(), outer(e.to()).layer, Math::min);
        Map<Node, List<Node>> groupOf = new IdentityHashMap<>();
        for (List<Node> g : groups)
            for (Node n : g)
                groupOf.put(n, g);
        List<Node> sources = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Edge e : edges)
            if (highest.containsKey(e.from()) && seen.add(e.from()))
                sources.add(e.from());
        sources.sort(Comparator.comparingInt((Node n) -> outer(n).layer - highest.get(n)));
        int left = LANES;
        for (Node from : sources) {
            int top = highest.get(from);
            left -= outer(from).layer - top - 1;
            if (left < 0)
                break;
            Map<Integer, Node> mine = new HashMap<>();
            for (int layer = top + 1; layer < outer(from).layer; layer++) {
                Node d = new Node(Kind.ITEM, -1, 1, 0);
                d.dummy = true;
                d.layer = layer;
                mine.put(layer, d);
                groupOf.get(outer(from)).add(d);
            }
            lanes.put(from, mine);
        }
        routing = true;
    }

    /** Whether the line has its way up between the boxes: one layer needs none. */
    private boolean laned(Edge e) {
        return outer(e.from()).layer - outer(e.to()).layer == 1 || lanes.containsKey(e.from());
    }

    // ── order within the layers and coordinates ──

    /** each box's (and lane's) neighbours up / down, as the drawn lines join them */
    private final Map<Node, List<Node>> above = new HashMap<>(), below = new HashMap<>();

    private void adjacency() {
        Set<List<Node>> seen = new HashSet<>();
        for (Edge e : edges) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a == b || a.layer == b.layer)
                continue;
            if (routing && upward(e) && a.layer - b.layer > 1 && lanes.containsKey(e.from())) {
                Node low = a;
                for (int layer = a.layer - 1; layer > b.layer; layer--) {
                    Node d = lanes.get(e.from()).get(layer);
                    join(seen, d, low);
                    low = d;
                }
                join(seen, b, low);
            } else
                join(seen, a.layer < b.layer ? a : b, a.layer < b.layer ? b : a);
        }
        // a common material nothing else is drawn to: under what uses it, without pulling those to itself
        Map<Node, List<Node>> uses = new HashMap<>();
        for (Edge e : hidden) {
            Node a = outer(e.from()), b = outer(e.to());
            if (a.layer > b.layer && !above.containsKey(a) && !below.containsKey(a))
                uses.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        }
        above.putAll(uses);
    }

    private void join(Set<List<Node>> seen, Node up, Node down) {
        if (!seen.add(List.of(up, down)))
            return;
        below.computeIfAbsent(up, k -> new ArrayList<>()).add(down);
        above.computeIfAbsent(down, k -> new ArrayList<>()).add(up);
    }

    private List<Node> over(Node n) {
        return above.getOrDefault(n, List.of());
    }

    private List<Node> under(Node n) {
        return below.getOrDefault(n, List.of());
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
                    List<Node> ns = down ? over(n) : under(n);
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

    /** lanes lie close together, boxes keep their distance */
    private static int gap(Node a, Node b) {
        return a.dummy && b.dummy ? 4 : a.dummy || b.dummy ? 7 : GAP_X;
    }

    /** x of every box and lane within the group */
    private void place(List<List<Node>> layers) {
        for (List<Node> layer : layers) {
            layer.sort(Comparator.comparingDouble(m -> m.order));
            int x = 0;
            for (int i = 0; i < layer.size(); i++) {
                Node n = layer.get(i);
                if (i > 0)
                    x += gap(layer.get(i - 1), n);
                n.x = x;
                x += n.w;
            }
        }
        // pull boxes under the boxes they are joined to, keeping the order and the gaps
        for (int pass = 0; pass < (routing ? 8 : 4); pass++)
            for (int k = 0; k < layers.size(); k++) {
                List<Node> layer = layers.get(routing && pass % 2 == 1 ? layers.size() - 1 - k : k);
                float[] want = new float[layer.size()];
                for (int i = 0; i < layer.size(); i++) {
                    Node n = layer.get(i);
                    List<Node> ns = over(n).isEmpty() || routing && pass % 2 == 1 && !under(n).isEmpty() ? under(n) : over(n);
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
                    if (i + 1 < layer.size())
                        edge = left[i] + n.w + gap(n, layer.get(i + 1));
                }
                edge = Float.MAX_VALUE;
                for (int i = layer.size() - 1; i >= 0; i--) {
                    Node n = layer.get(i);
                    right[i] = Math.min(want[i] - n.w / 2f, edge - n.w);
                    if (i > 0)
                        edge = right[i] - gap(layer.get(i - 1), n);
                }
                // both passes keep the gaps; their average does too
                for (int i = 0; i < layer.size(); i++)
                    layer.get(i).x = Math.round((left[i] + right[i]) / 2);
                for (int i = 1; i < layer.size(); i++) {
                    Node a = layer.get(i - 1), b = layer.get(i);
                    if (b.x < a.x + a.w + gap(a, b))
                        b.x = a.x + a.w + gap(a, b);
                }
            }
        if (routing)
            straighten(layers);
    }

    /** Each lane goes straight up from what is under it, as far as its neighbours leave room. */
    private void straighten(List<List<Node>> layers) {
        for (int k = layers.size() - 1; k >= 0; k--) {
            List<Node> layer = layers.get(k);
            for (int i = 0; i < layer.size(); i++) {
                Node n = layer.get(i);
                if (!n.dummy || under(n).size() != 1)
                    continue;
                int x = (int) Math.floor(under(n).get(0).cx());
                if (i > 0)
                    x = Math.max(x, layer.get(i - 1).x + layer.get(i - 1).w + gap(layer.get(i - 1), n));
                if (i + 1 < layer.size())
                    x = Math.min(x, layer.get(i + 1).x - gap(n, layer.get(i + 1)) - n.w);
                n.x = x;
            }
        }
    }

    // ── tracks and routes ──

    /** The part of one box's lines in one gap between layers: up from {@link #bottom}, along a track, up to each end. */
    private static final class Seg {
        Node bottom;
        /** the lane it goes on through, if it does */
        Node onward;
        final List<Edge> ends = new ArrayList<>();
        int track = -1;
        int y;
    }

    /** source of the lines → layer above the gap → its part there */
    private final Map<Node, Map<Integer, Seg>> segs = new IdentityHashMap<>();
    /** of the group being laid out: layer above the gap → tracks used there */
    private final Map<Integer, Integer> trackCount = new HashMap<>();
    /** where each line arrives on its box's lower edge, from the box's left */
    private final Map<Edge, Float> port = new IdentityHashMap<>();

    /** x of a box's middle before the loops' insides are moved into place */
    private float middle(Node n) {
        Node o = outer(n);
        return o == n ? n.cx() : o.x + n.cx();
    }

    private float endX(Edge e) {
        Node o = outer(e.to());
        return (o == e.to() ? e.to().x : o.x + e.to().x) + port.get(e);
    }

    private void tracks(List<Node> group) {
        trackCount.clear();
        Set<Node> in = Collections.newSetFromMap(new IdentityHashMap<>());
        in.addAll(group);
        // lanes go on straight from what is under them where that is only a small step away
        List<Node> rising = new ArrayList<>();
        for (Node n : group)
            if (n.dummy && under(n).size() == 1)
                rising.add(n);
        rising.sort(Comparator.comparingInt((Node n) -> -n.layer));
        for (Node n : rising) {
            float step = under(n).get(0).cx() - (n.x + n.w / 2f);
            if (Math.abs(step) <= 3)
                n.off = step;
        }
        Map<Node, List<Edge>> arriving = new IdentityHashMap<>();
        for (Edge e : edges)
            if (upward(e) && in.contains(outer(e.from())))
                arriving.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        arriving.forEach((to, list) -> {
            list.sort(Comparator.comparingDouble(e -> middle(e.from())));
            for (int i = 0; i < list.size(); i++)
                port.put(list.get(i), to.w * (i + 1f) / (list.size() + 1));
            straightPorts(to, list);
        });
        Map<Integer, List<Seg>> gaps = new HashMap<>();
        for (Edge e : edges) {
            if (!upward(e) || !in.contains(outer(e.from())))
                continue;
            int s = outer(e.from()).layer, t = outer(e.to()).layer;
            Map<Integer, Seg> mine = segs.computeIfAbsent(e.from(), k -> new HashMap<>());
            for (int c = s - 1; c >= (laned(e) ? t : s - 1); c--) {
                Seg seg = mine.get(c);
                if (seg == null) {
                    seg = new Seg();
                    seg.bottom = c == s - 1 ? e.from() : lanes.get(e.from()).get(c + 1);
                    mine.put(c, seg);
                    gaps.computeIfAbsent(c, k -> new ArrayList<>()).add(seg);
                }
                if (c > t && laned(e))
                    seg.onward = lanes.get(e.from()).get(c);
                else
                    seg.ends.add(e);
            }
        }
        gaps.forEach((layer, list) -> {
            List<float[]> spans = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                Seg s = list.get(i);
                float lo = middle(s.bottom), hi = lo;
                for (Edge e : s.ends) {
                    lo = Math.min(lo, endX(e));
                    hi = Math.max(hi, endX(e));
                }
                if (s.onward != null) {
                    lo = Math.min(lo, s.onward.cx());
                    hi = Math.max(hi, s.onward.cx());
                }
                if (hi - lo >= 1)
                    spans.add(new float[]{lo, hi, i});
            }
            spans.sort(Comparator.comparingDouble(a -> a[0]));
            List<Float> taken = new ArrayList<>();      // each track's right end
            for (float[] span : spans) {
                int track = 0;
                while (track < taken.size() && taken.get(track) + 4 > span[0])
                    track++;
                if (track == taken.size())
                    taken.add(span[1]);
                else
                    taken.set(track, span[1]);
                list.get((int) span[2]).track = track;
            }
            trackCount.put(layer, taken.size());
        });
        pending = gaps;
    }

    private Map<Integer, List<Seg>> pending = Map.of();

    /**
     * A line whose way up already lies under its box arrives straight there, unless another line's
     * place is too near.
     */
    private void straightPorts(Node to, List<Edge> list) {
        float left = (outer(to) == to ? to.x : outer(to).x + to.x);
        for (int i = 0; i < list.size(); i++) {
            Edge e = list.get(i);
            int s = outer(e.from()).layer, t = outer(to).layer;
            Map<Integer, Node> lane = lanes.get(e.from());
            float coming = s - t > 1 && lane != null ? lane.get(t + 1).cx() : middle(e.from());
            float at = coming - left;
            float low = i == 0 ? 2 : port.get(list.get(i - 1)) + 4;
            float high = i == list.size() - 1 ? to.w - 2 : port.get(list.get(i + 1)) - 4;
            if (at >= low && at <= high)
                port.put(e, at);
        }
    }

    /** y of every row, the gaps tall enough for their tracks, and of the tracks. */
    private void rows(List<List<Node>> layers) {
        int y = 0;
        for (List<Node> layer : layers) {
            int h = layer.stream().mapToInt(n -> n.h).max().orElse(0);
            h = Math.max(h, ITEM_H);
            for (Node n : layer) {
                if (n.dummy) {
                    n.y = y;
                    n.h = h;
                } else
                    n.y = y + (h - n.h) / 2;
            }
            int index = layer.get(0).layer;
            int tracks = trackCount.getOrDefault(index, 0);
            List<Seg> here = pending.get(index);
            if (here != null)
                for (Seg s : here)
                    s.y = y + h + TRACK_TOP + Math.max(0, s.track) % MAX_TRACKS * TRACK;
            y += h + Math.max(GAP_Y, TRACK_TOP + Math.max(0, Math.min(tracks, MAX_TRACKS) - 1) * TRACK + TRACK_BOTTOM);
        }
        pending = Map.of();
    }

    /** The corners of every line, and the points where lines branch. */
    private void route() {
        for (Edge e : edges) {
            if (e.inner())
                continue;
            Node from = e.from(), to = e.to();
            int s = outer(from).layer, t = outer(to).layer;
            float x = from.cx();
            if (s <= t) {       // a leftover beside or above what made it
                float y1 = s == t ? from.y : from.y + from.h, y2 = to.y;
                float mid = s == t ? Math.min(y1, y2) - 6 : (y1 + y2) / 2;
                routes.put(e, new float[]{x, y1, x, mid, to.cx(), mid, to.cx(), y2});
                continue;
            }
            if (!laned(e)) {    // along under the first gap, then straight up behind whatever is there
                float up = to.x + port.get(e), turn = segs.get(from).get(s - 1).y;
                routes.put(e, new float[]{x, from.y, x, turn, up, turn, up, to.y + to.h});
                continue;
            }
            float[] p = new float[(s - t) * 4 + 4];
            int i = 0;
            p[i++] = x;
            p[i++] = from.y;
            for (int c = s - 1; c >= t; c--) {
                Seg seg = segs.get(from).get(c);
                float next = c == t ? to.x + port.get(e) : lanes.get(from).get(c).cx();
                p[i++] = x;
                p[i++] = seg.y;
                p[i++] = next;
                p[i++] = seg.y;
                x = next;
            }
            p[i++] = x;
            p[i] = to.y + to.h;
            routes.put(e, p);
        }
        segs.forEach((from, byLayer) -> byLayer.values().forEach(seg -> {
            float bottom = seg.bottom.cx();
            List<Float> up = new ArrayList<>();
            for (Edge e : seg.ends)
                up.add(e.to().x + port.get(e));
            if (seg.onward != null)
                up.add(seg.onward.cx());
            float lo = bottom, hi = bottom;
            for (float u : up) {
                lo = Math.min(lo, u);
                hi = Math.max(hi, u);
            }
            Set<Float> at = new LinkedHashSet<>(up);
            at.add(bottom);
            for (float x : at) {
                int ways = (x > lo + 0.5f ? 1 : 0) + (x < hi - 0.5f ? 1 : 0)
                        + (up.stream().anyMatch(u -> Math.abs(u - x) < 0.5f) ? 1 : 0) + (Math.abs(bottom - x) < 0.5f ? 1 : 0);
                if (ways >= 3)
                    dots.add(new Dot(x, seg.y, from));
            }
        }));
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
