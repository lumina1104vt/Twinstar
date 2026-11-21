package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.order.PlanGraph;
import com.lumina.twinstar.order.PlanMenu;
import com.lumina.twinstar.order.PlanView;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

import static com.lumina.twinstar.client.ui.PlanUI.CRAFT;
import static com.lumina.twinstar.client.ui.PlanUI.DIM;
import static com.lumina.twinstar.client.ui.PlanUI.LOOP_COLOR;
import static com.lumina.twinstar.client.ui.PlanUI.TEXT;

/**
 * The crafting graph of the shown plan. Follows the menu: a new plan (after "plan again" or a mark)
 * is laid out anew with its loops folded.
 */
public class PlanGraphView extends GraphView {
    private final PlanMenu menu;
    private @Nullable PlanView shown;
    /** the graph shown: its own field, as the layout asks for texts while it is being built */
    private PlanGraph graph = PlanGraph.EMPTY;

    public PlanGraphView(PlanMenu menu, Runnable back) {
        super(back);
        this.menu = menu;
        tick();
    }

    /** Follows the menu's plan. */
    public void tick() {
        PlanView view = menu.planning ? null : menu.getView();
        if (view == shown && layout != null)
            return;
        shown = view;
        graph = view == null ? PlanGraph.EMPTY : view.graph();
        setGraph(view == null ? null : graph, false);
    }

    private PlanGraph graph() {
        return graph;
    }

    @Override
    protected Component title() {
        MutableComponent title = Component.translatable("gui.twinstar.graph.title").withStyle(s -> s.withBold(true));
        if (shown != null)
            title.append(Component.literal("  ").append(AEKeyRendering.getDisplayName(shown.target()))
                    .append(" ×" + PlanUI.shortText(shown.amount(), shown.target())).withStyle(s -> s.withBold(false)));
        return title;
    }

    @Override
    protected Component emptyText() {
        return Component.translatable(menu.planning ? "gui.twinstar.plan.planning" : "gui.twinstar.graph.none");
    }

    @Override
    protected String itemText(int item) {
        PlanGraph.Item it = graph().items().get(item);
        if (!it.used().isEmpty())
            return "×" + PlanUI.shortText(it.used(), it.key());
        return it.leftover().isEmpty() ? "" : "+" + PlanUI.shortText(it.leftover(), it.key());
    }

    @Override
    protected int itemTextColor(int item) {
        return graph().items().get(item).used().isEmpty() ? DIM : TEXT;
    }

    @Override
    protected int itemFrame(int item) {
        PlanGraph.Item it = graph().items().get(item);
        if (!it.made().isEmpty())
            return MADE;
        if (!it.stock().isEmpty() || it.infinite())
            return FROM_STOCK;
        return DIM;
    }

    @Override
    protected List<Tab> itemTabs(int item) {
        PlanGraph.Item it = graph().items().get(item);
        List<Tab> tabs = new ArrayList<>(2);
        if (!it.missing().isEmpty())
            tabs.add(Tab.MISSING);
        if (it.infinite())
            tabs.add(Tab.INFINITE);
        return tabs;
    }

    @Override
    protected String recipeText(int recipe) {
        return "×" + PlanUI.shortText(graph().recipes().get(recipe).runs(), null);
    }

    @Override
    protected int recipeTextColor(int recipe) {
        return CRAFT;
    }

    @Override
    protected boolean recipeMissing(int recipe) {
        return graph().recipes().get(recipe).machineMissing();
    }

    @Override
    protected List<Component> itemTooltip(int item) {
        PlanGraph.Item it = graph().items().get(item);
        List<Component> lines = new ArrayList<>();
        lines.add(AEKeyRendering.getDisplayName(it.key()));
        if (!it.used().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.uses", PlanUI.fullText(it.used(), it.key())));
        if (!it.made().isEmpty())
            lines.add(Component.translatable("gui.twinstar.graph.made", PlanUI.fullText(it.made(), it.key())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(MADE)));
        if (!it.stock().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.stock", PlanUI.fullText(it.stock(), it.key())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(FROM_STOCK)));
        if (it.infinite())
            lines.add(Component.translatable("gui.twinstar.plan.infinite").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(FROM_STOCK)));
        if (!it.missing().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.missing", PlanUI.fullText(it.missing(), it.key())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(MISSING)));
        if (!it.leftover().isEmpty())
            lines.add(Component.translatable("gui.twinstar.graph.leftover", PlanUI.fullText(it.leftover(), it.key())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        if (it.loop() >= 0)
            lines.add(Component.translatable("gui.twinstar.graph.in_loop").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(LOOP_COLOR)));
        return lines;
    }

    @Override
    protected List<Component> recipeTooltip(int recipe) {
        PlanGraph.Recipe rc = graph().recipes().get(recipe);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.twinstar.graph.recipe", PlanUI.fullText(rc.runs(), null)).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(CRAFT)));
        if (rc.machine() != null)
            lines.add(rc.machineMissing()
                    ? Component.translatable("gui.twinstar.plan.missing_machine", AEKeyRendering.getDisplayName(rc.machine())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(MISSING))
                    : Component.translatable("gui.twinstar.graph.machine", AEKeyRendering.getDisplayName(rc.machine())).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        lines.add(Component.translatable("gui.twinstar.graph.inputs").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        for (PlanGraph.Flow f : rc.inputs())
            lines.add(flowLine(f, inputKind(f.kind())));
        lines.add(Component.translatable("gui.twinstar.graph.outputs").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        for (PlanGraph.Flow f : rc.outputs())
            lines.add(flowLine(f, f.kind() == 1 ? "byproduct" : null));
        return lines;
    }

    @Override
    protected List<Component> loopTooltip(int loop, List<Integer> items, int recipes) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("gui.twinstar.graph.loop", items.size(), recipes).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(LOOP_COLOR)));
        for (int i : items) {
            PlanGraph.Item it = graph().items().get(i);
            lines.add(Component.literal("  ").append(AEKeyRendering.getDisplayName(it.key()))
                    .append(it.used().isEmpty() ? "" : " ×" + PlanUI.shortText(it.used(), it.key())));
        }
        List<PlanGraph.Flow> seeds = graph().loops().get(loop).seeds();
        if (!seeds.isEmpty()) {
            lines.add(Component.translatable("gui.twinstar.graph.seeds").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
            for (PlanGraph.Flow f : seeds)
                lines.add(flowLine(f, null));
        }
        lines.add(Component.translatable("gui.twinstar.graph.loop.unfold").withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        return lines;
    }

    private Component flowLine(PlanGraph.Flow f, @Nullable String note) {
        AEKey key = graph().items().get(f.item()).key();
        MutableComponent line = Component.literal("  ").append(AEKeyRendering.getDisplayName(key))
                .append(" ×" + PlanUI.fullText(f.amount(), key));
        if (note != null)
            line.append(Component.literal(" ").append(Component.translatable("gui.twinstar.graph." + note)).withStyle(com.lumina.twinstar.client.ui.PlanUI.colorStyle(DIM)));
        return line;
    }
}
