package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.client.gui.implementations.AESubScreen;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.screens.Screen;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.Bookmarks;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.order.ItemMark;
import com.lumina.twinstar.order.PlanMenu;
import com.lumina.twinstar.order.PlanView;
import com.lumina.corelib.util.AmountParser;
import com.lumina.corelib.util.BigAmountFormat;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The twin-star plan screen, built with LDLib2's UI (layout B): header with the rotating twin star,
 * the crafting tree on the left (each item with how much the plan uses and where it comes from),
 * the summary and the missing materials on the right, and back / plan again / start below. The look
 * is in assets/twinstar/lss/plan.lss; {@link #refresh()} follows the menu's plan.
 */
public class PlanUI {
    public static final int WIDTH = 262, HEIGHT = 214;
    /**
     * Height between the header and the buttons: fixed, so a long tree or missing list scrolls
     * inside its card instead of stretching the card past the panel.
     */
    private static final int BODY_H = HEIGHT - 1 - 32 - 6 - 20 - 6;
    private static final int SUMMARY_H = 59, CARD_PAD = 2, SIDE_GAP = 4;
    /** the look of both twin-star screens */
    static final ResourceLocation STYLESHEET = ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "lss/twinstar.lss");

    static final int TEXT = 0xFFDEDEEA;
    static final int DIM = 0xFF8C8CA2;
    static final int CRAFT = 0xFF96ACFF;
    static final int GOOD = 0xFF78E296;
    static final int BAD = 0xFFFF7474;
    static final int AVOID_COLOR = 0xFFF0B060;
    static final int KEEP_COLOR = 0xFF5AB4FF;
    private static final int LINE = 0xFF484468;
    private static final float SMALL = 7;
    static final int LOOP_COLOR = 0xFFC890FF;
    private static final int ROW_H = 21, MAX_INDENT_DEPTH = 6;

    private final PlanMenu menu;
    private final ModularUI modularUI;
    private final ScrollerView tree = new ScrollerView();
    private final UIElement summary = new UIElement();
    private final UIElement missingCard = new UIElement();
    private final Label missingTitle = new PixelLabel();
    private final ScrollerView missingList = new ScrollerView();
    private final Button replan = PixelLabel.button(Component.translatable("gui.twinstar.plan.replan"));
    private final Button start = PixelLabel.button(Component.translatable("gui.twinstar.plan.start"));
    private final Button force = new Button();
    private final Player player;
    /** the start button's own caption, and the bookmark icon shown beside it while Shift turns it into "bookmark missing" */
    private final Label startCaption = PixelLabel.captionOf(start);
    private final PixelIcon startIcon = new PixelIcon(BOOKMARK_ICON, TEXT);
    /** what the start button looks like now (mode and whether something is missing); -1 before the first look */
    private int startLook = -1;
    private boolean forceShown;
    private boolean canStart;               // the start button is usable
    private boolean enterHandled;           // a field (the kept amount) already took this Enter press

    // what is shown now
    private @Nullable PlanView shownView;
    private boolean shownPlanning;
    private @Nullable String shownError;

    /** @param openGraph opens the plan's crafting graph (a screen of its own) */
    public PlanUI(PlanMenu menu, Player player, Runnable openGraph) {
        this.menu = menu;
        this.player = player;
        UIElement root = new UIElement().addClass("plan_root");
        root.layout(l -> l.width(WIDTH).height(HEIGHT).paddingLeft(7).paddingRight(7).paddingTop(1).paddingBottom(6));
        root.addChildren(header(openGraph, player), body(), footer());
        this.modularUI = new ModularUI(UI.of(root, STYLESHEET), player);
    }

    public ModularUI modularUI() {
        return modularUI;
    }

    // ── layout ──

    private UIElement header(Runnable openGraph, Player player) {
        Label title = new PixelLabel();
        title.setText(Component.translatable("gui.twinstar.plan.title").withStyle(s -> s.withBold(true)));
        title.addClass("title");
        title.layout(l -> l.flexGrow(1).marginLeft(3));
        Button close = new Button();
        close.noText();
        close.addChild(new BackArrow());
        close.setOnClick(e -> AESubScreen.goBack());
        close.style(s -> s.tooltips(menu.getHost().getMainMenuIcon().getHoverName()));
        close.layout(l -> l.width(16).height(16).paddingAll(0).justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        Button graph = new Button();
        graph.noText();
        graph.addChild(new PixelIcon(PixelIcon.TREE, TEXT));
        graph.setOnClick(e -> {
            if (menu.getView() != null && !menu.planning)
                openGraph.run();
            else
                player.displayClientMessage(Component.translatable("gui.twinstar.graph.no_plan"), false);
        });
        graph.style(s -> s.tooltips(Component.translatable("gui.twinstar.graph.open"),
                Component.translatable("gui.twinstar.graph.open.tooltip").withColor(DIM)));
        graph.layout(l -> l.width(16).height(16).paddingAll(0).marginRight(3)
                .justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        UIElement header = new UIElement().addChildren(new TwinStarElement().layout(l -> l.marginLeft(-4)), title,
                graph, close);
        header.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(32).flexShrink(0));
        return header;
    }

    private UIElement body() {
        UIElement treeCard = new UIElement().addClass("card").addChild(tree);
        treeCard.layout(l -> l.width(154).height(BODY_H).paddingAll(CARD_PAD).flexShrink(0));
        tree.layout(l -> l.height(BODY_H - 2 * CARD_PAD).flexShrink(0));

        summary.addClass("card");
        summary.layout(l -> l.height(SUMMARY_H).paddingAll(3).gapAll(1).flexShrink(0));

        int missingH = BODY_H - SUMMARY_H - SIDE_GAP;
        missingCard.addClass("card").addChildren(missingTitle, missingList);
        missingCard.layout(l -> l.height(missingH).paddingAll(3).gapAll(2).flexShrink(0));
        missingTitle.layout(l -> l.flexShrink(0));
        missingList.layout(l -> l.flexGrow(1).flexShrink(1).flexBasis(0).minHeight(0));

        UIElement side = new UIElement().addChildren(summary, missingCard);
        side.layout(l -> l.flexGrow(1).height(BODY_H).gapAll(SIDE_GAP));
        UIElement body = new UIElement().addChildren(treeCard, side);
        body.layout(l -> l.flexDirection(FlexDirection.ROW).height(BODY_H).flexShrink(0).gapAll(5));
        return body;
    }

    private UIElement footer() {
        Button back = PixelLabel.button(Component.translatable("gui.twinstar.plan.back"));
        back.setOnClick(e -> menu.back());
        back.layout(l -> l.width(56).height(20));
        replan.setOnClick(e -> menu.replan());
        replan.layout(l -> l.width(66).height(20));
        start.setOnClick(e -> pressStart());
        start.layout(l -> l.width(84).height(20));
        // the bookmark icon sits before the caption; it only shows while Shift turns the button into "bookmark missing"
        startIcon.layout(l -> l.marginRight(3));
        startIcon.setDisplay(false);
        start.clearAllChildren();
        start.addChildren(startIcon, startCaption);
        UIElement spacer = new UIElement().layout(l -> l.flexGrow(1));
        force.noText();
        force.addChild(new PixelIcon(PixelIcon.FORCE, TEXT));
        force.setOnClick(e -> menu.startForced());
        force.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.force"),
                Component.translatable("gui.twinstar.plan.force.tooltip").withColor(DIM),
                Component.translatable("gui.twinstar.plan.force.risk").withColor(AVOID_COLOR)));
        force.layout(l -> l.width(20).height(20).paddingAll(0).justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        force.setDisplay(false);
        UIElement footer = new UIElement().addChildren(back, replan, spacer, force, start);
        footer.layout(l -> l.flexDirection(FlexDirection.ROW).height(20).marginTop(6).gapAll(4).flexShrink(0));
        return footer;
    }

    /** The material under the mouse (JEI and EMI bookmark it with their A key), or null. */
    public @Nullable KeyHit keyAt() {
        return KeyRow.under(modularUI);
    }

    /** A bookmark ribbon (the start button while Shift is held and something is missing). */
    private static final String[] BOOKMARK_ICON = {
            "#####",
            "#####",
            "#####",
            "#####",
            "#####",
            "##.##",
            "#...#"};

    /**
     * What the start button does now: with Shift held and something missing in the plan it is "bookmark
     * missing", otherwise "start". 0 start, 1 bookmark missing, 2 start with Shift held but nothing missing.
     * Asked every frame (the Shift key is read when it is used, never remembered).
     */
    private int wantedStartMode() {
        if (!Screen.hasShiftDown())
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
     * Gives the start button its look for the mode (icon and text, never colour alone; the tooltip says what a click does),
     * when the mode changed. Called every frame and every tick.
     */
    public void updateStartButton() {
        int mode = wantedStartMode();
        if (mode == 1)
            start.setActive(true);          // usable even when starting is not (the plan lacks something)
        else
            start.setActive(canStart);
        PlanView view = menu.planning ? null : menu.getView();
        boolean missing = view != null && !view.missing().isEmpty();
        int look = mode * 2 + (missing ? 1 : 0);
        if (look == startLook)
            return;
        startLook = look;
        boolean bookmark = mode == 1;
        startIcon.setDisplay(bookmark);
        startCaption.setText(Component.translatable(bookmark ? "gui.twinstar.plan.bookmark" : "gui.twinstar.plan.start"));
        startCaption.textStyle(t -> t.fontSize(bookmark ? SMALL : 9));
        if (bookmark)
            start.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.bookmark"),
                    Component.translatable("gui.twinstar.plan.bookmark.tooltip").withColor(DIM)));
        else if (mode == 2)
            start.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.bookmark.nothing").withColor(DIM)));
        else if (missing)
            start.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.bookmark.hint").withColor(DIM)));
        else
            start.style(s -> s.tooltips(new Component[0]));
    }

    /**
     * Puts every missing material (and machine) into JEI / EMI in one go, so the player can look them up and
     * fetch them. Tells how many were new, or that JEI / EMI cannot take them (not loaded yet).
     */
    private void bookmarkMissing() {
        PlanView view = menu.planning ? null : menu.getView();
        if (view == null)
            return;
        if (!Bookmarks.available()) {
            player.displayClientMessage(Component.translatable("gui.twinstar.plan.bookmark.unavailable"), true);
            return;
        }
        List<AEKey> keys = view.missing().stream().map(PlanView.Missing::key).distinct().toList();
        int added = Bookmarks.add(keys);
        player.displayClientMessage(Component.translatable(added > 0 ? "gui.twinstar.plan.bookmark.done" : "gui.twinstar.plan.bookmark.none",
                added, keys.size()), true);
    }

    // ── content ──

    /** Call before a key press is handed on. */
    public void beginKey() {
        enterHandled = false;
    }

    /**
     * Enter starts the order, as the start button does (a kept-amount field that had the keyboard has plans again
     * with it instead). Nothing happens while the start button is not usable. True when the key was Enter.
     */
    public boolean enterPressed(int keyCode) {
        if (!OrderUI.isEnter(keyCode))
            return false;
        if (!enterHandled)
            pressStart();
        return true;
    }

    /** Follows the menu: planning, the plan's view, a failed start. Called every client tick. */
    public void refresh() {
        PlanView view = menu.planning ? null : menu.getView();
        boolean planning = menu.planning;
        String error = menu.error;
        replan.setActive(!planning);
        // too big for the black hole: starting would only fail
        canStart = view != null && view.complete() && view.fits();
        updateStartButton();
        // 強制下單: only when the plan lacks something and there is something to make
        boolean canForce = view != null && !view.complete() && view.fits() && view.patterns() > 0;
        if (canForce != forceShown) {
            forceShown = canForce;
            force.setDisplay(canForce);
        }
        if (view == shownView && planning == shownPlanning && error.equals(shownError))
            return;
        shownView = view;
        shownPlanning = planning;
        shownError = error;

        tree.clearAllScrollViewChildren();
        fillTree(view, planning, error);
        summary.clearAllChildren();
        missingList.clearAllScrollViewChildren();
        missingTitle.setText(Component.empty());
        missingCard.removeClass("card_missing");
        missingCard.addClass("card");
        if (view == null)
            return;
        fillSummary(view, error);
        fillMissing(view);
    }

    private void fillTree(@Nullable PlanView view, boolean planning, String error) {
        if (view == null) {
            Component msg = !planning && !error.isEmpty() ? errorText(error) : Component.translatable("gui.twinstar.plan.planning");
            Label label = small(msg, !planning && !error.isEmpty() ? BAD : TEXT);
            label.layout(l -> l.marginTop(60).marginLeft(4));
            tree.addScrollViewChild(label);
            return;
        }
        for (int i = 0; i < view.rows().size(); i++)
            tree.addScrollViewChild(new TreeRow(view.rows().get(i), i));
        if (view.truncated())
            tree.addScrollViewChild(small(Component.translatable("gui.twinstar.plan.truncated"), DIM).layout(l -> l.marginLeft(4)));
    }

    private void fillSummary(PlanView view, String error) {
        Label amount = new PixelLabel();
        amount.setText(Component.literal("×" + shortText(view.amount(), view.target())));
        amount.layout(l -> l.marginLeft(2));
        UIElement target = new KeyRow(view.target()).addChildren(new KeyIcon(view.target()), amount);
        target.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).marginBottom(1));
        summary.addChildren(target,
                small(Component.translatable("gui.twinstar.plan.patterns", view.patterns()), DIM),
                small(Component.translatable("gui.twinstar.plan.time", millis(view.planNanos())), DIM),
                small(Component.translatable("gui.twinstar.plan.capacity", shortText(view.usage(), null),
                        limit(view.freeCapacity())), view.fits() ? TEXT : BAD),
                small(Component.translatable("gui.twinstar.plan.parallel", limit(view.parallel())), TEXT));
        if (!error.isEmpty())           // a start that failed
            summary.addChild(small(errorText(error), BAD));
        else if (!view.fits())          // said before the start is pressed
            summary.addChild(small(errorText("cpu_too_small"), BAD));
        summary.style(s -> s.tooltips(AEKeyRendering.getDisplayName(view.target()),
                Component.literal("×" + fullText(view.amount(), view.target())),
                Component.translatable("gui.twinstar.plan.capacity.tooltip", fullText(view.usage(), null),
                        view.freeCapacity().isEmpty() ? "∞" : fullText(view.freeCapacity(), null))));
    }

    private void fillMissing(PlanView view) {
        List<PlanView.Missing> missing = view.missing();
        if (missing.isEmpty()) {
            missingTitle.setText(Component.translatable(view.complete() ? "gui.twinstar.plan.complete" : "gui.twinstar.plan.less"));
            missingTitle.textStyle(t -> t.textColor(GOOD).fontSize(SMALL));
            return;
        }
        missingCard.removeClass("card");
        missingCard.addClass("card_missing");
        long machines = missing.stream().filter(PlanView.Missing::machine).count();
        long materials = missing.size() - machines;
        missingTitle.setText(machines == 0 ? Component.translatable("gui.twinstar.plan.missing_title", materials)
                : materials == 0 ? Component.translatable("gui.twinstar.plan.missing_machines_title", machines)
                : Component.translatable("gui.twinstar.plan.missing_both_title", materials, machines));
        missingTitle.textStyle(t -> t.textColor(BAD).fontSize(9));
        for (PlanView.Missing m : missing) {
            UIElement text = new UIElement().addChildren(
                    rolling(AEKeyRendering.getDisplayName(m.key()), BAD),
                    small(m.machine() ? Component.translatable("gui.twinstar.plan.machine")
                            : Component.literal("×" + shortText(m.amount(), m.key())), BAD));
            text.layout(l -> l.flexGrow(1).flexShrink(1).minWidth(0).marginLeft(2));
            UIElement entry = new KeyRow(m.key()).addChildren(new KeyIcon(m.key()), text);
            entry.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(18));
            entry.style(s -> s.tooltips(AEKeyRendering.getDisplayName(m.key()), m.machine()
                    ? Component.translatable("gui.twinstar.plan.machine.tooltip").withColor(BAD)
                    : Component.translatable("gui.twinstar.plan.missing", fullText(m.amount(), m.key())).withColor(BAD)));
            missingList.addScrollViewChild(entry);
        }
    }

    /**
     * One item of the crafting tree: indented by depth, a connector to its parent, tinted when
     * something is missing, and (below the target) the player's mark for it.
     */
    private final class TreeRow extends KeyRow {
        private final int depth;

        TreeRow(PlanView.Row row, int index) {
            super(row.key());
            this.depth = Math.min(row.depth(), MAX_INDENT_DEPTH);
            boolean missing = !row.missing().isEmpty() || row.missingMachine() != null;
            if (missing)
                addClass("row_missing");
            // a kept item: how much is kept, editable, in place of where its amount comes from
            boolean keep = row.depth() > 0 && menu.markOf(row.key()) == ItemMark.KEEP;
            UIElement text = new UIElement().addChildren(
                    rolling(AEKeyRendering.getDisplayName(row.key()).copy().append(" ×" + shortText(row.amount(), row.key())), TEXT),
                    keep ? keepEditor(row.key(), index) : rolling(sources(row), TEXT));
            text.layout(l -> l.flexGrow(1).flexShrink(1).minWidth(0).marginLeft(2).gapAll(2));
            addChildren(new KeyIcon(row.key()), text);
            if (row.depth() > 0)
                addChild(markTag(row.key(), index));
            layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(ROW_H).flexShrink(0)
                    .paddingLeft(2 + depth * 10));
            style(s -> s.tooltips(rowTooltip(row).toArray(Component[]::new)));
        }

        @Override
        public void drawBackgroundAdditional(GUIContext context) {
            if (depth == 0)
                return;
            int x = (int) getPositionX() + 2 + depth * 10 - 6;
            int y = (int) getPositionY();
            context.graphics.fill(x, y - 2, x + 1, y + 10, LINE);
            context.graphics.fill(x, y + 9, x + 4, y + 10, LINE);
        }
    }

    /**
     * How much of a kept item is kept: a small field ("all", or an amount in the item's display
     * unit); Enter plans again with it.
     */
    private UIElement keepEditor(AEKey key, int row) {
        int perUnit = Math.max(1, key.getAmountPerUnit());
        BigInteger kept = menu.keepOf(key);
        PixelTextField field = new PixelTextField();
        field.setAnyString();
        field.textFieldStyle(s -> s.textColor(TEXT).errorColor(BAD).cursorColor(KEEP_COLOR).fontSize(SMALL)
                .textShadow(false).focusOverlay(IGuiTexture.EMPTY));
        field.setText(kept == null ? Component.translatable("gui.twinstar.plan.keep.all").getString()
                : OrderUI.fieldText(kept.toString(), perUnit));
        field.addClass("amount_field");
        field.layout(l -> l.width(52).height(9).paddingLeft(2).paddingRight(2).flexShrink(0));
        field.addEventListener(UIEvents.KEY_DOWN, e -> {
            if (!OrderUI.isEnter(e.keyCode))
                return;
            enterHandled = true;
            BigDecimal v = AmountParser.parse(field.getText());
            // anything that is not a number ("all", empty) keeps it whole
            String amount = v == null || v.signum() < 0 ? ""
                    : v.multiply(BigDecimal.valueOf(perUnit)).setScale(0, RoundingMode.CEILING).toBigInteger().toString();
            menu.mark(new PlanMenu.MarkRequest(row, ItemMark.KEEP.ordinal(), amount));
        });
        field.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.keep.field.tooltip")));
        Label label = small(Component.translatable("gui.twinstar.plan.keep"), KEEP_COLOR);
        label.layout(l -> l.marginRight(2).flexShrink(0));
        UIElement line = new UIElement().addChildren(label, field);
        line.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(9));
        return line;
    }

    /** The item's mark for this order (auto / avoid / free / keep, as an icon); a click moves to the next one and plans again. */
    private Button markTag(AEKey key, int row) {
        ItemMark mark = menu.markOf(key);
        String name = mark.name().toLowerCase(Locale.ROOT);
        int color = switch (mark) {
            case AUTO -> DIM;
            case AVOID -> AVOID_COLOR;
            case FREE -> GOOD;
            case KEEP -> KEEP_COLOR;
        };
        Button tag = new Button();
        tag.noText();
        tag.addChild(new MarkIcon(mark, color));
        // on the element itself: above the stylesheet's general button look
        switch (mark) {
            case AVOID -> {
                tag.lss("base-background", "rect(#3a2410, 0, 1, #c08040)");
                tag.lss("hover-background", "rect(#4a3018, 0, 1, #f0b060)");
            }
            case FREE -> {
                tag.lss("base-background", "rect(#10301c, 0, 1, #40a060)");
                tag.lss("hover-background", "rect(#184024, 0, 1, #78e296)");
            }
            case KEEP -> {
                tag.lss("base-background", "rect(#10243a, 0, 1, #4a8cc8)");
                tag.lss("hover-background", "rect(#163050, 0, 1, #5ab4ff)");
            }
            case AUTO -> { }
        }
        tag.layout(l -> l.width(13).height(12).paddingAll(0).flexShrink(0).marginRight(1)
                .justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        tag.setOnClick(e -> menu.mark(new PlanMenu.MarkRequest(row, mark.next().ordinal())));
        tag.style(s -> s.tooltips(Component.translatable("gui.twinstar.plan.mark." + name + ".tooltip"),
                Component.translatable("gui.twinstar.plan.mark.click").withColor(DIM)));
        return tag;
    }

    // ── text ──

    private static Label small(Component text, int color) {
        Label label = new PixelLabel(text);
        label.textStyle(t -> t.textColor(color).fontSize(SMALL));
        return label;
    }

    /** Small text that rolls sideways under the mouse when it is wider than its place. */
    private static Label rolling(Component text, int color) {
        Label label = small(text, color);
        label.textStyle(t -> t.textWrap(TextWrap.HOVER_ROLL));
        label.layout(l -> l.widthPercent(100));
        return label;
    }

    /** Where a row's amount comes from: crafted / stock / infinite source / missing, coloured. */
    private static Component sources(PlanView.Row row) {
        List<MutableComponent> parts = new ArrayList<>();
        if (row.shared())
            parts.add(Component.translatable("gui.twinstar.plan.shared").withColor(DIM));
        if (!row.runs().isEmpty())
            parts.add(Component.translatable("gui.twinstar.plan.runs", shortText(row.runs(), null)).withColor(CRAFT));
        if (!row.stock().isEmpty())
            parts.add(Component.translatable("gui.twinstar.plan.stock", shortText(row.stock(), row.key())).withColor(TEXT));
        if (row.infinite())
            parts.add(Component.translatable("gui.twinstar.plan.infinite").withColor(GOOD));
        if (!row.missing().isEmpty())
            parts.add(Component.translatable("gui.twinstar.plan.missing", shortText(row.missing(), row.key())).withColor(BAD));
        if (row.missingMachine() != null)
            parts.add(Component.translatable("gui.twinstar.plan.missing_machine", AEKeyRendering.getDisplayName(row.missingMachine()))
                    .withColor(BAD));
        return joined(parts);
    }

    /** The parts in one line, with dim dots between. */
    private static Component joined(List<MutableComponent> parts) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0)
                out.append(Component.literal("・").withColor(DIM));
            out.append(parts.get(i));
        }
        return out;
    }

    private static List<Component> rowTooltip(PlanView.Row row) {
        List<Component> lines = new ArrayList<>();
        lines.add(AEKeyRendering.getDisplayName(row.key()));
        lines.add(Component.translatable("gui.twinstar.plan.uses", fullText(row.amount(), row.key())));
        if (!row.runs().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.runs", fullText(row.runs(), null)).withColor(CRAFT));
        if (!row.stock().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.stock", fullText(row.stock(), row.key())));
        if (row.infinite())
            lines.add(Component.translatable("gui.twinstar.plan.infinite").withColor(GOOD));
        if (!row.missing().isEmpty())
            lines.add(Component.translatable("gui.twinstar.plan.missing", fullText(row.missing(), row.key())).withColor(BAD));
        if (row.missingMachine() != null)
            lines.add(Component.translatable("gui.twinstar.plan.missing_machine", AEKeyRendering.getDisplayName(row.missingMachine()))
                    .withColor(BAD));
        if (row.shared())
            lines.add(Component.translatable("gui.twinstar.plan.shared.tooltip").withColor(DIM));
        return lines;
    }

    // ── amounts (sent as digits when they fit a long, else already in unit form) ──

    private static @Nullable BigInteger digits(String s) {
        if (s.isEmpty() || s.length() > 19)
            return null;
        for (int i = 0; i < s.length(); i++)
            if (!Character.isDigit(s.charAt(i)))
                return null;
        return new BigInteger(s);
    }

    static String shortText(String s, @Nullable AEKey key) {
        BigInteger v = digits(s);
        if (v == null)
            return s;
        if (key != null && key.getAmountPerUnit() > 1)
            return BigAmountFormat.shortForm(v, key.getAmountPerUnit(), unit(key));
        return BigAmountFormat.unitForm(v);
    }

    static String fullText(String s, @Nullable AEKey key) {
        BigInteger v = digits(s);
        if (v == null)
            return s;
        if (key != null && key.getAmountPerUnit() > 1)
            return BigAmountFormat.fullForm(v, key.getAmountPerUnit(), unit(key));
        return NumberFormat.getInstance().format(v);
    }

    private static String unit(AEKey key) {
        String u = key.getUnitSymbol();
        return u != null ? u : "";
    }

    private static String limit(String s) {
        return s.isEmpty() ? "∞" : shortText(s, null);
    }

    private static String millis(long nanos) {
        double ms = nanos / 1e6;
        return ms < 10 ? String.format("%.1f", ms) : String.valueOf(Math.round(ms));
    }

    private static Component errorText(String code) {
        return Component.translatableWithFallback("gui.twinstar.plan.error." + code.toLowerCase(Locale.ROOT), code);
    }
}
