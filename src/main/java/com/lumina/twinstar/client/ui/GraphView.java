package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.order.PlanGraph;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.lumina.twinstar.client.ui.PlanUI.DIM;
import static com.lumina.twinstar.client.ui.PlanUI.LOOP_COLOR;
import static com.lumina.twinstar.client.ui.PlanUI.TEXT;

/**
 * A full-screen crafting graph (the plan's, the theory viewer's): drag to move, wheel to zoom
 * around the mouse, click a loop to unfold it and its frame's title to fold it again. Subclasses
 * give the graph, the texts, colours, marks and tooltips, and may draw panels over it.
 * <p>
 * A common material has no lines: hovering a label of it (or its box) shows them, a click keeps them.
 * <p>
 * States are never told by red vs green alone: something missing has a thick orange frame and a
 * "!" tab, an infinite source an ∞ tab, an item that can be opened a +/− tab.
 */
public abstract class GraphView {
    protected static final int BAR_H = 22, BUTTON_W = 40;
    private static final int BACKGROUND = 0xFF07070C, GRID = 0xFF16152A, BAR = 0xF00E0D18, BAR_LINE = 0xFF2E2A48;
    protected static final int BOX = 0xFF14121F, RECIPE_BOX = 0xFF1A1830, PANEL = 0xF0100F1A, PANEL_LINE = 0xFF3A3458;
    private static final int LOOP_BOX = 0xFF201430, FRAME = 0x40281C40;
    private static final int LINE = 0xFF4A4670, LINE_HOT = 0xFFB4A6FF;
    protected static final int RECIPE_LINE = 0xFF5C64A8, BEST = 0xFFD6CCFF;
    private static final float MIN_ZOOM = 0.02f, MAX_ZOOM = 3f;
    /** below this zoom boxes are plain coloured blocks and lines straight (thousands of boxes stay smooth) */
    private static final float DETAIL_ZOOM = 0.7f, FAR_ZOOM = 0.12f;
    /** far away: icons kept this many at most, names only for the tops of the parts */
    private static final int LANDMARKS = 500, LANDMARK = 18;
    private static final int LINE_STRONG = 0xFFA89CF0;
    /** colours told apart without red/green: orange = a problem, blue = from the network, violet = made */
    protected static final int MISSING = 0xFFFF9A3C, MISSING_LINE = 0xFF9A6030, FROM_STOCK = 0xFF5AB4FF, MADE = 0xFFB48CFF;

    /** Marks hung on a box's top right corner. */
    protected enum Tab { MISSING, INFINITE, OPEN, CLOSE, LOADING }

    protected final Font font = Minecraft.getInstance().font;
    protected int width, height;

    protected @Nullable GraphLayout layout;
    private final Set<Integer> unfolded = new HashSet<>();
    private float panX, panY, zoom = 1;
    private boolean pressing, dragging;
    /** the common material whose lines stay shown (a clicked label), −1: none */
    private int pinned = -1;
    private double dragged;
    private final Runnable back;

    protected GraphView(Runnable back) {
        this.back = back;
    }

    /** this frame: the screen in the graph's units, and far away the line pieces already drawn */
    private double seenLeft, seenTop, seenRight, seenBottom;
    private final Set<Long> drawnPieces = new HashSet<>();

    // ── what subclasses give ──

    protected abstract Component title();

    /** Shown when there is no graph. */
    protected abstract Component emptyText();

    protected abstract String itemText(int item);

    protected abstract int itemTextColor(int item);

    protected abstract int itemFrame(int item);

    protected abstract List<Tab> itemTabs(int item);

    protected abstract String recipeText(int recipe);

    protected abstract int recipeTextColor(int recipe);

    /** A recipe drawn with a bright double frame (the engine's choice). */
    protected boolean recipeBest(int recipe) {
        return false;
    }

    protected boolean recipeMissing(int recipe) {
        return false;
    }

    protected boolean itemMissing(int item) {
        return itemTabs(item).contains(Tab.MISSING);
    }

    protected abstract List<Component> itemTooltip(int item);

    protected abstract List<Component> recipeTooltip(int recipe);

    protected abstract List<Component> loopTooltip(int loop, List<Integer> items, int recipes);

    /** Everything leads to item 0 (a plan, one part of the theory map); otherwise many tops, parts side by side. */
    protected boolean oneTarget() {
        return true;
    }

    /** A line drawn thick and bright (the engine's recommended route). */
    protected boolean edgeStrong(GraphLayout.Edge edge) {
        return false;
    }

    /** A switch of the subclass's own on the bar: its text, null for none. */
    protected @Nullable Component barSwitch() {
        return null;
    }

    protected void barSwitched() {
    }

    /** False: common materials keep their lines even with labels chosen. */
    protected boolean labelsAllowed() {
        return true;
    }

    /** A click on an item box (not a drag). */
    protected void itemClicked(int item) {
    }

    /** Width kept free on the right (a side panel) when fitting the graph. */
    protected int rightPanelWidth() {
        return 0;
    }

    /** Drawn over the graph, under the bar and tooltips. */
    protected void drawOverlay(GuiGraphics g, int mx, int my) {
    }

    /** True when the overlay takes the point (no graph hover or drag there). */
    protected boolean overlayContains(double mx, double my) {
        return false;
    }

    protected boolean overlayClicked(double mx, double my, int button) {
        return false;
    }

    protected boolean overlayScrolled(double mx, double my, double amount) {
        return false;
    }

    /** The bar's text right of the title (status); null for the hint. */
    protected @Nullable Component barStatus() {
        return null;
    }

    protected int barStatusColor() {
        return DIM;
    }

    // ── graph ──

    /**
     * Shows a graph. {@code sameGraph}: the same things again (newer data), so open loops stay open
     * and the view stays where it is; otherwise loops fold and the view fits the new graph.
     */
    protected void setGraph(@Nullable PlanGraph graph, boolean sameGraph) {
        if (!sameGraph) {
            unfolded.clear();
            pinned = -1;
        }
        if (graph == null) {
            layout = null;
            return;
        }
        unfolded.removeIf(l -> l >= graph.loops().size());
        layout = lay(graph);
        if (!sameGraph)
            fit();
    }

    private GraphLayout lay(PlanGraph graph) {
        GraphLayout.Style style = GraphStyle.get();
        if (style == GraphLayout.Style.LABELS && !labelsAllowed())
            style = GraphLayout.Style.SQUARE;
        return new GraphLayout(graph, unfolded, oneTarget(), labels(), font::width, style);
    }

    /** The subclass's switch, left of the line style: its left end and width; null when there is none. */
    private int @Nullable [] switchButton() {
        Component text = barSwitch();
        if (text == null)
            return null;
        int w = font.width(text) + 12;
        return new int[]{styleButton()[0] - 4 - w, w};
    }

    /** The bar's button for how lines go: its left end and width. */
    private int[] styleButton() {
        int w = font.width(GraphStyle.name(GraphStyle.get())) + 12;
        return new int[]{width - BUTTON_W - 8 - w, w};
    }

    private void nextStyle() {
        GraphStyle.next();
        if (layout != null) {
            pinned = -1;
            layout = lay(layout.graph);
            fit();
        }
    }

    private GraphLayout.Labels labels() {
        return new GraphLayout.Labels() {
            @Override
            public String itemText(int item) {
                return GraphView.this.itemText(item);
            }

            @Override
            public String recipeText(int recipe) {
                return GraphView.this.recipeText(recipe);
            }
        };
    }

    public void resize(int width, int height) {
        boolean first = this.width == 0;
        this.width = width;
        this.height = height;
        if (first)
            fit();
    }

    /** Shows the whole graph, at most a little larger than 1:1. */
    protected void fit() {
        if (layout == null || width == 0)
            return;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (GraphLayout.Node n : layout.nodes) {
            minX = Math.min(minX, n.x);
            minY = Math.min(minY, n.y);
            maxX = Math.max(maxX, n.x + n.w);
            maxY = Math.max(maxY, n.y + n.h);
        }
        if (minX > maxX)
            return;
        float availW = width - rightPanelWidth(), availH = height - BAR_H;
        zoom = clamp(Math.min((availW - 40f) / Math.max(1, maxX - minX), (availH - 40f) / Math.max(1, maxY - minY)), MIN_ZOOM, 1.5f);
        panX = availW / 2f - (minX + maxX) / 2f * zoom;
        panY = BAR_H + availH / 2f - (minY + maxY) / 2f * zoom;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private double worldX(double sx) {
        return (sx - panX) / zoom;
    }

    private double worldY(double sy) {
        return (sy - panY) / zoom;
    }

    // ── input ──

    public boolean mouseClicked(double mx, double my, int button) {
        if (my < BAR_H) {
            if (button != 0)
                return true;
            if (mx < BAR_H)
                back.run();
            else if (mx >= width - BUTTON_W - 4 && mx < width - 4)
                fit();
            else if (mx >= styleButton()[0] && mx < styleButton()[0] + styleButton()[1])
                nextStyle();
            else if (switchButton() != null && mx >= switchButton()[0] && mx < switchButton()[0] + switchButton()[1])
                barSwitched();
            return true;
        }
        if (overlayContains(mx, my))
            return overlayClicked(mx, my, button);
        if (button != 0)
            return false;
        pressing = true;
        dragging = false;
        dragged = 0;
        return true;
    }

    /** An overlay that is being dragged (a movable panel) takes the drag. */
    protected boolean overlayDragged(double dx, double dy) {
        return false;
    }

    /** The button came up: an overlay that took the press ends it. */
    protected boolean overlayReleased(double mx, double my, int button) {
        return false;
    }

    public boolean mouseDragged(int button, double dx, double dy) {
        if (button == 0 && overlayDragged(dx, dy))
            return true;
        if (!pressing || button != 0)
            return false;
        dragged += Math.abs(dx) + Math.abs(dy);
        if (dragged > 3)
            dragging = true;
        if (dragging) {
            panX += (float) dx;
            panY += (float) dy;
        }
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (overlayReleased(mx, my, button))
            return true;
        if (!pressing || button != 0)
            return false;
        pressing = false;
        if (!dragging) {
            GraphLayout.Node mark = landmarkAt(mx, my);
            if (mark != null)
                click(mark.cx(), mark.cy());    // its icon stands for its tiny box
            else
                click(worldX(mx), worldY(my));
        }
        dragging = false;
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double amount) {
        if (overlayContains(mx, my))
            return overlayScrolled(mx, my, amount);
        if (amount == 0 || my < BAR_H)
            return false;
        float next = clamp(zoom * (float) Math.pow(1.15, amount), MIN_ZOOM, MAX_ZOOM);
        panX = (float) (mx - (mx - panX) * next / zoom);
        panY = (float) (my - (my - panY) * next / zoom);
        zoom = next;
        return true;
    }

    /** A folded loop unfolds; an unfolded loop's title folds it (the loop stays where it was); an item is the subclass's. */
    private void click(double wx, double wy) {
        if (layout == null)
            return;
        int tag = tagAt(wx, wy);
        if (tag >= 0) {
            pinned = pinned == tag ? -1 : tag;
            return;
        }
        if (layout.all().stream().noneMatch(n -> n.contains(wx, wy)))
            pinned = -1;
        for (GraphLayout.Node n : layout.nodes) {
            if (n.kind != GraphLayout.Kind.LOOP || !n.contains(wx, wy))
                continue;
            if (n.unfolded && wy >= n.y + GraphLayout.FRAME_TITLE_H)
                break;      // inside the frame: the loop's own boxes
            float keepX = n.x * zoom + panX, keepY = n.y * zoom + panY;
            if (n.unfolded)
                unfolded.remove(n.ref);
            else
                unfolded.add(n.ref);
            layout = lay(layout.graph);
            GraphLayout.Node again = layout.loopNode(n.ref);
            panX = keepX - again.x * zoom;
            panY = keepY - again.y * zoom;
            return;
        }
        for (GraphLayout.Node n : layout.all())
            if (n.kind == GraphLayout.Kind.ITEM && n.contains(wx, wy)) {
                itemClicked(n.ref);
                return;
            }
    }

    /** Centres the view on the item at a readable size. */
    protected void focusOn(int item) {
        if (layout == null)
            return;
        GraphLayout.Node n = find(layout, item);
        if (n == null)
            return;
        zoom = Math.max(zoom, 1.2f);
        panX = (width - rightPanelWidth()) / 2f - n.cx() * zoom;
        panY = BAR_H + (height - BAR_H) / 2f - n.cy() * zoom;
    }

    /** Keeps the item's box where it is on the screen across a new layout (after {@code change} runs). */
    protected void keepInPlace(int item, Runnable change) {
        GraphLayout.Node before = layout == null ? null : find(layout, item);
        float sx = before == null ? 0 : before.x * zoom + panX, sy = before == null ? 0 : before.y * zoom + panY;
        change.run();
        GraphLayout.Node after = layout == null ? null : find(layout, item);
        if (before != null && after != null) {
            panX = sx - after.x * zoom;
            panY = sy - after.y * zoom;
        }
    }

    private static @Nullable GraphLayout.Node find(GraphLayout layout, int item) {
        for (GraphLayout.Node n : layout.all())
            if (n.kind == GraphLayout.Kind.ITEM && n.ref == item)
                return n;
        return null;
    }

    // ── drawing ──

    public void render(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, height, BACKGROUND);
        grid(g);
        Hovered under = hoverAt(mx, my);
        GraphLayout.Node hover = under == null ? null : under.node();
        boolean frameTitle = under != null && under.frameTitle();
        int tag = hover == null || my < BAR_H ? -1 : tagAt(worldX(mx), worldY(my));
        // the common material whose lines are shown: the label or box under the mouse, else the kept one
        int lit = tag >= 0 ? tag : hover != null && hover.kind == GraphLayout.Kind.ITEM && layout.common.containsKey(hover.ref)
                ? hover.ref : pinned;

        if (layout != null) {
            // what is on the screen, in world units (with a margin for tabs and labels)
            double vx0 = worldX(-20), vy0 = worldY(BAR_H - 20), vx1 = worldX(width + 20), vy1 = worldY(height + 20);
            boolean detail = zoom >= DETAIL_ZOOM;
            // the graph stays between the bar and the side panel (tabs and icons draw high up)
            g.enableScissor(0, BAR_H, width - rightPanelWidth(), height);
            g.pose().pushPose();
            g.pose().translate(panX, panY, 0);
            g.pose().scale(zoom, zoom, 1);
            for (GraphLayout.Node n : layout.nodes)
                if (n.kind == GraphLayout.Kind.LOOP && n.unfolded && visible(n, vx0, vy0, vx1, vy1))
                    frame(g, n);
            // very far out only the strong lines: a large pack has a hundred thousand of them
            boolean thin = zoom < FAR_ZOOM;
            seenLeft = vx0;
            seenTop = vy0;
            seenRight = vx1;
            seenBottom = vy1;
            drawnPieces.clear();
            // strong and hovered lines over the rest: routed lines run over each other
            for (int pass = 0; pass < 2; pass++)
                for (GraphLayout.Edge e : layout.edges) {
                    boolean on = hover != null && (e.from() == hover || e.to() == hover);
                    boolean strong = edgeStrong(e);
                    if ((on || strong) == (pass == 1) && (!thin || strong || on) && visible(e, vx0, vy0, vx1, vy1))
                        edge(g, e, hover, detail);
                }
            if (detail)
                for (GraphLayout.Dot d : layout.dots)
                    if (d.x() >= vx0 && d.x() <= vx1 && d.y() >= vy0 && d.y() <= vy1)
                        g.fill((int) d.x() - 2, (int) d.y() - 2, (int) d.x() + 2, (int) d.y() + 2,
                                d.from() == hover ? LINE_HOT : LINE_STRONG);
            g.flush();
            for (GraphLayout.Node n : layout.all())
                if (!(n.kind == GraphLayout.Kind.LOOP && n.unfolded) && visible(n, vx0, vy0, vx1, vy1)) {
                    if (detail)
                        box(g, n);
                    else
                        block(g, n);
                }
            if (lit >= 0 && layout.common.containsKey(lit))
                commonLines(g, lit);
            if (zoom >= 0.75f)
                for (GraphLayout.Edge e : layout.edges)
                    if (visible(e, vx0, vy0, vx1, vy1))
                        edgeLabel(g, e);
            g.pose().popPose();
            if (!detail)
                landmarks(g, vx0, vy0, vx1, vy1);
            g.disableScissor();
        } else {
            Component msg = emptyText();
            g.drawString(font, msg, (width - rightPanelWidth() - font.width(msg)) / 2, height / 2, TEXT, false);
        }
        // items are drawn raised by the game (about 150 up): the overlay and the bar go above them, or the graph's
        // icons would show through the panels
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        drawOverlay(g, mx, my);
        bar(g, mx, my);
        g.pose().popPose();
        if (layout != null && layout.graph.truncated()) {
            Component t = Component.translatable("gui.twinstar.graph.truncated");
            g.drawString(font, t, 6, height - 12, DIM, false);
        }
        if (hover != null) {
            List<Component> lines = switch (hover.kind) {
                case ITEM -> itemTooltip(hover.ref);
                case RECIPE -> recipeTooltip(hover.ref);
                case LOOP -> frameTitle ? List.of(Component.translatable("gui.twinstar.graph.loop.fold").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)))
                        : loopTooltip(hover.ref, hover.inner.stream().filter(n -> n.kind == GraphLayout.Kind.ITEM).map(n -> n.ref).toList(),
                        (int) hover.inner.stream().filter(n -> n.kind == GraphLayout.Kind.RECIPE).count());
            };
            if (tag >= 0) {
                lines = new java.util.ArrayList<>(itemTooltip(tag));
                commonNote(lines, tag);
            } else if (hover.kind == GraphLayout.Kind.ITEM && layout.common.containsKey(hover.ref)) {
                lines = new java.util.ArrayList<>(lines);
                commonNote(lines, hover.ref);
            }
            g.renderComponentTooltip(font, lines, mx, my);
        }
    }

    private void commonNote(List<Component> lines, int item) {
        lines.add(Component.translatable("gui.twinstar.graph.common", layout.common.getOrDefault(item, 0)).withStyle(st -> st.withColor(FROM_STOCK & 0xFFFFFF)));
        lines.add(Component.translatable("gui.twinstar.graph.common.pin").withStyle(st -> st.withColor(DIM & 0xFFFFFF)));
    }

    /** The common material label under a point of the graph (its item), −1: none. */
    private int tagAt(double wx, double wy) {
        if (layout == null || zoom < DETAIL_ZOOM)
            return -1;
        for (GraphLayout.Node n : layout.all()) {
            if (n.tags.isEmpty() || !n.contains(wx, wy))
                continue;
            int i = (int) Math.floor((wx - n.x - 2) / GraphLayout.TAG);
            if (wx >= n.x + 2 && i < Math.min(n.tags.size(), GraphLayout.MAX_TAGS))
                return n.tags.get(i);
        }
        return -1;
    }

    /** The labels at a box's left end: the common materials it uses, as small icons. */
    private void labelsOf(GuiGraphics g, GraphLayout.Node n) {
        if (n.tags.isEmpty())
            return;
        int shown = Math.min(n.tags.size(), GraphLayout.MAX_TAGS);
        int y = n.y + (n.h - 10) / 2;
        for (int i = 0; i < shown; i++) {
            int item = n.tags.get(i), x = n.x + 2 + i * GraphLayout.TAG;
            g.fill(x, y, x + 10, y + 10, itemMissing(item) ? MISSING : FROM_STOCK);
            g.fill(x + 1, y + 1, x + 9, y + 9, item == pinned ? 0xFF2C5070 : BOX);
            g.pose().pushPose();
            g.pose().translate(x + 1, y + 1, 0);
            g.pose().scale(0.5f, 0.5f, 1);
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, 0, 0, layout.graph.items().get(item).key());
            g.pose().popPose();
        }
        if (n.tags.size() > shown)
            g.drawString(font, "+" + (n.tags.size() - shown), n.x + 2 + shown * GraphLayout.TAG, n.y + (n.h - 8) / 2, FROM_STOCK, false);
    }

    /** A common material's lines, over the boxes while asked for: up from it, along, into each box that uses it. */
    private void commonLines(GuiGraphics g, int item) {
        GraphLayout.Node from = layout.shownItem(item);
        float width = Math.max(1.4f, 1 / zoom);
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        for (GraphLayout.Node to : layout.all()) {
            int i = to.tags.indexOf(item);
            if (i < 0)
                continue;
            float x1 = from.cx(), y1 = from.y;
            float x2 = to.x + 7 + Math.min(i, GraphLayout.MAX_TAGS) * GraphLayout.TAG, y2 = to.y + to.h;
            float mid = y2 + 8;
            path(g, new float[]{x1, y1, x1, mid, x2, mid, x2, y2}, width, LINE_HOT);
            arrow(g, x2, y2 + 3, x2, y2, LINE_HOT);
        }
        g.flush();
        g.pose().popPose();
    }

    /**
     * A routed line, only its pieces on the screen. Many lines share their trunks: far away, where
     * thousands are on the screen at once, each shared piece is drawn once.
     */
    private void routed(GuiGraphics g, float[] p, float width, int color, boolean once) {
        float[] piece = new float[4];
        for (int i = 0; i + 3 < p.length; i += 2) {
            System.arraycopy(p, i, piece, 0, 4);
            if (Math.max(piece[0], piece[2]) < seenLeft || Math.min(piece[0], piece[2]) > seenRight
                    || Math.max(piece[1], piece[3]) < seenTop || Math.min(piece[1], piece[3]) > seenBottom)
                continue;
            if (once && !drawnPieces.add(31L * (31L * (31L * (31L * Math.round(piece[0]) + Math.round(piece[1]))
                    * 1_000_003L + Math.round(piece[2])) + Math.round(piece[3])) + color))
                continue;
            path(g, piece, width, color);
        }
    }

    /** Level and upright pieces, the corners filled. */
    private static void path(GuiGraphics g, float[] p, float width, int color) {
        float half = width / 2;
        for (int i = 0; i + 3 < p.length; i += 2) {
            float x1 = p[i], y1 = p[i + 1], x2 = p[i + 2], y2 = p[i + 3];
            if (x1 == x2 && y1 == y2)
                continue;
            if (y1 == y2)
                line(g, Math.min(x1, x2) - half, y1, Math.max(x1, x2) + half, y1, width, color);
            else
                line(g, x1, y1, x2, y2, width, color);
        }
    }

    private static boolean visible(GraphLayout.Node n, double x0, double y0, double x1, double y1) {
        return n.x + n.w >= x0 && n.x <= x1 && n.y + n.h >= y0 && n.y <= y1;
    }

    private boolean visible(GraphLayout.Edge e, double x0, double y0, double x1, double y1) {
        float[] p = layout.routes.get(e);
        if (p != null) {
            float l = p[0], r = p[0], t = p[1], b = p[1];
            for (int i = 2; i < p.length; i += 2) {
                l = Math.min(l, p[i]);
                r = Math.max(r, p[i]);
                t = Math.min(t, p[i + 1]);
                b = Math.max(b, p[i + 1]);
            }
            return r >= x0 && l <= x1 && b >= y0 && t <= y1;
        }
        GraphLayout.Node a = e.from(), b = e.to();
        return Math.max(a.x + a.w, b.x + b.w) >= x0 && Math.min(a.x, b.x) <= x1
                && Math.max(a.y + a.h, b.y + b.h) >= y0 && Math.min(a.y, b.y) <= y1;
    }

    /** What the mouse is on in the graph: the node and, for an unfolded loop, whether it is the frame's title. */
    private record Hovered(GraphLayout.Node node, boolean frameTitle) {
    }

    /** The graph node under the mouse (screen pixels); null over the bar, an overlay or empty space. */
    private @Nullable Hovered hoverAt(double mx, double my) {
        if (layout == null || my < BAR_H || overlayContains(mx, my))
            return null;
        GraphLayout.Node hover = null;
        double wx = worldX(mx), wy = worldY(my);
        for (GraphLayout.Node n : layout.all())
            if (n.contains(wx, wy) && (hover == null || hover.kind == GraphLayout.Kind.LOOP))
                hover = n;
        boolean frameTitle = hover != null && hover.kind == GraphLayout.Kind.LOOP && hover.unfolded
                && wy < hover.y + GraphLayout.FRAME_TITLE_H;
        if (hover != null && hover.kind == GraphLayout.Kind.LOOP && hover.unfolded && !frameTitle)
            hover = null;   // the empty inside of a frame
        GraphLayout.Node mark = landmarkAt(mx, my);
        if (mark != null) {
            hover = mark;
            frameTitle = false;
        }
        return hover == null ? null : new Hovered(hover, frameTitle);
    }

    /**
     * The material drawn under the mouse (screen pixels), for JEI's and EMI's bookmark key: an item
     * box gives its item, a recipe box its machine, a folded loop the icon the mouse is on.
     */
    public @Nullable KeyHit keyAt(double mx, double my) {
        if (layout == null || my < BAR_H)
            return null;
        if (overlayContains(mx, my))
            return overlayKeyAt(mx, my);
        Hovered h = hoverAt(mx, my);
        if (h == null)
            return null;
        GraphLayout.Node n = h.node();
        PlanGraph graph = layout.graph;
        int tag = tagAt(worldX(mx), worldY(my));
        if (tag >= 0)
            return keyHit(graph.items().get(tag).key(), n.x, n.y, n.w, n.h, mx, my);
        return switch (n.kind) {
            case ITEM -> keyHit(graph.items().get(n.ref).key(), n.x, n.y, n.w, n.h, mx, my);
            case RECIPE -> {
                AEKey machine = graph.recipes().get(n.ref).machine();
                yield machine == null ? null : keyHit(machine, n.x, n.y, n.w, n.h, mx, my);
            }
            case LOOP -> {
                if (h.frameTitle())
                    yield null;
                if (zoom < DETAIL_ZOOM) {
                    AEKey icon = loopIcon(n);
                    yield icon == null ? null : keyHit(icon, n.x, n.y, n.w, n.h, mx, my);
                }
                // the member icons of a folded loop's box, as drawn in box()
                double wx = worldX(mx), wy = worldY(my);
                int x = n.x + n.tagW + 14, shown = 0;
                for (GraphLayout.Node in : n.inner) {
                    if (in.kind != GraphLayout.Kind.ITEM)
                        continue;
                    if (shown++ >= 4)
                        break;
                    if (wx >= x && wx < x + 16 && wy >= n.y + 4 && wy < n.y + 20)
                        yield keyHit(graph.items().get(in.ref).key(), x, n.y + 4, 16, 16, mx, my);
                    x += 17;
                }
                yield null;
            }
        };
    }

    /** A material drawn in a square of the graph (world units), as a hit in screen pixels around the mouse. */
    private KeyHit keyHit(AEKey key, int wx, int wy, int ww, int wh, double mx, double my) {
        int x = Math.round(wx * zoom + panX), y = Math.round(wy * zoom + panY);
        int w = Math.max(1, Math.round(ww * zoom)), h = Math.max(1, Math.round(wh * zoom));
        if (mx < x || mx >= x + w || my < y || my >= y + h) {   // a landmark icon is drawn larger than its box
            x = (int) mx - 8;
            y = (int) my - 8;
            w = h = 16;
        }
        return new KeyHit(key, x, y, w, h);
    }

    /** The material in the part of the screen the subclass draws over the graph (a panel), or null. */
    protected @Nullable KeyHit overlayKeyAt(double mx, double my) {
        return null;
    }

    private @Nullable GraphLayout landmarksFor;
    private List<GraphLayout.Node> landmarkOrder = List.of();
    /** the icons drawn last frame and their screen squares (the mouse finds them before the tiny boxes) */
    private final List<GraphLayout.Node> drawnLandmarks = new java.util.ArrayList<>();
    private final List<int[]> drawnLandmarkRects = new java.util.ArrayList<>();

    /**
     * Far away the boxes are too small to tell: the most telling items keep a full-size icon on the
     * screen (the tops of the parts first, then the most joined), as many as fit without covering
     * each other; the tops of the parts also get their name. Like the labels of a map.
     */
    private void landmarks(GuiGraphics g, double x0, double y0, double x1, double y1) {
        if (landmarksFor != layout) {
            landmarksFor = layout;
            landmarkOrder = layout.nodes.stream()
                    .filter(n -> n.kind == GraphLayout.Kind.ITEM || n.kind == GraphLayout.Kind.LOOP && !n.unfolded)
                    .sorted(java.util.Comparator.comparing((GraphLayout.Node n) -> !n.top).thenComparing(n -> -n.degree))
                    .toList();
        }
        // taken screen cells, a coarse grid: enough to keep icons and names apart
        int cell = 6, cols = width / cell + 2, rows = height / cell + 2;
        boolean[] taken = new boolean[cols * rows];
        int shown = 0;
        drawnLandmarks.clear();
        drawnLandmarkRects.clear();
        for (GraphLayout.Node n : landmarkOrder) {
            if (shown >= LANDMARKS)
                break;
            if (!visible(n, x0, y0, x1, y1))
                continue;
            int sx = Math.round(n.cx() * zoom + panX), sy = Math.round(n.cy() * zoom + panY);
            AEKey key = n.kind == GraphLayout.Kind.ITEM ? layout.graph.items().get(n.ref).key() : loopIcon(n);
            if (key == null)
                continue;
            String name = n.top ? font.plainSubstrByWidth(AEKeyRendering.getDisplayName(key).getString(), 110) : "";
            int left = sx - LANDMARK / 2, top = sy - LANDMARK / 2;
            int right = left + LANDMARK + (name.isEmpty() ? 0 : font.width(name) + 6);
            if (!free(taken, cols, rows, cell, left, top, right, top + LANDMARK)) {
                if (name.isEmpty() || !free(taken, cols, rows, cell, left, top, left + LANDMARK, top + LANDMARK))
                    continue;
                name = "";      // room for the icon only
                right = left + LANDMARK;
            }
            take(taken, cols, rows, cell, left - 2, top - 2, right + 2, top + LANDMARK + 2);
            drawnLandmarks.add(n);
            drawnLandmarkRects.add(new int[]{left, top, right, top + LANDMARK});
            int color = n.kind == GraphLayout.Kind.LOOP ? LOOP_COLOR : itemMissing(n.ref) ? MISSING : itemFrame(n.ref);
            g.fill(left, top, right, top + LANDMARK, 0xE0100F1A);
            outline(g, left, top, LANDMARK, LANDMARK, color);
            if (n.kind == GraphLayout.Kind.ITEM && itemMissing(n.ref))
                outline(g, left + 1, top + 1, LANDMARK - 2, LANDMARK - 2, MISSING);
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, left + 1, top + 1, key);
            if (!name.isEmpty())
                g.drawString(font, name, left + LANDMARK + 3, top + 5, TEXT, false);
            shown++;
        }
    }

    private GraphLayout.@Nullable Node landmarkAt(double mx, double my) {
        if (zoom >= DETAIL_ZOOM || layout != landmarksFor)
            return null;
        for (int i = drawnLandmarks.size() - 1; i >= 0; i--) {
            int[] q = drawnLandmarkRects.get(i);
            if (mx >= q[0] && mx < q[2] && my >= q[1] && my < q[3])
                return drawnLandmarks.get(i);
        }
        return null;
    }

    private @Nullable AEKey loopIcon(GraphLayout.Node loop) {
        for (GraphLayout.Node in : loop.inner)
            if (in.kind == GraphLayout.Kind.ITEM)
                return layout.graph.items().get(in.ref).key();
        return null;
    }

    private static boolean free(boolean[] taken, int cols, int rows, int cell, int x0, int y0, int x1, int y1) {
        for (int cy = Math.max(0, y0 / cell); cy <= Math.min(rows - 1, y1 / cell); cy++)
            for (int cx = Math.max(0, x0 / cell); cx <= Math.min(cols - 1, x1 / cell); cx++)
                if (taken[cy * cols + cx])
                    return false;
        return true;
    }

    private static void take(boolean[] taken, int cols, int rows, int cell, int x0, int y0, int x1, int y1) {
        for (int cy = Math.max(0, y0 / cell); cy <= Math.min(rows - 1, y1 / cell); cy++)
            for (int cx = Math.max(0, x0 / cell); cx <= Math.min(cols - 1, x1 / cell); cx++)
                taken[cy * cols + cx] = true;
    }

    /** A box far away: its colour only (the marks as a corner dot, so missing still shows). */
    private void block(GuiGraphics g, GraphLayout.Node n) {
        int color = switch (n.kind) {
            case ITEM -> itemMissing(n.ref) ? MISSING : itemFrame(n.ref);
            case RECIPE -> recipeMissing(n.ref) ? MISSING : recipeBest(n.ref) ? BEST : RECIPE_LINE;
            case LOOP -> LOOP_COLOR;
        };
        g.fill(n.x, n.y, n.x + n.w, n.y + n.h, color & 0x00FFFFFF | 0x90000000);
        if (n.kind == GraphLayout.Kind.ITEM && itemMissing(n.ref))
            g.fill(n.x + n.w - 8, n.y - 4, n.x + n.w, n.y + 4, MISSING);
    }

    private void grid(GuiGraphics g) {
        float step = 24 * zoom;
        if (step < 8)
            return;
        float x0 = panX % step, y0 = panY % step;
        for (float x = x0; x < width; x += step)
            for (float y = y0; y < height; y += step)
                g.fill((int) x, (int) y, (int) x + 1, (int) y + 1, GRID);
    }

    private void bar(GuiGraphics g, int mx, int my) {
        g.fill(0, 0, width, BAR_H, BAR);
        g.fill(0, BAR_H - 1, width, BAR_H, BAR_LINE);
        if (my < BAR_H && mx < BAR_H)
            g.fill(2, 2, BAR_H - 2, BAR_H - 2, 0xFF2C2642);
        BackArrow.draw(g, 6, 7);
        Component title = title();
        g.drawString(font, title, BAR_H + 4, 7, 0xFFD6CCFF, false);
        Component status = barStatus();
        Component hint = status != null ? status : Component.translatable("gui.twinstar.graph.hint");
        int bx = width - BUTTON_W - 4;
        int[] style = styleButton();
        int[] own = switchButton();
        int hx = (own != null ? own[0] : style[0]) - 8 - font.width(hint);
        if (hx > BAR_H + 8 + font.width(title))
            g.drawString(font, hint, hx, 7, status != null ? barStatusColor() : DIM, false);
        boolean overFit = my < BAR_H && mx >= bx && mx < bx + BUTTON_W;
        g.fill(bx, 3, bx + BUTTON_W, BAR_H - 3, overFit ? 0xFFA082F0 : 0xFF4E4474);
        g.fill(bx + 1, 4, bx + BUTTON_W - 1, BAR_H - 4, overFit ? 0xFF2C2642 : 0xFF1E1C2C);
        Component fit = Component.translatable("gui.twinstar.graph.fit");
        g.drawString(font, fit, bx + (BUTTON_W - font.width(fit)) / 2, 7, TEXT, false);
        boolean overStyle = my < BAR_H && mx >= style[0] && mx < style[0] + style[1];
        g.fill(style[0], 3, style[0] + style[1], BAR_H - 3, overStyle ? 0xFFA082F0 : 0xFF4E4474);
        g.fill(style[0] + 1, 4, style[0] + style[1] - 1, BAR_H - 4, overStyle ? 0xFF2C2642 : 0xFF1E1C2C);
        g.drawString(font, GraphStyle.name(GraphStyle.get()), style[0] + 6, 7, TEXT, false);
        if (own != null) {
            boolean over = my < BAR_H && mx >= own[0] && mx < own[0] + own[1];
            g.fill(own[0], 3, own[0] + own[1], BAR_H - 3, over ? 0xFFA082F0 : MISSING);
            g.fill(own[0] + 1, 4, own[0] + own[1] - 1, BAR_H - 4, over ? 0xFF2C2642 : 0xFF1E1C2C);
            g.drawString(font, barSwitch(), own[0] + 6, 7, TEXT, false);
        }
    }

    private void frame(GuiGraphics g, GraphLayout.Node n) {
        g.fill(n.x, n.y, n.x + n.w, n.y + n.h, FRAME);
        outline(g, n.x, n.y, n.w, n.h, LOOP_COLOR);
        g.fill(n.x + 1, n.y + 1, n.x + n.w - 1, n.y + GraphLayout.FRAME_TITLE_H, 0xFF2A1C40);
        PixelIcon.draw(g, PixelIcon.LOOP, n.x + 3, n.y + 3, LOOP_COLOR);
        g.drawString(font, Component.translatable("gui.twinstar.graph.loop.title"), n.x + 13, n.y + 3, LOOP_COLOR, false);
    }

    private void box(GuiGraphics g, GraphLayout.Node n) {
        PlanGraph graph = layout.graph;
        switch (n.kind) {
            case ITEM -> {
                PlanGraph.Item it = graph.items().get(n.ref);
                boolean missing = itemMissing(n.ref);
                g.fill(n.x, n.y, n.x + n.w, n.y + n.h, BOX);
                outline(g, n.x, n.y, n.w, n.h, missing ? MISSING : itemFrame(n.ref));
                if (missing)
                    outline(g, n.x + 1, n.y + 1, n.w - 2, n.h - 2, MISSING);
                if (n.ref == 0 && layout.target != null)
                    outline(g, n.x - 1, n.y - 1, n.w + 2, n.h + 2, BEST);
                AEKeyRendering.drawInGui(Minecraft.getInstance(), g, n.x + 3, n.y + 3, it.key());
                String text = itemText(n.ref);
                if (!text.isEmpty())
                    g.drawString(font, text, n.x + 22, n.y + 7, missing ? MISSING : itemTextColor(n.ref), false);
                if (layout.common.containsKey(n.ref)) {
                    // no lines go up from it: a small hollow square says where they would start
                    int cx = n.x + n.w / 2;
                    outline(g, cx - 3, n.y - 6, 7, 7, FROM_STOCK);
                }
                tabs(g, n, itemTabs(n.ref));
            }
            case RECIPE -> {
                PlanGraph.Recipe rc = graph.recipes().get(n.ref);
                boolean missing = recipeMissing(n.ref), best = recipeBest(n.ref);
                g.fill(n.x, n.y, n.x + n.w, n.y + n.h, RECIPE_BOX);
                outline(g, n.x, n.y, n.w, n.h, missing ? MISSING : best ? BEST : RECIPE_LINE);
                if (missing || best)
                    outline(g, n.x + 1, n.y + 1, n.w - 2, n.h - 2, missing ? MISSING : BEST);
                labelsOf(g, n);
                int tx = n.x + n.tagW + 4;
                if (rc.machine() != null) {
                    g.pose().pushPose();
                    g.pose().translate(n.x + n.tagW + 3, n.y + 3, 0);
                    g.pose().scale(0.5f, 0.5f, 1);
                    AEKeyRendering.drawInGui(Minecraft.getInstance(), g, 0, 0, rc.machine());
                    g.pose().popPose();
                    tx = n.x + n.tagW + 13;
                }
                g.drawString(font, recipeText(n.ref), tx, n.y + 3, recipeTextColor(n.ref), false);
                if (missing)
                    tabs(g, n, List.of(Tab.MISSING));
            }
            case LOOP -> {
                g.fill(n.x, n.y, n.x + n.w, n.y + n.h, LOOP_BOX);
                outline(g, n.x, n.y, n.w, n.h, LOOP_COLOR);
                labelsOf(g, n);
                PixelIcon.draw(g, PixelIcon.LOOP, n.x + n.tagW + 4, n.y + (n.h - 7) / 2, LOOP_COLOR);
                int x = n.x + n.tagW + 14, shownIcons = 0, items = 0;
                for (GraphLayout.Node in : n.inner)
                    if (in.kind == GraphLayout.Kind.ITEM) {
                        items++;
                        if (shownIcons < 4) {
                            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, n.y + 4, graph.items().get(in.ref).key());
                            x += 17;
                            shownIcons++;
                        }
                    }
                if (items > shownIcons)
                    g.drawString(font, "+" + (items - shownIcons), x + 1, n.y + 8, LOOP_COLOR, false);
            }
        }
    }

    /** The marks on the top right corner, right to left, above the item icons. */
    private static void tabs(GuiGraphics g, GraphLayout.Node n, List<Tab> tabs) {
        if (tabs.isEmpty())
            return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        int x = n.x + n.w - 3;
        for (Tab t : tabs) {
            switch (t) {
                case MISSING -> {
                    x -= 7;
                    g.fill(x, n.y - 4, x + 7, n.y + 6, MISSING);
                    PixelIcon.draw(g, PixelIcon.EXCLAIM, x + 2, n.y - 3, 0xFF000000);
                }
                case INFINITE -> {
                    x -= 13;
                    g.fill(x, n.y - 4, x + 13, n.y + 3, FROM_STOCK);
                    g.fill(x + 1, n.y - 3, x + 12, n.y + 2, BOX);
                    PixelIcon.draw(g, PixelIcon.INFINITE, x + 2, n.y - 3, FROM_STOCK);
                }
                case OPEN, CLOSE, LOADING -> {
                    x -= 9;
                    g.fill(x, n.y - 4, x + 9, n.y + 5, BEST);
                    g.fill(x + 1, n.y - 3, x + 8, n.y + 4, BOX);
                    PixelIcon.draw(g, t == Tab.OPEN ? PixelIcon.PLUS : t == Tab.CLOSE ? PixelIcon.MINUS : PixelIcon.DOTS,
                            x + 2, n.y - 2, BEST);
                }
            }
            x -= 1;
        }
        g.pose().popPose();
    }

    protected static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A line from a material up to what uses it (or a recipe up to what it makes), with an arrow head. */
    private void edge(GuiGraphics g, GraphLayout.Edge e, @Nullable GraphLayout.Node hover, boolean detail) {
        GraphLayout.Node a = e.from(), b = e.to();
        boolean hot = hover != null && (hover == a || hover == b);
        boolean missing = a.kind == GraphLayout.Kind.ITEM && itemMissing(a.ref) || b.kind == GraphLayout.Kind.ITEM && itemMissing(b.ref);
        boolean strong = edgeStrong(e);
        int color = hot ? LINE_HOT : missing ? MISSING_LINE : strong ? LINE_STRONG : LINE;
        float width = (strong ? 2.2f : 1f) * (hot ? 1.4f : 1f);
        float[] route = layout.routes.get(e);
        if (route != null) {
            routed(g, route, detail ? width : Math.max(width, 1 / zoom), color, !detail && !hot);
            if (detail) {
                int n = route.length;
                float x = route[n - 2], y = route[n - 1];
                arrow(g, x, y + (route[n - 3] > y ? 3 : -3), x, y, color);
            }
            return;
        }
        if (!detail) {
            // far away: straight lines, no heads; at least a pixel wide on the screen
            float x1 = a.cx(), y1 = a.cy(), x2 = b.cx(), y2 = b.cy();
            line(g, x1, y1, x2, y2, Math.max(width, 1 / zoom), color);
            return;
        }
        if (e.inner()) {
            // inside a loop's ring: straight, centre to centre, cut at the boxes
            float[] p = clip(b, a.cx(), a.cy()), q = clip(a, b.cx(), b.cy());
            line(g, q[0], q[1], p[0], p[1], width, LOOP_COLOR & 0x00FFFFFF | (hot ? 0xFF000000 : 0xC0000000));
            arrow(g, q[0], q[1], p[0], p[1], LOOP_COLOR);
            return;
        }
        // between layers: from the material's top to the user's bottom, a soft S curve
        float x1 = a.cx(), y1 = a.y, x2 = b.cx(), y2 = b.y + b.h;
        if (y1 < y2) {      // a leftover beside or above what made it
            y1 = a.y + a.h;
            y2 = b.y;
        }
        float px = x1, py = y1;
        int steps = 14;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float u = 1 - t;
            float my = (y1 + y2) / 2;
            // cubic Bézier with vertical ends
            float x = u * u * u * x1 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x2;
            float y = u * u * u * y1 + 3 * u * u * t * my + 3 * u * t * t * my + t * t * t * y2;
            line(g, px, py, x, y, width, color);
            px = x;
            py = y;
        }
        arrow(g, x2, y2 + (y2 > y1 ? -3 : 3), x2, y2, color);
    }

    private void edgeLabel(GuiGraphics g, GraphLayout.Edge e) {
        if (e.amount().isEmpty() || e.from().kind != GraphLayout.Kind.ITEM)
            return;     // inputs only: an output's amount is on the item already
        AEKey key = layout.graph.items().get(e.from().ref).key();
        String text = PlanUI.shortText(e.amount(), key);
        float x, y;
        float[] route = layout.routes.get(e);
        if (route != null) {
            x = route[route.length - 2];
            // lines arriving side by side: their amounts at two heights
            y = route[route.length - 1] + 3 + (Math.floorMod((int) (x - e.to().x) / 7, 2)) * 6;
        } else if (e.inner()) {
            x = (e.from().cx() + e.to().cx()) / 2;
            y = (e.from().cy() + e.to().cy()) / 2;
        } else {
            x = e.to().cx();
            y = e.to().y + e.to().h + 4;
            x += (e.from().cx() - x) * 0.18f;   // a little towards the material: labels of one recipe part
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.6f, 0.6f, 1);
        int w = font.width(text);
        g.fill(-w / 2 - 1, -1, w / 2 + 1, 8, 0xC007070C);
        g.drawString(font, text, -w / 2, 0, e.kind() == 0 ? 0xFFB8B4D0 : DIM, false);
        g.pose().popPose();
    }

    /** Where the line from (px, py) to the node's centre meets the node's border. */
    private static float[] clip(GraphLayout.Node n, float px, float py) {
        float cx = n.cx(), cy = n.cy(), dx = px - cx, dy = py - cy;
        if (dx == 0 && dy == 0)
            return new float[]{cx, cy};
        float sx = dx == 0 ? Float.MAX_VALUE : (n.w / 2f + 2) / Math.abs(dx);
        float sy = dy == 0 ? Float.MAX_VALUE : (n.h / 2f + 2) / Math.abs(dy);
        float s = Math.min(1, Math.min(sx, sy));
        return new float[]{cx + dx * s, cy + dy * s};
    }

    private static void arrow(GuiGraphics g, float x1, float y1, float x2, float y2, int color) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len == 0)
            return;
        dx /= len;
        dy /= len;
        float bx = x2 - dx * 4, by = y2 - dy * 4;
        line(g, x2, y2, bx - dy * 2.5f, by + dx * 2.5f, 1, color);
        line(g, x2, y2, bx + dy * 2.5f, by - dx * 2.5f, 1, color);
    }

    /** A straight line of any slope, as a thin quad (both windings: the GUI may cull one). */
    private static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float width, int color) {
        float dx = x2 - x1, dy = y2 - y1, len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.01f)
            return;
        float nx = -dy / len * width / 2, ny = dx / len * width / 2;
        Matrix4f m = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        vc.vertex(m, x1 + nx, y1 + ny, 0).color(color).endVertex();
        vc.vertex(m, x1 - nx, y1 - ny, 0).color(color).endVertex();
        vc.vertex(m, x2 - nx, y2 - ny, 0).color(color).endVertex();
        vc.vertex(m, x2 + nx, y2 + ny, 0).color(color).endVertex();
        vc.vertex(m, x1 + nx, y1 + ny, 0).color(color).endVertex();
        vc.vertex(m, x2 + nx, y2 + ny, 0).color(color).endVertex();
        vc.vertex(m, x2 - nx, y2 - ny, 0).color(color).endVertex();
        vc.vertex(m, x1 - nx, y1 - ny, 0).color(color).endVertex();
    }

    // ── shared tooltip pieces ──

    protected static @Nullable String inputKind(int kind) {
        return switch (kind) {      // InputKind: CONSUME, CATALYST, TOOL, CONTAINER
            case 1 -> "catalyst";
            case 2 -> "tool";
            case 3 -> "container";
            default -> null;
        };
    }
}
