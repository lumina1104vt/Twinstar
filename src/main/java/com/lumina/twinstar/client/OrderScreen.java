package com.lumina.twinstar.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.client.gui.implementations.AESubScreen;
import com.lumina.twinstar.client.ui.BackArrow;
import com.lumina.twinstar.client.ui.PixelIcon;
import com.lumina.twinstar.client.ui.PlanUI;
import com.lumina.twinstar.client.ui.UiKit;
import com.lumina.twinstar.client.ui.UiKit.PixelButton;
import com.lumina.twinstar.client.ui.UiKit.PixelSwitch;
import com.lumina.twinstar.order.OrderMenu;
import com.lumina.twinstar.util.AmountParser;
import com.lumina.twinstar.util.BigAmountFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The twin-star order screen: header with the rotating twin star, the item card (what the network has of it), the amount (any size
 * up to the order limit; ± buttons as AE2's, shift or ctrl for 1 16 32 64), the order options, and "original screen" / "plan →".
 * Drawn with {@link UiKit} (1.20.1 has no LDLib2); the layout is the 1.21 one, in pixels from the panel's corner.
 */
public class OrderScreen extends AbstractContainerScreen<OrderMenu> {
    public static final int WIDTH = 178, HEIGHT = 240;
    private static final int BAD = 0xFFFF7474, BUSY = 0xFFF0B060, GOOD = 0xFF78E296, DIM = 0xFF8C8CA2, TEXT = 0xFFDEDEEA;
    private static final long[] STEPS_1000 = {1, 10, 100, 1000};
    private static final long[] STEPS_64 = {1, 16, 32, 64};
    private static final int[] STEP_WIDTHS = {22, 28, 32, 38};

    // the options stay as the player left them (this session)
    private static boolean lastCraftMissing, lastBestEffort, lastAutoStart;

    private final Inventory playerInventory;
    private final PixelButton[] stepButtons = new PixelButton[8];
    private EditBox field;
    private PixelSwitch craftMissing, bestEffort, autoStart;
    private PixelButton next;

    private @Nullable AEKey shownKey;
    private @Nullable String appliedInitialAmount;
    private String fieldValue = "1";
    private int amountPerUnit = 1;
    private boolean shiftSteps;

    public OrderScreen(OrderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.playerInventory = playerInventory;
        menu.hideItemSlot();    // the screen shows the item itself
    }

    @Override
    protected void init() {
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        super.init();
        int x = leftPos, y = topPos;

        PixelButton theory = new PixelButton(x + 136, y + 9, 16, 16, Component.empty(),
                (g, bx, by, w, h, hot) -> PixelIcon.draw(g, PixelIcon.LENS, bx + 4, by + 4, TEXT), () -> {
            var stack = menu.getWhatToCraft();
            TheoryScreen.open(stack == null ? null : stack.what(), () -> new OrderScreen(menu, playerInventory, title));
        });
        theory.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.theory.open")));
        addRenderableWidget(theory);
        PixelButton close = new PixelButton(x + 155, y + 9, 16, 16, Component.empty(),
                (g, bx, by, w, h, hot) -> BackArrow.draw(g, bx + 3, by + 4), AESubScreen::goBack);
        close.setTooltip(Tooltip.create(menu.getHost().getMainMenuIcon().getHoverName()));
        addRenderableWidget(close);

        // amount: +1 +10 +100 +1000 over the field, -1 ... -1000 under it
        for (int row = 0; row < 2; row++) {
            int bx = x + 20;
            for (int i = 0; i < 4; i++) {
                int index = row * 4 + i;
                PixelButton b = new PixelButton(bx, y + (row == 0 ? 93 : 135), STEP_WIDTHS[i], 20, Component.empty(), () -> addSteps(index));
                stepButtons[index] = b;
                addRenderableWidget(b);
                bx += STEP_WIDTHS[i] + 6;
            }
        }
        field = new EditBox(font, x + 24, y + 120, 130, 10, Component.translatable("gui.twinstar.order.amount"));
        field.setBordered(false);
        field.setMaxLength(120);
        field.setTextColor(TEXT);
        field.setValue(fieldValue);
        field.setResponder(text -> {
            fieldValue = text;
            field.setTextColor(isValid() ? TEXT : BAD);
        });
        addRenderableWidget(field);

        craftMissing = option(x + 10, y + 170, "craftMissing", lastCraftMissing, () -> lastCraftMissing = !lastCraftMissing);
        bestEffort = option(x + 10, y + 184, "bestEffort", lastBestEffort, () -> lastBestEffort = !lastBestEffort);
        autoStart = option(x + 10, y + 198, "autoStart", lastAutoStart, () -> lastAutoStart = !lastAutoStart);

        PixelButton original = new PixelButton(x + 7, y + 214, 70, 20, Component.translatable("gui.twinstar.order.original"), this::useOriginal);
        original.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.order.original.tooltip")));
        addRenderableWidget(original);
        next = new PixelButton(x + 109, y + 214, 62, 20, Component.translatable("gui.twinstar.order.plan"), this::confirm);
        addRenderableWidget(next);
        shiftSteps = !shiftSteps;       // captions are written again
        updateCaptions();
        refresh();
    }

    private PixelSwitch option(int x, int y, String id, boolean on, Runnable onChange) {
        PixelSwitch s = new PixelSwitch(x, y, Component.translatable("gui.twinstar.order." + id), on, onChange);
        s.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.order." + id + ".tooltip")));
        addRenderableWidget(s);
        return s;
    }

    // ── content ──

    /** Follows the menu (item, the starting amount) and shift. */
    private void refresh() {
        GenericStack what = menu.getWhatToCraft();
        if (what != null) {
            AEKey key = what.what();
            if (!key.equals(shownKey)) {
                shownKey = key;
                amountPerUnit = Math.max(1, key.getAmountPerUnit());
            }
            // the item and the starting amount may arrive in either order: follow the amount once it changes
            if (!menu.initialAmount.equals(appliedInitialAmount)) {
                field.setValue(PlanUI.fieldText(menu.initialAmount, amountPerUnit));
                appliedInitialAmount = menu.initialAmount;
            }
        }
        boolean shift = Screen.hasShiftDown() || Screen.hasControlDown();
        if (shift != shiftSteps)
            updateCaptions();
        shiftSteps = shift;
        next.active = isValid();
    }

    private void updateCaptions() {
        boolean shift = Screen.hasShiftDown() || Screen.hasControlDown();
        shiftSteps = shift;
        for (int i = 0; i < 8; i++) {
            long s = step(i);
            stepButtons[i].setMessage(Component.literal((s > 0 ? "+" : "") + s));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        field.tick();
        refresh();
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        g.blit(UiKit.ORDER_PANEL, x, y, 0, 0, WIDTH, HEIGHT, WIDTH, HEIGHT);
        TwinStarBadge.draw(g, x + 3, y + 1);
        g.drawString(font, Component.translatable("gui.twinstar.order.title").withStyle(s -> s.withBold(true)), x + 38, y + 12, UiKit.TITLE, false);

        // the item card
        UiKit.nineSlice(g, UiKit.INSET, x + 7, y + 33, 164, 45, 5);
        UiKit.nineSlice(g, UiKit.INSET_TRACK, x + 10, y + 36, 18, 18, 2);
        GenericStack what = menu.getWhatToCraft();
        if (what != null) {
            AEKey key = what.what();
            AEKeyRendering.drawInGui(minecraft, g, x + 11, y + 37, key);
            int tx = x + 32, room = 134;
            g.drawString(font, font.plainSubstrByWidth(AEKeyRendering.getDisplayName(key).getString(), room), tx, y + 36, TEXT, false);
            Component stock = menu.infinite ? PlanUI.coloured(Component.translatable("gui.twinstar.order.infinite"), GOOD)
                    : Component.translatable("gui.twinstar.order.stock", shortAmount(menu.stock, key));
            g.drawString(font, stock, tx, y + 47, TEXT, false);
        }
        Component routes = menu.routes > 0 ? Component.translatable("gui.twinstar.order.routes", menu.routes)
                : PlanUI.coloured(Component.translatable("gui.twinstar.order.no_routes"), DIM);
        g.drawString(font, routes, x + 32, y + 58, TEXT, false);
        g.drawString(font, PlanUI.coloured(Component.translatable(menu.upToDate ? "gui.twinstar.order.up_to_date"
                : "gui.twinstar.order.updating"), menu.upToDate ? GOOD : BUSY), x + 32, y + 69, TEXT, false);

        g.drawString(font, Component.translatable("gui.twinstar.order.amount"), x + 7, y + 82, TEXT, false);
        UiKit.box(g, x + 20, y + 116, 138, 16, 0xFF040408, UiKit.EDGE);
        g.drawString(font, Component.translatable("gui.twinstar.order.options"), x + 7, y + 159, TEXT, false);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        // drawn in renderBg
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        // tooltips of the card
        GenericStack what = menu.getWhatToCraft();
        if (what != null) {
            AEKey key = what.what();
            if (UiKit.inside(mouseX, mouseY, leftPos + 10, topPos + 36, 18, 18))
                g.renderTooltip(font, AEKeyRendering.getTooltip(key), Optional.empty(), mouseX, mouseY);
            else if (!menu.infinite && UiKit.inside(mouseX, mouseY, leftPos + 32, topPos + 47, 134, 10))
                g.renderTooltip(font, List.of(Component.literal(fullAmount(menu.stock, key))), Optional.empty(), mouseX, mouseY);
        }
        if (UiKit.inside(mouseX, mouseY, leftPos + 32, topPos + 69, 134, 10))
            g.renderTooltip(font, List.of(Component.translatable(menu.upToDate
                    ? "gui.twinstar.order.up_to_date.tooltip" : "gui.twinstar.order.updating.tooltip")), Optional.empty(), mouseX, mouseY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (field.isFocused() && keyCode != GLFW.GLFW_KEY_ESCAPE) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                confirm();
                return true;
            }
            return field.keyPressed(keyCode, scanCode, modifiers) || field.canConsumeInput();    // E must not close it
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── amount ──

    /** The entered amount in base units; null if it is not a whole amount. */
    private @Nullable BigInteger exactValue() {
        BigDecimal v = AmountParser.parse(fieldValue);
        if (v == null)
            return null;
        BigDecimal base = v.multiply(BigDecimal.valueOf(amountPerUnit)).stripTrailingZeros();
        return base.scale() > 0 ? null : base.toBigInteger();
    }

    private boolean isValid() {
        BigInteger v = exactValue();
        return v != null && v.signum() > 0 && v.compareTo(AmountParser.MAX_EXTERNAL) <= 0;
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
        BigDecimal current = AmountParser.parse(fieldValue);
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
        field.setValue(PlanUI.displayText(next));
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
