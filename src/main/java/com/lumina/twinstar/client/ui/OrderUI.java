package com.lumina.twinstar.client.ui;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.implementations.AESubScreen;
import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHolder;
import com.lumina.twinstar.order.OrderMenu;
import com.lumina.twinstar.util.AmountParser;
import com.lumina.twinstar.util.BigAmountFormat;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The twin-star order screen, built with LDLib2's UI (layout A): header with the rotating twin star,
 * the item card (what the network has of it), the amount (any size up to the order limit; ± buttons
 * as AE2's, shift or ctrl for 1 16 32 64), the order options, and "original screen" / "plan →". The
 * look is in assets/twinstar/lss/twinstar.lss; {@link #refresh()} follows the menu.
 */
public class OrderUI {
    public static final int WIDTH = 178, HEIGHT = 240;
    private static final int TEXT = 0xFFDEDEEA, DIM = 0xFF8C8CA2, GOOD = 0xFF78E296, BUSY = 0xFFF0B060,
            BAD = 0xFFFF7474, CURSOR = 0xFFA082F0;
    private static final long[] STEPS_1000 = {1, 10, 100, 1000};
    private static final long[] STEPS_64 = {1, 16, 32, 64};
    private static final int[] STEP_WIDTHS = {22, 28, 32, 38};

    // the options stay as the player left them (this session)
    private static boolean lastCraftMissing;
    private static boolean lastBestEffort;
    private static boolean lastAutoStart;

    private final OrderMenu menu;
    private final ModularUI modularUI;
    private final UIElement icon = new UIElement();
    private final Label name = new PixelLabel();
    private final Label stock = new PixelLabel();
    private final Label routes = new PixelLabel();
    private final Label engine = new PixelLabel();
    private final PixelTextField field = new PixelTextField();
    private final List<Label> stepCaptions = new ArrayList<>(8);
    private final PixelToggle craftMissing;
    private final PixelToggle bestEffort;
    private final PixelToggle autoStart;
    private final Button next = PixelLabel.button(Component.translatable("gui.twinstar.order.plan"));

    private @Nullable AEKey shownKey;
    private @Nullable String appliedInitialAmount;
    private int amountPerUnit = 1;
    private boolean shiftSteps;

    /** @param openTheory opens the theory viewer on the item (a screen of its own) */
    public OrderUI(OrderMenu menu, Player player, Runnable openTheory) {
        this.menu = menu;
        craftMissing = option("craftMissing", lastCraftMissing, () -> lastCraftMissing = !lastCraftMissing);
        bestEffort = option("bestEffort", lastBestEffort, () -> lastBestEffort = !lastBestEffort);
        autoStart = option("autoStart", lastAutoStart, () -> lastAutoStart = !lastAutoStart);

        UIElement root = new UIElement().addClass("order_root");
        root.layout(l -> l.width(WIDTH).height(HEIGHT).paddingLeft(7).paddingRight(7).paddingTop(1).paddingBottom(6));
        root.addChildren(header(openTheory), card(),
                section("gui.twinstar.order.amount"), amount(),
                section("gui.twinstar.order.options"), craftMissing, bestEffort, autoStart,
                new UIElement().layout(l -> l.flexGrow(1)), footer());
        this.modularUI = new ModularUI(UI.of(root, PlanUI.STYLESHEET), player);
    }

    public ModularUI modularUI() {
        return modularUI;
    }

    // ── layout ──

    private UIElement header(Runnable openTheory) {
        Label title = new PixelLabel(Component.translatable("gui.twinstar.order.title").withStyle(s -> s.withBold(true)));
        title.addClass("title");
        title.layout(l -> l.flexGrow(1).marginLeft(3));
        Button close = new Button();
        close.noText();
        close.addChild(new BackArrow());
        close.setOnClick(e -> AESubScreen.goBack());
        close.style(s -> s.tooltips(menu.getHost().getMainMenuIcon().getHoverName()));
        close.layout(l -> l.width(16).height(16).paddingAll(0).justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        Button theory = new Button();
        theory.noText();
        theory.addChild(new PixelIcon(PixelIcon.LENS, PlanUI.TEXT));
        theory.setOnClick(e -> openTheory.run());
        theory.style(s -> s.tooltips(Component.translatable("gui.twinstar.theory.open"),
                Component.translatable("gui.twinstar.theory.open.order").withColor(PlanUI.DIM)));
        theory.layout(l -> l.width(16).height(16).paddingAll(0).marginRight(3)
                .justifyContent(AlignContent.CENTER).alignItems(AlignItems.CENTER));
        UIElement header = new UIElement().addChildren(new TwinStarElement().layout(l -> l.marginLeft(-4)), title,
                theory, close);
        header.layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(32).flexShrink(0));
        return header;
    }

    /** The item and what the network has of it. */
    private UIElement card() {
        icon.addClass("slot");
        icon.layout(l -> l.width(18).height(18).paddingAll(1).flexShrink(0));
        UIElement info = new UIElement().addChildren(name, stock, routes, engine);
        info.layout(l -> l.flexGrow(1).flexShrink(1).minWidth(0).marginLeft(4));
        for (Label line : List.of(name, stock, routes, engine))
            line.textStyle(t -> t.textColor(TEXT).fontSize(9));
        UIElement card = new KeyCard().addClass("card").addChildren(icon, info);
        card.layout(l -> l.flexDirection(FlexDirection.ROW).height(45).paddingAll(3).flexShrink(0));
        return card;
    }

    /** The card of the item: the mouse on it (icon, name, amounts) means the item. */
    private final class KeyCard extends UIElement implements KeyHolder {
        @Override
        public @Nullable AEKey heldKey() {
            return shownKey;
        }
    }

    /** The material under the mouse (JEI and EMI bookmark it with their A key), or null. */
    public @Nullable KeyHit keyAt() {
        return KeyRow.under(modularUI);
    }

    private static Label section(String key) {
        Label label = new PixelLabel(Component.translatable(key));
        label.layout(l -> l.marginTop(4).marginBottom(2));
        return label;
    }

    /** +1 +10 +100 +1000, the field, -1 ... -1000: AE2's amount entry. */
    private UIElement amount() {
        field.addClass("amount_field");
        field.setAnyString();
        field.textFieldStyle(s -> s.textColor(TEXT).errorColor(BAD).cursorColor(CURSOR).fontSize(9)
                .textShadow(false).focusOverlay(IGuiTexture.EMPTY));
        field.setTextResponder(text -> validate());
        field.layout(l -> l.height(16).marginVertical(3).paddingLeft(4).paddingRight(4));
        field.addEventListener(UIEvents.KEY_DOWN, e -> {
            if (e.keyCode == GLFW.GLFW_KEY_ENTER || e.keyCode == GLFW.GLFW_KEY_KP_ENTER)
                confirm();
        });
        field.setText("1");
        UIElement block = new UIElement().addChildren(steps(true), field, steps(false));
        block.layout(l -> l.width(138).marginLeft(13).flexShrink(0));
        return block;
    }

    private UIElement steps(boolean plus) {
        UIElement row = new UIElement();
        for (int i = 0; i < 4; i++) {
            int index = plus ? i : i + 4;
            Button b = PixelLabel.button(Component.empty());
            int width = STEP_WIDTHS[i];
            b.layout(l -> l.width(width).height(20));
            b.setOnClick(e -> addSteps(index));
            stepCaptions.add((Label) b.getChildren().get(b.getChildren().size() - 1));
            row.addChild(b);
        }
        row.layout(l -> l.flexDirection(FlexDirection.ROW).gapAll(6).height(20));
        return row;
    }

    private PixelToggle option(String id, boolean on, Runnable onChange) {
        PixelToggle toggle = new PixelToggle(Component.translatable("gui.twinstar.order." + id), on, onChange);
        toggle.style(s -> s.tooltips(Component.translatable("gui.twinstar.order." + id + ".tooltip")));
        toggle.layout(l -> l.marginLeft(3).marginBottom(2).flexShrink(0));
        return toggle;
    }

    private UIElement footer() {
        Button original = PixelLabel.button(Component.translatable("gui.twinstar.order.original"));
        original.setOnClick(e -> useOriginal());
        original.style(s -> s.tooltips(Component.translatable("gui.twinstar.order.original.tooltip")));
        original.layout(l -> l.width(70).height(20));
        next.setOnClick(e -> confirm());
        next.layout(l -> l.width(62).height(20));
        UIElement footer = new UIElement().addChildren(original, new UIElement().layout(l -> l.flexGrow(1)), next);
        footer.layout(l -> l.flexDirection(FlexDirection.ROW).height(20).flexShrink(0));
        return footer;
    }

    // ── content ──

    /** Follows the menu (item, what the network has of it, the starting amount) and shift. Every client tick. */
    public void refresh() {
        GenericStack what = menu.getWhatToCraft();
        if (what != null) {
            AEKey key = what.what();
            if (!key.equals(shownKey)) {
                shownKey = key;
                amountPerUnit = Math.max(1, key.getAmountPerUnit());
                icon.clearAllChildren();
                icon.addChild(new KeyIcon(key));
                icon.style(s -> s.tooltips(AEKeyRendering.getTooltip(key).toArray(Component[]::new)));
                name.setText(AEKeyRendering.getDisplayName(key));
            }
            // the item and the starting amount may arrive in either order: follow the amount once it changes
            if (!menu.initialAmount.equals(appliedInitialAmount)) {
                field.setText(fieldText(menu.initialAmount, amountPerUnit));
                appliedInitialAmount = menu.initialAmount;
            }
            stock.setText(menu.infinite ? Component.translatable("gui.twinstar.order.infinite").withColor(GOOD)
                    : Component.translatable("gui.twinstar.order.stock", shortAmount(menu.stock, key)));
            stock.style(s -> s.tooltips(menu.infinite ? new Component[0]
                    : new Component[]{Component.literal(fullAmount(menu.stock, key))}));
        }
        routes.setText(menu.routes > 0 ? Component.translatable("gui.twinstar.order.routes", menu.routes)
                : Component.translatable("gui.twinstar.order.no_routes").withColor(DIM));
        engine.setText(Component.translatable(menu.upToDate ? "gui.twinstar.order.up_to_date" : "gui.twinstar.order.updating")
                .withColor(menu.upToDate ? GOOD : BUSY));
        engine.style(s -> s.tooltips(Component.translatable(menu.upToDate
                ? "gui.twinstar.order.up_to_date.tooltip" : "gui.twinstar.order.updating.tooltip")));
        boolean shift = Screen.hasShiftDown() || Screen.hasControlDown();
        if (shift != shiftSteps || stepCaptions.get(0).getValue().getString().isEmpty()) {
            shiftSteps = shift;
            for (int i = 0; i < 8; i++) {
                long s = step(i);
                stepCaptions.get(i).setText(Component.literal((s > 0 ? "+" : "") + s));
            }
        }
        next.setActive(isValid());
    }

    // ── amount ──

    /** The entered amount in base units; null if it is not a whole amount. */
    private @Nullable BigInteger exactValue() {
        BigDecimal v = AmountParser.parse(field.getValue());
        if (v == null)
            return null;
        BigDecimal base = v.multiply(BigDecimal.valueOf(amountPerUnit)).stripTrailingZeros();
        return base.scale() > 0 ? null : base.toBigInteger();
    }

    private boolean isValid() {
        BigInteger v = exactValue();
        return v != null && v.signum() > 0 && v.compareTo(AmountParser.MAX_EXTERNAL) <= 0;
    }

    private void validate() {
        field.textFieldStyle(s -> s.textColor(isValid() ? TEXT : BAD));
    }

    private long step(int index) {
        long s = (shiftSteps ? STEPS_64 : STEPS_1000)[index % 4];
        return index < 4 ? s : -s;
    }

    /**
     * Adds a step in displayed units, as AE2's amount buttons do: kept between 1 and the order
     * limit, and from exactly 1 a step larger than 1 replaces it (1, +1000 gives 1000, not 1001).
     */
    private void addSteps(int index) {
        BigDecimal current = AmountParser.parse(field.getValue());
        if (current == null)
            current = BigDecimal.ZERO;
        long delta = step(index);
        BigDecimal next = current.add(BigDecimal.valueOf(delta));
        BigDecimal max = new BigDecimal(AmountParser.MAX);
        if (next.compareTo(BigDecimal.ONE) < 0)
            next = BigDecimal.ONE;
        else if (next.compareTo(max) > 0)
            next = max;
        else if (current.compareTo(BigDecimal.ONE) == 0 && delta > 1)
            next = next.subtract(BigDecimal.ONE);
        field.setText(displayText(next));
    }

    /**
     * An amount (base units, decimal text) as the field shows it: plain for a readable number, else
     * as mantissa and exponent ({@code 1e10000}), which the field reads back exactly.
     */
    static String fieldText(String baseUnits, int perUnit) {
        try {
            return displayText(new BigDecimal(new BigInteger(baseUnits)).divide(BigDecimal.valueOf(Math.max(1, perUnit))));
        } catch (ArithmeticException | NumberFormatException e) {
            return "1";
        }
    }

    private static String displayText(BigDecimal v) {
        v = v.stripTrailingZeros();
        int exponent = -v.scale();
        return exponent >= 6 && v.precision() <= 20 ? v.unscaledValue() + "e" + exponent : v.toPlainString();
    }

    private static String shortAmount(String baseUnits, AEKey key) {
        try {
            return BigAmountFormat.shortForm(new BigInteger(baseUnits), key.getAmountPerUnit(), unit(key));
        } catch (NumberFormatException e) {
            return baseUnits;
        }
    }

    private static String fullAmount(String baseUnits, AEKey key) {
        try {
            return BigAmountFormat.fullForm(new BigInteger(baseUnits), key.getAmountPerUnit(), unit(key));
        } catch (NumberFormatException e) {
            return baseUnits;
        }
    }

    private static String unit(AEKey key) {
        return key.getUnitSymbol() != null ? key.getUnitSymbol() : "";
    }

    // ── actions ──

    private void confirm() {
        BigInteger v = exactValue();
        if (v != null && isValid())
            menu.confirm(new OrderMenu.Request(v.toString(), craftMissing.isOn(), bestEffort.isOn(), autoStart.isOn()));
    }

    private void useOriginal() {
        BigInteger v = exactValue();
        menu.useOriginal(v != null ? v.toString() : "1");
    }
}
