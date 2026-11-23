package com.lumina.twinstar.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.client.gui.implementations.AESubScreen;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.BackArrow;
import com.lumina.twinstar.client.ui.MarkIcon;
import com.lumina.twinstar.client.ui.PixelIcon;
import com.lumina.twinstar.client.ui.PlanUI;
import com.lumina.twinstar.client.ui.UiKit;
import com.lumina.twinstar.client.ui.UiKit.PixelButton;
import com.lumina.twinstar.order.ItemMark;
import com.lumina.twinstar.order.PlanMenu;
import com.lumina.twinstar.order.PlanView;
import com.lumina.twinstar.util.AmountParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.lumina.twinstar.client.ui.PlanUI.AVOID_COLOR;
import static com.lumina.twinstar.client.ui.PlanUI.BAD;
import static com.lumina.twinstar.client.ui.PlanUI.DIM;
import static com.lumina.twinstar.client.ui.PlanUI.GOOD;
import static com.lumina.twinstar.client.ui.PlanUI.KEEP_COLOR;
import static com.lumina.twinstar.client.ui.PlanUI.TEXT;

/**
 * The twin-star plan screen: header with the rotating twin star, the crafting tree on the left (each item with how much the plan
 * uses and where it comes from), the summary and the missing materials on the right, and back / plan again / start below.
 * Drawn with {@link UiKit} (1.20.1 has no LDLib2); follows the menu's plan every frame.
 */
public class PlanScreen extends AbstractContainerScreen<PlanMenu> implements KeyHover {
    private static final int WIDTH = PlanUI.WIDTH, HEIGHT = PlanUI.HEIGHT;
    /** Height between the header and the buttons: fixed, so a long tree or missing list scrolls inside its card. */
    private static final int BODY_H = HEIGHT - 1 - 32 - 6 - 20 - 6;
    private static final int SUMMARY_H = 59, SIDE_GAP = 4;
    private static final int ROW_H = 21, MAX_INDENT_DEPTH = 6, MISSING_ROW_H = 18;
    private static final int LINE = 0xFF484468;
    /** A bookmark ribbon (the start button while Shift is held and something is missing). */
    private static final String[] BOOKMARK_ICON = {
            "#####",
            "#####",
            "#####",
            "#####",
            "#####",
            "##.##",
            "#...#"};

    // card geometry, from the panel's corner
    private static final int TREE_X = 7, TREE_Y = 33, TREE_W = 154;
    private static final int VIEW_X = TREE_X + 2, VIEW_Y = TREE_Y + 2, VIEW_W = TREE_W - 4 - 6, VIEW_H = BODY_H - 4;
    private static final int SIDE_X = TREE_X + TREE_W + 5, SIDE_W = WIDTH - 7 - SIDE_X;
    private static final int MISSING_Y = TREE_Y + SUMMARY_H + SIDE_GAP, MISSING_H = BODY_H - SUMMARY_H - SIDE_GAP;
    private static final int LIST_X = SIDE_X + 3, LIST_Y = MISSING_Y + 3 + 11, LIST_W = SIDE_W - 6 - 6, LIST_H = MISSING_Y + MISSING_H - 3 - LIST_Y;

    private final Inventory playerInventory;
    private final UiKit.Scroller treeScroll = new UiKit.Scroller(), missingScroll = new UiKit.Scroller();
    private final Map<Integer, EditBox> keepBoxes = new HashMap<>();
    private PixelButton replan, start;
    /** the start button is usable (the plan is complete and fits) */
    private boolean canStart;
    /** what the start button looks like now (mode and whether something is missing); -1 before the first look */
    private int startLook = -1;

    private @Nullable PlanView shownView;
    private boolean shownPlanning;
    private String shownError = "";

    public PlanScreen(PlanMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.playerInventory = playerInventory;
    }

    /** The crafting graph; a failure is told in the chat and logged. */
    private void openGraph() {
        if (menu.getView() == null || menu.planning) {
            playerInventory.player.displayClientMessage(Component.translatable("gui.twinstar.graph.no_plan"), false);
            return;
        }
        try {
            minecraft.setScreen(new PlanGraphScreen(menu, playerInventory, title));
        } catch (RuntimeException | StackOverflowError e) {
            Twinstar.LOGGER.error("[雙星] 合成圖打不開", e);
            playerInventory.player.displayClientMessage(Component.translatable("gui.twinstar.graph.failed", e.toString()), false);
        }
    }

    @Override
    protected void init() {
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        super.init();
        int x = leftPos, y = topPos;
        PixelButton graph = new PixelButton(x + 220, y + 9, 16, 16, Component.empty(),
                (g, bx, by, w, h, hot) -> PixelIcon.draw(g, PixelIcon.TREE, bx + 3, by + 3, TEXT), this::openGraph);
        graph.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.graph.open")));
        addRenderableWidget(graph);
        PixelButton close = new PixelButton(x + 239, y + 9, 16, 16, Component.empty(),
                (g, bx, by, w, h, hot) -> BackArrow.draw(g, bx + 3, by + 4), AESubScreen::goBack);
        close.setTooltip(Tooltip.create(menu.getHost().getMainMenuIcon().getHoverName()));
        addRenderableWidget(close);

        int footerY = y + TREE_Y + BODY_H + 6;
        addRenderableWidget(new PixelButton(x + 7, footerY, 64, 20, Component.translatable("gui.twinstar.plan.back"), menu::back));
        replan = new PixelButton(x + 75, footerY, 74, 20, Component.translatable("gui.twinstar.plan.replan"), menu::replan);
        addRenderableWidget(replan);
        start = new PixelButton(x + WIDTH - 7 - 90, footerY, 90, 20, Component.translatable("gui.twinstar.plan.start"),
                (g, bx, by, w, h, hot) -> {
                    if (startLook / 2 != 1)
                        return;
                    // "bookmark missing": a ribbon and the text, drawn here (the caption is empty then)
                    Component text = Component.translatable("gui.twinstar.plan.bookmark");
                    int total = BOOKMARK_ICON[0].length() + 3 + UiKit.smallWidth(text);
                    int left = bx + (w - total) / 2;
                    PixelIcon.draw(g, BOOKMARK_ICON, left, by + 6, TEXT);
                    UiKit.small(g, text, left + BOOKMARK_ICON[0].length() + 3, by + 6, TEXT, w - 4);
                }, this::pressStart);
        startLook = -1;
        addRenderableWidget(start);
        shownView = null;
        keepBoxes.clear();
        refresh();
    }

    /** Follows the menu: planning, the plan's view, a failed start. */
    private void refresh() {
        PlanView view = menu.planning ? null : menu.getView();
        boolean planning = menu.planning;
        replan.active = !planning;
        // too big for the black hole: starting would only fail
        canStart = view != null && view.complete() && view.fits();
        updateStartButton();
        if (view == shownView && planning == shownPlanning && menu.error.equals(shownError))
            return;
        if (view != shownView) {
            keepBoxes.clear();
            if (shownView == null || view == null)
                treeScroll.reset();
            missingScroll.reset();
        }
        shownView = view;
        shownPlanning = planning;
        shownError = menu.error;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        keepBoxes.values().forEach(EditBox::tick);
        refresh();
    }

    /**
     * What the start button is: with Shift held and something missing in the plan it is "bookmark missing", otherwise
     * "start". 0 start, 1 bookmark missing, 2 start with Shift held but nothing missing. Asked every frame (Shift is read
     * when it is used, never remembered).
     */
    private int wantedStartMode() {
        if (!hasShiftDown())
            return 0;
        PlanView view = menu.planning ? null : menu.getView();
        return view != null && !view.missing().isEmpty() ? 1 : 2;
    }

    /** The start button, or with Shift held and something missing, "bookmark missing" in its place. */
    private void pressStart() {
        if (wantedStartMode() == 1)
            bookmarkMissing();
        else if (canStart)
            menu.start();
    }

    /**
     * Gives the start button its look for the mode (a ribbon icon and other text, never colour alone; the tooltip says what a
     * click does), when it changed. Called every frame and every tick.
     */
    private void updateStartButton() {
        if (start == null)
            return;
        int mode = wantedStartMode();
        start.active = mode == 1 || canStart;       // usable even when starting is not (the plan lacks something)
        PlanView view = menu.planning ? null : menu.getView();
        boolean missing = view != null && !view.missing().isEmpty();
        int look = mode * 2 + (missing ? 1 : 0);
        if (look == startLook)
            return;
        startLook = look;
        start.setMessage(mode == 1 ? Component.empty() : Component.translatable("gui.twinstar.plan.start"));
        if (mode == 1)
            start.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.plan.bookmark.tooltip")));
        else if (mode == 2)
            start.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.plan.bookmark.nothing")));
        else if (missing)
            start.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.plan.bookmark.hint")));
        else
            start.setTooltip(null);
    }

    /**
     * Puts every missing material (and machine) into JEI / EMI in one go, so the player can look them up and fetch them.
     * Tells how many were new, or that JEI / EMI cannot take them (not loaded yet).
     */
    private void bookmarkMissing() {
        PlanView view = menu.planning ? null : menu.getView();
        if (view == null)
            return;
        if (!Bookmarks.available()) {
            playerInventory.player.displayClientMessage(Component.translatable("gui.twinstar.plan.bookmark.unavailable"), true);
            return;
        }
        List<AEKey> keys = view.missing().stream().map(PlanView.Missing::key).distinct().toList();
        int added = Bookmarks.add(keys);
        playerInventory.player.displayClientMessage(Component.translatable(
                added > 0 ? "gui.twinstar.plan.bookmark.done" : "gui.twinstar.plan.bookmark.none", added, keys.size()), true);
    }

    /**
     * The material drawn under the mouse, for JEI and EMI (their A key bookmarks it): a row of the crafting tree, the target
     * in the summary, a row of the missing list.
     */
    @Override
    public @Nullable KeyHit keyAt(double mx, double my) {
        PlanView view = shownView;
        if (view == null)
            return null;
        int vx = leftPos + VIEW_X, vy = topPos + VIEW_Y;
        if (UiKit.inside(mx, my, vx, vy, VIEW_W, VIEW_H)) {
            int i = ((int) my - vy + treeScroll.offset) / ROW_H;
            if (i >= 0 && i < view.rows().size())
                return new KeyHit(view.rows().get(i).key(), vx, vy + i * ROW_H - treeScroll.offset, VIEW_W, ROW_H);
            return null;
        }
        int sx = leftPos + SIDE_X + 3, sy = topPos + TREE_Y + 3;
        if (UiKit.inside(mx, my, sx, sy, SIDE_W - 6, 16))
            return new KeyHit(view.target(), sx, sy, SIDE_W - 6, 16);
        int lx = leftPos + LIST_X, ly = topPos + LIST_Y;
        if (UiKit.inside(mx, my, lx, ly, LIST_W, LIST_H)) {
            int i = ((int) my - ly + missingScroll.offset) / MISSING_ROW_H;
            if (i >= 0 && i < view.missing().size())
                return new KeyHit(view.missing().get(i).key(), lx, ly + i * MISSING_ROW_H - missingScroll.offset, LIST_W, MISSING_ROW_H);
        }
        return null;
    }

    // ── drawing ──

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.blit(UiKit.PLAN_PANEL, x, y, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        TwinStarBadge.draw(g, x + 3, y + 1);
        g.drawString(font, Component.translatable("gui.twinstar.plan.title").withStyle(s -> s.withBold(true)), x + 38, y + 12, UiKit.TITLE, false);
        UiKit.nineSlice(g, UiKit.INSET, x + TREE_X, y + TREE_Y, TREE_W, BODY_H, 5);

        PlanView view = shownView;
        drawTree(g, view, mouseX, mouseY);
        boolean missing = view != null && !view.missing().isEmpty();
        UiKit.nineSlice(g, UiKit.INSET, x + SIDE_X, y + TREE_Y, SIDE_W, SUMMARY_H, 5);
        UiKit.nineSlice(g, missing ? UiKit.INSET_MISSING : UiKit.INSET, x + SIDE_X, y + MISSING_Y, SIDE_W, MISSING_H, 5);
        if (view != null) {
            drawSummary(g, view);
            drawMissing(g, view);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // drawn in renderBg
    }

    private void drawTree(GuiGraphics g, @Nullable PlanView view, int mouseX, int mouseY) {
        int vx = leftPos + VIEW_X, vy = topPos + VIEW_Y;
        if (view == null) {
            boolean failed = !shownPlanning && !shownError.isEmpty();
            Component msg = failed ? PlanUI.errorText(shownError) : Component.translatable("gui.twinstar.plan.planning");
            UiKit.small(g, msg, vx + 4, vy + 60, failed ? BAD : TEXT, VIEW_W - 8);
            return;
        }
        int rows = view.rows().size();
        int content = rows * ROW_H + (view.truncated() ? 12 : 0);
        treeScroll.clamp(content, VIEW_H);
        g.enableScissor(vx, vy, vx + VIEW_W, vy + VIEW_H);
        for (int i = 0; i < rows; i++) {
            int ry = vy + i * ROW_H - treeScroll.offset;
            if (ry + ROW_H < vy || ry > vy + VIEW_H)
                continue;
            drawRow(g, view.rows().get(i), i, vx, ry, mouseX, mouseY);
        }
        if (view.truncated())
            UiKit.small(g, Component.translatable("gui.twinstar.plan.truncated"), vx + 4, vy + rows * ROW_H - treeScroll.offset + 2, DIM, VIEW_W - 8);
        g.disableScissor();
        treeScroll.drawBar(g, vx + VIEW_W + 1, vy, VIEW_H, content, VIEW_H);
    }

    /** One item of the crafting tree: indented by depth, a connector to its parent, tinted when something is missing, the player's mark. */
    private void drawRow(GuiGraphics g, PlanView.Row row, int index, int vx, int ry, int mouseX, int mouseY) {
        int depth = Math.min(row.depth(), MAX_INDENT_DEPTH);
        boolean missing = !row.missing().isEmpty() || row.missingMachine() != null;
        if (missing)
            g.fill(vx, ry, vx + VIEW_W, ry + ROW_H, 0xFF30141C);
        int indent = vx + 2 + depth * 10;
        if (depth > 0) {
            g.fill(indent - 6, ry - 2, indent - 5, ry + 10, LINE);
            g.fill(indent - 6, ry + 9, indent - 2, ry + 10, LINE);
        }
        AEKeyRendering.drawInGui(minecraft, g, indent, ry + 2, row.key());
        int tx = indent + 18;
        int tagX = vx + VIEW_W - 1 - 13;
        int room = (row.depth() > 0 ? tagX - 2 : vx + VIEW_W - 2) - tx;
        UiKit.small(g, AEKeyRendering.getDisplayName(row.key()).copy().append(" ×" + PlanUI.shortText(row.amount(), row.key())), tx, ry + 3, TEXT, room);
        boolean keep = row.depth() > 0 && menu.markOf(row.key()) == ItemMark.KEEP;
        if (keep) {
            Component label = Component.translatable("gui.twinstar.plan.keep");
            UiKit.small(g, label, tx, ry + 12, KEEP_COLOR, room);
            EditBox box = keepBox(row.key(), index);
            box.setX(tx + UiKit.smallWidth(label) + 2);
            box.setY(ry + 12);
            UiKit.box(g, box.getX() - 1, box.getY() - 1, 54, 10, 0xFF040408, UiKit.EDGE);
            box.render(g, mouseX, mouseY, 0);
        } else {
            UiKit.small(g, PlanUI.sources(row), tx, ry + 12, TEXT, room);
        }
        if (row.depth() > 0)
            drawMarkTag(g, row.key(), tagX, ry + 4, mouseX, mouseY);
    }

    private void drawMarkTag(GuiGraphics g, AEKey key, int x, int y, int mouseX, int mouseY) {
        ItemMark mark = menu.markOf(key);
        boolean hot = UiKit.inside(mouseX, mouseY, x, y, 13, 12);
        int fill, edge;
        switch (mark) {
            case AVOID -> {
                fill = hot ? 0xFF4A3018 : 0xFF3A2410;
                edge = hot ? 0xFFF0B060 : 0xFFC08040;
            }
            case FREE -> {
                fill = hot ? 0xFF184024 : 0xFF10301C;
                edge = hot ? 0xFF78E296 : 0xFF40A060;
            }
            case KEEP -> {
                fill = hot ? 0xFF163050 : 0xFF10243A;
                edge = hot ? 0xFF5AB4FF : 0xFF4A8CC8;
            }
            default -> {
                fill = hot ? 0xFF2C2642 : 0xFF1E1C2C;
                edge = hot ? UiKit.HOVER_EDGE : UiKit.EDGE;
            }
        }
        UiKit.box(g, x, y, 13, 12, fill, edge);
        int color = switch (mark) {
            case AUTO -> DIM;
            case AVOID -> AVOID_COLOR;
            case FREE -> GOOD;
            case KEEP -> KEEP_COLOR;
        };
        MarkIcon.draw(g, mark, x + 3, y + 2, color);
    }

    /** How much of a kept item is kept: a small field ("all", or an amount in the item's display unit); Enter plans again with it. */
    private EditBox keepBox(AEKey key, int row) {
        return keepBoxes.computeIfAbsent(row, r -> {
            int perUnit = Math.max(1, key.getAmountPerUnit());
            BigInteger kept = menu.keepOf(key);
            EditBox box = new EditBox(font, 0, 0, 52, 9, Component.translatable("gui.twinstar.plan.keep"));
            box.setBordered(false);
            box.setMaxLength(60);
            box.setTextColor(TEXT);
            box.setValue(kept == null ? Component.translatable("gui.twinstar.plan.keep.all").getString()
                    : PlanUI.fieldText(kept.toString(), perUnit));
            return box;
        });
    }

    private void submitKeep(AEKey key, int row, EditBox box) {
        int perUnit = Math.max(1, key.getAmountPerUnit());
        BigDecimal v = AmountParser.parse(box.getValue());
        // anything that is not a number ("all", empty) keeps it whole
        String amount = v == null || v.signum() < 0 ? ""
                : v.multiply(BigDecimal.valueOf(perUnit)).setScale(0, RoundingMode.CEILING).toBigInteger().toString();
        menu.mark(new PlanMenu.MarkRequest(row, ItemMark.KEEP.ordinal(), amount));
    }

    private void drawSummary(GuiGraphics g, PlanView view) {
        int x = leftPos + SIDE_X + 3, y = topPos + TREE_Y + 3, room = SIDE_W - 6;
        AEKeyRendering.drawInGui(minecraft, g, x, y, view.target());
        g.drawString(font, "×" + PlanUI.shortText(view.amount(), view.target()), x + 18, y + 4, TEXT, false);
        int ly = y + 18;
        UiKit.small(g, Component.translatable("gui.twinstar.plan.patterns", view.patterns()), x, ly, DIM, room);
        UiKit.small(g, Component.translatable("gui.twinstar.plan.time", PlanUI.millis(view.planNanos())), x, ly + 8, DIM, room);
        UiKit.small(g, Component.translatable("gui.twinstar.plan.capacity", PlanUI.shortText(view.usage(), null),
                PlanUI.limit(view.freeCapacity())), x, ly + 16, view.fits() ? TEXT : BAD, room);
        UiKit.small(g, Component.translatable("gui.twinstar.plan.parallel", PlanUI.limit(view.parallel())), x, ly + 24, TEXT, room);
        if (!shownError.isEmpty())       // a start that failed
            UiKit.small(g, PlanUI.errorText(shownError), x, ly + 32, BAD, room);
        else if (!view.fits())           // said before the start is pressed
            UiKit.small(g, PlanUI.errorText("cpu_too_small"), x, ly + 32, BAD, room);
    }

    private void drawMissing(GuiGraphics g, PlanView view) {
        List<PlanView.Missing> missing = view.missing();
        int x = leftPos + SIDE_X + 3, y = topPos + MISSING_Y + 3;
        if (missing.isEmpty()) {
            UiKit.small(g, Component.translatable(view.complete() ? "gui.twinstar.plan.complete" : "gui.twinstar.plan.less"), x, y, GOOD, SIDE_W - 6);
            return;
        }
        long machines = missing.stream().filter(PlanView.Missing::machine).count();
        long materials = missing.size() - machines;
        Component title = machines == 0 ? Component.translatable("gui.twinstar.plan.missing_title", materials)
                : materials == 0 ? Component.translatable("gui.twinstar.plan.missing_machines_title", machines)
                : Component.translatable("gui.twinstar.plan.missing_both_title", materials, machines);
        UiKit.small(g, title, x, y, BAD, SIDE_W - 6);
        int lx = leftPos + LIST_X, ly = topPos + LIST_Y;
        int content = missing.size() * MISSING_ROW_H;
        missingScroll.clamp(content, LIST_H);
        g.enableScissor(lx, ly, lx + LIST_W, ly + LIST_H);
        for (int i = 0; i < missing.size(); i++) {
            int ry = ly + i * MISSING_ROW_H - missingScroll.offset;
            if (ry + MISSING_ROW_H < ly || ry > ly + LIST_H)
                continue;
            PlanView.Missing m = missing.get(i);
            AEKeyRendering.drawInGui(minecraft, g, lx, ry + 1, m.key());
            UiKit.small(g, AEKeyRendering.getDisplayName(m.key()), lx + 18, ry + 2, BAD, LIST_W - 18);
            UiKit.small(g, m.machine() ? Component.translatable("gui.twinstar.plan.machine")
                    : Component.literal("×" + PlanUI.shortText(m.amount(), m.key())), lx + 18, ry + 10, BAD, LIST_W - 18);
        }
        g.disableScissor();
        missingScroll.drawBar(g, lx + LIST_W + 1, ly, LIST_H, content, LIST_H);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        updateStartButton();        // Shift is read every frame: held, the start button is "bookmark missing"
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        tooltips(g, mouseX, mouseY);
    }

    private void tooltips(GuiGraphics g, int mouseX, int mouseY) {
        PlanView view = shownView;
        if (view == null)
            return;
        int vx = leftPos + VIEW_X, vy = topPos + VIEW_Y;
        if (UiKit.inside(mouseX, mouseY, vx, vy, VIEW_W, VIEW_H)) {
            int i = (mouseY - vy + treeScroll.offset) / ROW_H;
            if (i >= 0 && i < view.rows().size()) {
                PlanView.Row row = view.rows().get(i);
                int ry = vy + i * ROW_H - treeScroll.offset;
                int tagX = vx + VIEW_W - 1 - 13;
                if (row.depth() > 0 && UiKit.inside(mouseX, mouseY, tagX, ry + 4, 13, 12)) {
                    String name = menu.markOf(row.key()).name().toLowerCase(Locale.ROOT);
                    g.renderTooltip(font, List.of(Component.translatable("gui.twinstar.plan.mark." + name + ".tooltip"),
                            PlanUI.coloured(Component.translatable("gui.twinstar.plan.mark.click"), DIM)), Optional.empty(), mouseX, mouseY);
                } else {
                    g.renderTooltip(font, PlanUI.rowTooltip(row), Optional.empty(), mouseX, mouseY);
                }
                return;
            }
        }
        if (UiKit.inside(mouseX, mouseY, leftPos + SIDE_X, topPos + TREE_Y, SIDE_W, SUMMARY_H)) {
            g.renderTooltip(font, List.of(AEKeyRendering.getDisplayName(view.target()),
                    Component.literal("×" + PlanUI.fullText(view.amount(), view.target())),
                    Component.translatable("gui.twinstar.plan.capacity.tooltip", PlanUI.fullText(view.usage(), null),
                            view.freeCapacity().isEmpty() ? "∞" : PlanUI.fullText(view.freeCapacity(), null))), Optional.empty(), mouseX, mouseY);
            return;
        }
        int lx = leftPos + LIST_X, ly = topPos + LIST_Y;
        if (UiKit.inside(mouseX, mouseY, lx, ly, LIST_W, LIST_H)) {
            int i = (mouseY - ly + missingScroll.offset) / MISSING_ROW_H;
            if (i >= 0 && i < view.missing().size()) {
                PlanView.Missing m = view.missing().get(i);
                g.renderTooltip(font, List.of(AEKeyRendering.getDisplayName(m.key()), m.machine()
                                ? PlanUI.coloured(Component.translatable("gui.twinstar.plan.machine.tooltip"), BAD)
                                : PlanUI.coloured(Component.translatable("gui.twinstar.plan.missing", PlanUI.fullText(m.amount(), m.key())), BAD)),
                        Optional.empty(), mouseX, mouseY);
            }
        }
    }

    // ── input ──

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        PlanView view = shownView;
        int vx = leftPos + VIEW_X, vy = topPos + VIEW_Y;
        if (view != null && button == 0 && UiKit.inside(mx, my, vx, vy, VIEW_W, VIEW_H)) {
            int i = ((int) my - vy + treeScroll.offset) / ROW_H;
            if (i >= 0 && i < view.rows().size()) {
                PlanView.Row row = view.rows().get(i);
                int ry = vy + i * ROW_H - treeScroll.offset;
                int tagX = vx + VIEW_W - 1 - 13;
                boolean any = false;
                for (EditBox box : keepBoxes.values())
                    box.setFocused(false);
                if (row.depth() > 0 && UiKit.inside(mx, my, tagX, ry + 4, 13, 12)) {
                    menu.mark(new PlanMenu.MarkRequest(i, menu.markOf(row.key()).next().ordinal()));
                    return true;
                }
                EditBox box = keepBoxes.get(i);
                if (box != null && box.mouseClicked(mx, my, button)) {
                    box.setFocused(true);
                    return true;
                }
            }
        }
        for (EditBox box : keepBoxes.values())
            box.setFocused(false);
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        PlanView view = shownView;
        if (view != null) {
            if (UiKit.inside(mx, my, leftPos + VIEW_X, topPos + VIEW_Y, VIEW_W + 6, VIEW_H)
                    && treeScroll.wheel(delta, view.rows().size() * ROW_H + (view.truncated() ? 12 : 0), VIEW_H, ROW_H))
                return true;
            if (UiKit.inside(mx, my, leftPos + LIST_X, topPos + LIST_Y, LIST_W + 6, LIST_H)
                    && missingScroll.wheel(delta, view.missing().size() * MISSING_ROW_H, LIST_H, MISSING_ROW_H))
                return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (Map.Entry<Integer, EditBox> e : keepBoxes.entrySet()) {
            EditBox box = e.getValue();
            if (!box.isFocused() || keyCode == GLFW.GLFW_KEY_ESCAPE)
                continue;
            PlanView view = shownView;
            if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && view != null && e.getKey() < view.rows().size()) {
                submitKeep(view.rows().get(e.getKey()).key(), e.getKey(), box);
                return true;
            }
            return box.keyPressed(keyCode, scanCode, modifiers) || box.canConsumeInput();    // E must not close it
        }
        if (super.keyPressed(keyCode, scanCode, modifiers))
            return true;
        // Enter anywhere starts the order, as the start button does (nothing while it is not usable)
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            pressStart();
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        for (EditBox box : keepBoxes.values())
            if (box.isFocused())
                return box.charTyped(c, modifiers);
        return super.charTyped(c, modifiers);
    }
}
