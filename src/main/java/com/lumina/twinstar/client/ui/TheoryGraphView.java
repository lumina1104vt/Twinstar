package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.order.PlanGraph;
import com.lumina.twinstar.order.PlanGraphBuilder;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHits;
import com.lumina.twinstar.theory.TheoryCache;
import com.lumina.twinstar.theory.TheoryItem;
import com.lumina.twinstar.theory.TheoryMap;
import com.lumina.twinstar.util.BigAmountFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import static com.lumina.twinstar.client.ui.PlanUI.CRAFT;
import static com.lumina.twinstar.client.ui.PlanUI.DIM;
import static com.lumina.twinstar.client.ui.PlanUI.LOOP_COLOR;
import static com.lumina.twinstar.client.ui.PlanUI.TEXT;

/**
 * The theory viewer: the engine's whole theory layer at once, live. Every item and every pattern;
 * parts not joined to each other side by side; the engine's recommended route of each item drawn
 * with thick lines and a bright double frame, the other routes with how many times its cost they
 * are. A search shows only the found item's part (it and everything that goes into it, down to raw
 * materials); "all" goes back. The side panel tells the clicked item's level, cost, routes in the
 * engine's order, raw materials and loop. It is a prediction: a real order also looks at stock,
 * marks and missing machines.
 */
public class TheoryGraphView extends GraphView {
    private static final int PANEL_W = 172, SEARCH_W = 160, RESULT_H = 18, RESULTS = 10, ALL_W = 36;
    private static final int BUTTON = 0xFF1E1C2C, BUTTON_LINE = 0xFF4E4474, BUTTON_HOT = 0xFFA082F0;

    private final EditBox search;
    /** Java memory and speed of the theory layer (bottom left; a click on its header folds it) */
    private static final PerfPanel PERF_PANEL = new PerfPanel(true, "theory");
    private final PerfPanel perfPanel = PERF_PANEL;
    private @Nullable TheoryMap shownMap;
    /** only this item's part; null = everything */
    private @Nullable AEKey focus;
    private @Nullable AEKey shownFocus;
    private @Nullable AEKey selected;

    // the graph shown: graph index → map index
    private int[] itemOf = new int[0], recipeOf = new int[0];
    /** per map item: the patterns making it */
    private int[][] producers = new int[0][];

    private List<Integer> results = List.of();
    private String resultsFor = "";
    private int panelScroll, panelHeight;
    /** the side panel's "only its part" button, where it was drawn */
    private int partX, partY, partW;

    public TheoryGraphView(@Nullable AEKey start, EditBox search, Runnable back) {
        super(back);
        this.search = search;
        search.setX(6);
        search.setY(BAR_H + 6);
        search.setWidth(SEARCH_W);
        search.setHint(Component.translatable("gui.twinstar.theory.search").withColor(DIM));
        this.focus = start;
        this.selected = start;
    }

    public void tick() {
        TheoryMap map = TheoryCache.map();
        if (map != shownMap || focus != shownFocus)
            rebuild(map);
        String text = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (!text.equals(resultsFor) || map != shownMap) {
            resultsFor = text;
            List<Integer> found = new ArrayList<>();
            if (map != null && !text.isEmpty())
                for (int i = 0; i < map.items().size() && found.size() < RESULTS; i++) {
                    AEKey k = map.items().get(i).key();
                    if (AEKeyRendering.getDisplayName(k).getString().toLowerCase(Locale.ROOT).contains(text)
                            || k.getId().toString().contains(text))
                        found.add(i);
                }
            results = found;
        }
    }

    private void showPart(@Nullable AEKey key) {
        focus = key;
        if (key != null)
            selected = key;
        panelScroll = 0;
        tick();
    }

    // ── the graph from the map ──

    private void rebuild(@Nullable TheoryMap map) {
        boolean sameGraph = shownMap != null && focus == shownFocus;    // a newer version of the same view
        shownMap = map;
        shownFocus = focus;
        if (map == null) {
            setGraph(null, false);
            return;
        }
        int n = map.items().size(), m = map.patterns().size();
        int[] count = new int[n];
        for (TheoryMap.Pattern p : map.patterns())
            for (TheoryMap.Out o : p.outputs())
                count[o.item()]++;
        producers = new int[n][];
        for (int i = 0; i < n; i++)
            producers[i] = new int[count[i]];
        Arrays.fill(count, 0);
        for (int p = 0; p < m; p++)
            for (TheoryMap.Out o : map.patterns().get(p).outputs())
                producers[o.item()][count[o.item()]++] = p;

        // which items and patterns: all, or the found item and everything that goes into it
        int[] itemAt = new int[n];
        Arrays.fill(itemAt, -1);
        List<Integer> items = new ArrayList<>(), patterns = new ArrayList<>();
        int start = focus == null ? -1 : indexOf(map, focus);
        if (focus != null && start < 0) {
            setGraph(null, false);
            return;
        }
        if (start < 0) {
            for (int i = 0; i < n; i++) {
                itemAt[i] = i;
                items.add(i);
            }
            for (int p = 0; p < m; p++)
                patterns.add(p);
        } else {
            boolean[] patternIn = new boolean[m];
            Deque<Integer> queue = new ArrayDeque<>();
            itemAt[start] = 0;
            items.add(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                int x = queue.poll();
                for (int p : producers[x]) {
                    if (patternIn[p])
                        continue;
                    patternIn[p] = true;
                    patterns.add(p);
                    for (TheoryMap.In in : map.patterns().get(p).inputs())
                        if (itemAt[in.item()] < 0) {
                            itemAt[in.item()] = items.size();
                            items.add(in.item());
                            queue.add(in.item());
                        }
                }
            }
        }
        itemOf = items.stream().mapToInt(Integer::intValue).toArray();
        recipeOf = patterns.stream().mapToInt(Integer::intValue).toArray();

        List<List<PlanGraph.Flow>> ins = new ArrayList<>(), outs = new ArrayList<>();
        List<List<Integer>> materials = new ArrayList<>();
        for (int i = 0; i < itemOf.length; i++)
            materials.add(new ArrayList<>());
        for (int p : recipeOf) {
            TheoryMap.Pattern pat = map.patterns().get(p);
            List<PlanGraph.Flow> in = new ArrayList<>(), out = new ArrayList<>();
            for (TheoryMap.In f : pat.inputs())
                if (itemAt[f.item()] >= 0)
                    in.add(new PlanGraph.Flow(itemAt[f.item()], f.amount(), f.kind()));
            for (TheoryMap.Out f : pat.outputs())
                if (itemAt[f.item()] >= 0)
                    out.add(new PlanGraph.Flow(itemAt[f.item()], f.amount(), f.byproduct() ? 1 : 0));
            ins.add(in);
            outs.add(out);
            for (PlanGraph.Flow o : out)
                for (PlanGraph.Flow f : in)
                    materials.get(o.item()).add(f.item());
        }
        int[] loopOf = PlanGraphBuilder.loops(materials);
        int loopCount = Arrays.stream(loopOf).max().orElse(-1) + 1;

        List<PlanGraph.Item> gItems = new ArrayList<>(itemOf.length);
        for (int i = 0; i < itemOf.length; i++) {
            TheoryMap.Item it = map.items().get(itemOf[i]);
            gItems.add(new PlanGraph.Item(it.key(), "", "", "", it.is(TheoryMap.INFINITE), "", "", loopOf[i]));
        }
        List<PlanGraph.Recipe> gRecipes = new ArrayList<>(recipeOf.length);
        for (int r = 0; r < recipeOf.length; r++) {
            int loop = -1;
            for (PlanGraph.Flow o : outs.get(r))
                for (PlanGraph.Flow f : ins.get(r))
                    if (loop < 0 && loopOf[o.item()] >= 0 && loopOf[o.item()] == loopOf[f.item()])
                        loop = loopOf[o.item()];
            gRecipes.add(new PlanGraph.Recipe("", map.patterns().get(recipeOf[r]).machine(), false, loop, ins.get(r), outs.get(r)));
        }
        List<PlanGraph.Loop> loops = new ArrayList<>(loopCount);
        for (int l = 0; l < loopCount; l++)
            loops.add(new PlanGraph.Loop(List.of()));
        setGraph(new PlanGraph(gItems, gRecipes, loops, false), sameGraph);
    }

    private static int indexOf(TheoryMap map, AEKey key) {
        for (int i = 0; i < map.items().size(); i++)
            if (map.items().get(i).key().equals(key))
                return i;
        return -1;
    }

    private TheoryMap.Item mapItem(int item) {
        return shownMap.items().get(itemOf[item]);
    }

    private TheoryMap.Pattern pattern(int recipe) {
        return shownMap.patterns().get(recipeOf[recipe]);
    }

    /** The output a recipe is shown for: one it is the engine's route of, else its first main output. */
    private TheoryMap.@Nullable Out mainOut(int recipe) {
        TheoryMap.Out first = null;
        for (TheoryMap.Out o : pattern(recipe).outputs()) {
            if (o.byproduct())
                continue;
            if (shownMap.items().get(o.item()).best() == recipeOf[recipe])
                return o;
            if (first == null)
                first = o;
        }
        return first;
    }

    private boolean isBest(int recipe) {
        for (TheoryMap.Out o : pattern(recipe).outputs())
            if (shownMap.items().get(o.item()).best() == recipeOf[recipe])
                return true;
        return false;
    }

    // ── boxes ──

    @Override
    protected boolean oneTarget() {
        return focus != null;
    }

    @Override
    protected Component title() {
        MutableComponent title = Component.translatable("gui.twinstar.theory.title").withStyle(s -> s.withBold(true));
        if (focus != null)
            title.append(Component.literal("  ").append(AEKeyRendering.getDisplayName(focus)).withStyle(s -> s.withBold(false)));
        return title;
    }

    @Override
    protected Component emptyText() {
        if (shownMap != null && focus != null)
            return Component.translatable("gui.twinstar.theory.unknown");
        if (TheoryCache.status().version() < 0)
            return Component.translatable("gui.twinstar.theory.status.none");
        return TheoryCache.partsTotal() > 0
                ? Component.translatable("gui.twinstar.theory.loading_parts", TheoryCache.partsIn(), TheoryCache.partsTotal())
                : Component.translatable("gui.twinstar.theory.loading");
    }

    @Override
    protected @Nullable Component barStatus() {
        TwinStarService.TheoryStatus s = TheoryCache.status();
        if (s.version() < 0)
            return Component.translatable("gui.twinstar.theory.status.none");
        if (s.upToDate())
            return Component.translatable("gui.twinstar.theory.status.synced", s.items(), s.patterns(), s.loops());
        return Component.translatable("gui.twinstar.theory.status.busy", s.pendingBatches(), s.queued());
    }

    @Override
    protected int barStatusColor() {
        TwinStarService.TheoryStatus s = TheoryCache.status();
        return s.version() >= 0 && s.upToDate() ? FROM_STOCK : MISSING;
    }

    @Override
    protected String itemText(int item) {
        return "";
    }

    @Override
    protected int itemTextColor(int item) {
        return TEXT;
    }

    @Override
    protected int itemFrame(int item) {
        TheoryMap.Item it = mapItem(item);
        return it.is(TheoryMap.RAW) || it.is(TheoryMap.MINED) ? FROM_STOCK : MADE;
    }

    @Override
    protected List<Tab> itemTabs(int item) {
        TheoryMap.Item it = mapItem(item);
        List<Tab> tabs = new ArrayList<>(2);
        if (it.is(TheoryMap.CANNOT))
            tabs.add(Tab.MISSING);
        if (it.is(TheoryMap.INFINITE))
            tabs.add(Tab.INFINITE);
        return tabs;
    }

    /** How many times the item's own cost this route's is (×1 for its best); "—" when it cannot run. */
    private String ratio(int recipe) {
        TheoryMap.Out o = mainOut(recipe);
        if (o == null || Double.isInfinite(o.logCost()))
            return "—";
        double log = o.logCost() - shownMap.items().get(o.item()).logCost();
        return Double.isNaN(log) ? "—" : "×" + logText(Math.max(0, log));
    }

    @Override
    protected String recipeText(int recipe) {
        return ratio(recipe);
    }

    @Override
    protected int recipeTextColor(int recipe) {
        if (isBest(recipe))
            return BEST;
        TheoryMap.Out o = mainOut(recipe);
        return o == null || Double.isInfinite(o.logCost()) ? DIM : CRAFT;
    }

    @Override
    protected boolean recipeBest(int recipe) {
        return isBest(recipe);
    }

    /** The recommended route: into and out of a pattern that is some item's best, out to that item. */
    @Override
    protected boolean edgeStrong(GraphLayout.Edge edge) {
        int p = recipeOf[edge.recipe()];
        if (edge.output())
            return shownMap.items().get(itemOf[edge.item()]).best() == p;
        return isBest(edge.recipe());
    }

    @Override
    protected void itemClicked(int item) {
        selected = mapItem(item).key();
        panelScroll = 0;
    }

    // ── tooltips ──

    @Override
    protected List<Component> itemTooltip(int item) {
        TheoryMap.Item it = mapItem(item);
        List<Component> lines = new ArrayList<>();
        lines.add(AEKeyRendering.getDisplayName(it.key()));
        lines.add(Component.translatable("gui.twinstar.theory.level", it.level()).withColor(DIM));
        if (it.is(TheoryMap.CANNOT))
            lines.add(Component.translatable("gui.twinstar.theory.cannot").withColor(MISSING));
        else
            lines.add(Component.translatable("gui.twinstar.theory.cost", logText(it.logCost())).withColor(DIM));
        if (it.is(TheoryMap.RAW))
            lines.add(Component.translatable("gui.twinstar.theory.raw").withColor(FROM_STOCK));
        if (it.is(TheoryMap.MINED))
            lines.add(Component.translatable("gui.twinstar.theory.mined").withColor(FROM_STOCK));
        if (it.is(TheoryMap.INFINITE))
            lines.add(Component.translatable("gui.twinstar.plan.infinite").withColor(FROM_STOCK));
        if (producers[itemOf[item]].length > 0)
            lines.add(Component.translatable("gui.twinstar.theory.routes", producers[itemOf[item]].length).withColor(CRAFT));
        if (it.loop() >= 0)
            lines.add(Component.translatable("gui.twinstar.theory.in_engine_loop").withColor(LOOP_COLOR));
        lines.add(Component.translatable("gui.twinstar.theory.click_select").withColor(DIM));
        return lines;
    }

    @Override
    protected List<Component> recipeTooltip(int recipe) {
        TheoryMap.Pattern pat = pattern(recipe);
        List<Component> lines = new ArrayList<>();
        TheoryMap.Out main = mainOut(recipe);
        if (main != null)
            lines.add(Component.translatable("gui.twinstar.theory.makes", AEKeyRendering.getDisplayName(shownMap.items().get(main.item()).key()))
                    .withColor(CRAFT));
        if (isBest(recipe))
            lines.add(Component.translatable("gui.twinstar.theory.best").withColor(BEST));
        else if (main == null || Double.isInfinite(main.logCost()))
            lines.add(Component.translatable("gui.twinstar.theory.route_cannot").withColor(MISSING));
        else
            lines.add(Component.translatable("gui.twinstar.theory.ratio", ratio(recipe)).withColor(DIM));
        if (pat.machine() != null)
            lines.add(Component.translatable("gui.twinstar.graph.machine", AEKeyRendering.getDisplayName(pat.machine())).withColor(DIM));
        lines.add(Component.translatable("gui.twinstar.theory.per_run").withColor(DIM));
        lines.add(Component.translatable("gui.twinstar.graph.inputs").withColor(DIM));
        for (TheoryMap.In in : pat.inputs()) {
            TheoryMap.Item m = shownMap.items().get(in.item());
            MutableComponent line = stackLine(m.key(), in.amount(), inputKind(in.kind()));
            // why a route cannot run: the material the theory finds no cost for
            if (m.is(TheoryMap.CANNOT) && in.kind() != 1 && in.kind() != 3)
                line.append(Component.literal(" ").append(Component.translatable("gui.twinstar.theory.no_cost")).withColor(MISSING));
            lines.add(line);
        }
        lines.add(Component.translatable("gui.twinstar.graph.outputs").withColor(DIM));
        for (TheoryMap.Out o : pat.outputs())
            lines.add(stackLine(shownMap.items().get(o.item()).key(), o.amount(), o.byproduct() ? "byproduct" : null));
        return lines;
    }

    @Override
    protected List<Component> loopTooltip(int loop, List<Integer> items, int recipes) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.twinstar.graph.loop", items.size(), recipes).withColor(LOOP_COLOR));
        for (int i : items)
            lines.add(Component.literal("  ").append(AEKeyRendering.getDisplayName(mapItem(i).key())));
        lines.add(Component.translatable("gui.twinstar.graph.loop.unfold").withColor(DIM));
        return lines;
    }

    private static MutableComponent stackLine(AEKey key, String amount, @Nullable String note) {
        MutableComponent line = Component.literal("  ").append(AEKeyRendering.getDisplayName(key))
                .append(" ×" + PlanUI.fullText(amount, key));
        if (note != null)
            line.append(Component.literal(" ").append(Component.translatable("gui.twinstar.graph." + note)).withColor(DIM));
        return line;
    }

    /** 10^log as short text: 0.25, 3, 1.2K, …, ∞. */
    static String logText(double log) {
        if (Double.isNaN(log))
            return "?";
        if (Double.isInfinite(log))
            return log > 0 ? "∞" : "0";
        if (log < 4) {
            double v = Math.pow(10, log);
            if (Math.abs(v - Math.rint(v)) < 1e-6)
                return String.valueOf((long) Math.rint(v));
            return String.format(Locale.ROOT, v < 1 ? "%.3g" : "%.2f", v).replaceAll("\\.?0+$", "");
        }
        int exp = (int) Math.floor(log);
        BigInteger v = BigDecimal.valueOf(Math.pow(10, log - exp)).scaleByPowerOfTen(exp).toBigInteger();
        return BigAmountFormat.unitForm(v);
    }

    // ── search, "all" and the side panel ──

    @Override
    protected int rightPanelWidth() {
        return PANEL_W;
    }

    private int allX() {
        return search.getX() + search.getWidth() + 6;
    }

    private boolean onAll(double mx, double my) {
        return focus != null && mx >= allX() && mx < allX() + ALL_W && my >= search.getY() - 3 && my < search.getY() + search.getHeight() - 1;
    }

    private boolean onSearch(double mx, double my) {
        return mx >= search.getX() - 3 && mx < search.getX() + search.getWidth() + 1 && my >= search.getY() - 3
                && my < search.getY() + search.getHeight() - 1;
    }

    private boolean inResults(double mx, double my) {
        return search.isFocused() && !results.isEmpty() && mx >= 6 && mx < 6 + SEARCH_W + 60
                && my >= search.getY() + search.getHeight() && my < search.getY() + search.getHeight() + 2 + results.size() * RESULT_H;
    }

    @Override
    protected boolean overlayContains(double mx, double my) {
        return mx >= width - PANEL_W && my >= BAR_H || inResults(mx, my) || onSearch(mx, my) || onAll(mx, my) || perfPanel.contains(mx, my);
    }

    @Override
    protected boolean overlayDragged(double dx, double dy) {
        return perfPanel.mouseDragged(dx, dy);
    }

    @Override
    protected boolean overlayReleased(double mx, double my, int button) {
        return perfPanel.mouseReleased(mx, my, button);
    }

    @Override
    protected boolean overlayClicked(double mx, double my, int button) {
        if (perfPanel.mousePressed(mx, my, button))
            return true;
        if (inResults(mx, my)) {
            int i = (int) ((my - search.getY() - search.getHeight() - 2) / RESULT_H);
            if (i >= 0 && i < results.size() && shownMap != null) {
                search.setValue("");
                search.setFocused(false);
                showPart(shownMap.items().get(results.get(i)).key());
            }
            return true;
        }
        if (onSearch(mx, my)) {
            search.setFocused(true);
            return search.mouseClicked(mx, my, button);
        }
        if (onAll(mx, my)) {
            showPart(null);
            return true;
        }
        if (partW > 0 && mx >= partX && mx < partX + partW && my >= partY && my < partY + 14 && selected != null) {
            showPart(selected);
            return true;
        }
        search.setFocused(false);
        return true;
    }

    @Override
    protected boolean overlayScrolled(double mx, double my, double amount) {
        if (mx >= width - PANEL_W) {
            int max = Math.max(0, panelHeight - (height - BAR_H - 8));
            panelScroll = Math.max(0, Math.min(max, panelScroll - (int) (amount * 18)));
        }
        return true;
    }

    /** Clicks outside the search box take its focus away (the screen asks). */
    public void blurSearch(double mx, double my) {
        if (!overlayContains(mx, my))
            search.setFocused(false);
    }

    /** the materials drawn in the panel and the search results this frame */
    private final KeyHits overlayHits = new KeyHits();

    @Override
    protected @Nullable KeyHit overlayKeyAt(double mx, double my) {
        return overlayHits.at(mx, my);
    }

    @Override
    protected void drawOverlay(GuiGraphics g, int mx, int my) {
        overlayHits.clear();
        panel(g, mx, my);
        int x = search.getX(), y = search.getY();
        g.fill(x - 3, y - 3, x + search.getWidth() + 1, y + search.getHeight() - 1, search.isFocused() ? BUTTON_HOT : PANEL_LINE);
        g.fill(x - 2, y - 2, x + search.getWidth(), y + search.getHeight() - 2, 0xFF040408);
        if (focus != null)
            button(g, allX(), y - 3, ALL_W, Component.translatable("gui.twinstar.theory.all"), onAll(mx, my));
        if (search.isFocused() && !results.isEmpty() && shownMap != null) {
            int top = y + search.getHeight() + 2, w = SEARCH_W + 60;
            g.fill(x - 3, top - 1, x - 3 + w, top + results.size() * RESULT_H + 1, PANEL_LINE);
            g.fill(x - 2, top, x - 4 + w, top + results.size() * RESULT_H, PANEL);
            for (int i = 0; i < results.size(); i++) {
                int ry = top + i * RESULT_H;
                if (mx >= x - 2 && mx < x - 4 + w && my >= ry && my < ry + RESULT_H)
                    g.fill(x - 2, ry, x - 4 + w, ry + RESULT_H, 0xFF2C2642);
                AEKey key = shownMap.items().get(results.get(i)).key();
                overlayHits.add(key, x - 2, ry, w - 2, RESULT_H);
                AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, ry + 1, key);
                g.drawString(font, font.plainSubstrByWidth(AEKeyRendering.getDisplayName(key).getString(), w - 26), x + 19, ry + 5, TEXT, false);
            }
        }
        g.drawString(font, Component.translatable("gui.twinstar.theory.prediction"), 6, height - 12, DIM, false);
        PerfContent.Content perf = PerfContent.theory(TheoryCache.status().report());
        perfPanel.draw(g, font, 6, height - 16 - perfPanel.openHeightFor(perf), width, height, perf, mx, my);
        List<Component> tip = perfPanel.isPressed() ? null : perfPanel.tooltipAt(mx, my);
        if (tip != null)
            g.renderComponentTooltip(font, tip, mx, my);
    }

    private void button(GuiGraphics g, int x, int y, int w, Component text, boolean hot) {
        g.fill(x, y, x + w, y + 14, hot ? BUTTON_HOT : BUTTON_LINE);
        g.fill(x + 1, y + 1, x + w - 1, y + 13, hot ? 0xFF2C2642 : BUTTON);
        g.drawString(font, text, x + (w - font.width(text)) / 2, y + 3, TEXT, false);
    }

    private void panel(GuiGraphics g, int mx, int my) {
        int px = width - PANEL_W, top = BAR_H;
        g.fill(px, top, width, height, PANEL);
        g.fill(px, top, px + 1, height, PANEL_LINE);
        partW = 0;
        AEKey key = selected;
        if (key == null) {
            wrapped(g, Component.translatable("gui.twinstar.theory.pick"), px + 6, top + 6, PANEL_W - 12, DIM);
            return;
        }
        g.enableScissor(px + 1, top, width, height);
        int x = px + 6, y = top + 6 - panelScroll, w = PANEL_W - 12;
        overlayHits.add(key, x, y, w, 18);
        AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y, key);
        int nameEnd = wrapped(g, AEKeyRendering.getDisplayName(key).copy().withStyle(s -> s.withBold(true)), x + 19, y + 4, w - 19, TEXT);
        y = Math.max(nameEnd, y + 18) + 3;
        if (!key.equals(focus)) {
            Component part = Component.translatable("gui.twinstar.theory.show_part");
            partX = x;
            partY = y;
            partW = Math.min(w, font.width(part) + 10);
            button(g, partX, partY, partW, part, mx >= partX && mx < partX + partW && my >= partY && my < partY + 14);
            y += 18;
        }
        TheoryItem info = TheoryCache.item(key);
        if (info == null) {
            g.drawString(font, Component.translatable("gui.twinstar.theory.loading"), x, y, DIM, false);
            g.disableScissor();
            return;
        }
        List<Component> facts = new ArrayList<>();
        facts(info, facts);
        for (Component c : facts)
            y = wrapped(g, c, x, y, w, c.getStyle().getColor() != null ? c.getStyle().getColor().getValue() | 0xFF000000 : DIM);

        if (!info.routes().isEmpty()) {
            y += 5;
            y = wrapped(g, Component.translatable("gui.twinstar.theory.routes_title"), x, y, w, TEXT);
            for (int i = 0; i < info.routes().size(); i++) {
                TheoryItem.Route r = info.routes().get(i);
                int color = r.best() ? BEST : Double.isInfinite(r.logCost()) ? DIM : CRAFT;
                if (r.best())
                    PixelIcon.draw(g, PixelIcon.STAR, x, y, BEST);
                String ratio = Double.isInfinite(r.logCost()) ? "—" : "×" + logText(Math.max(0, r.logCost() - info.logCost()));
                MutableComponent line = Component.literal((i + 1) + ". " + ratio);
                if (r.machine() != null)
                    line.append(Component.literal(" ").append(AEKeyRendering.getDisplayName(r.machine())).withColor(DIM));
                int top0 = y;
                y = wrapped(g, line, x + 9, y, w - 9, color);
                if (r.machine() != null)
                    overlayHits.add(r.machine(), x + 9, top0, w - 9, y - top0);
            }
            if (info.moreRoutes() > 0)
                y = wrapped(g, Component.translatable("gui.twinstar.theory.more_routes", info.moreRoutes()), x, y, w, DIM);
        }
        if (!info.bom().isEmpty()) {
            y += 5;
            y = wrapped(g, Component.translatable("gui.twinstar.theory.bom"), x, y, w, TEXT);
            for (TheoryItem.Raw raw : info.bom()) {
                overlayHits.add(raw.key(), x, y, w, 17);
                AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y, raw.key());
                g.drawString(font, "×" + logText(raw.log()), x + 19, y + 4, TEXT, false);
                y += 17;
            }
        }
        if (info.loop() != null) {
            y += 5;
            y = wrapped(g, Component.translatable("gui.twinstar.theory.loop_members"), x, y, w, LOOP_COLOR);
            for (AEKey mKey : info.loop().members()) {
                int top0 = y;
                y = wrapped(g, Component.literal("  ").append(AEKeyRendering.getDisplayName(mKey)), x, y, w, TEXT);
                overlayHits.add(mKey, x, top0, w, y - top0);
            }
            if (!info.loop().seeds().isEmpty()) {
                y = wrapped(g, Component.translatable("gui.twinstar.graph.seeds"), x, y, w, DIM);
                for (TheoryItem.Stack s : info.loop().seeds()) {
                    int top0 = y;
                    y = wrapped(g, stackLine(s.key(), s.amount(), null), x, y, w, TEXT);
                    overlayHits.add(s.key(), x, top0, w, y - top0);
                }
            }
        }
        panelHeight = y + panelScroll - top;
        g.disableScissor();
    }

    /** Level, cost, what kind of item. */
    private static void facts(TheoryItem info, List<Component> lines) {
        if (!info.known()) {
            lines.add(Component.translatable("gui.twinstar.theory.unknown").withColor(DIM));
            return;
        }
        lines.add(Component.translatable("gui.twinstar.theory.level", info.level()).withColor(DIM));
        boolean cannot = !info.raw() && !info.mined() && info.logCost() == Double.POSITIVE_INFINITY;
        if (cannot)
            lines.add(Component.translatable("gui.twinstar.theory.cannot").withColor(MISSING));
        else
            lines.add(Component.translatable("gui.twinstar.theory.cost", logText(info.logCost())).withColor(DIM));
        if (info.raw())
            lines.add(Component.translatable("gui.twinstar.theory.raw").withColor(FROM_STOCK));
        if (info.mined())
            lines.add(Component.translatable("gui.twinstar.theory.mined").withColor(FROM_STOCK));
        if (info.infinite())
            lines.add(Component.translatable("gui.twinstar.plan.infinite").withColor(FROM_STOCK));
        if (!info.raw())
            lines.add(Component.translatable("gui.twinstar.theory.routes", info.routes().size() + info.moreRoutes()).withColor(CRAFT));
        if (info.loop() != null)
            lines.add(Component.translatable("gui.twinstar.theory.in_loop", info.loop().members().size()).withColor(LOOP_COLOR));
    }

    /** Text wrapped to the width; returns the y below it. */
    private int wrapped(GuiGraphics g, Component text, int x, int y, int w, int color) {
        for (var line : font.split(text, w)) {
            g.drawString(font, line, x, y, color, false);
            y += 10;
        }
        return y;
    }
}
