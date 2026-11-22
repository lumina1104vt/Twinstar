package com.lumina.twinstar.client;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.PixelIcon;
import com.lumina.twinstar.client.ui.TheoryGraphView;
import com.lumina.twinstar.theory.TheoryCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * The theory viewer, full screen, over the AE2 menu that is open (the order screen's, the hub's):
 * the server answers for that menu's grid, so the menu stays open underneath. Asks the engine's
 * state every second and follows it live.
 */
public class TheoryScreen extends Screen implements KeyHover {
    private static final int STATUS_EVERY_TICKS = 20;

    private final Supplier<Screen> back;
    private final EditBox search;
    private final TheoryGraphView view;
    private int ticks;

    /**
     * @param start the item shown first (null: the player searches one)
     * @param back  the screen to go back to (a new one for LDLib2 screens, which drop their UI when left)
     */
    public TheoryScreen(@Nullable AEKey start, Supplier<Screen> back) {
        super(Component.translatable("gui.twinstar.theory.title"));
        this.back = back;
        TheoryCache.clear();
        TheoryCache.askStatus();
        this.search = new EditBox(Minecraft.getInstance().font, 0, 0, 160, 12, Component.translatable("gui.twinstar.theory.search"));
        search.setBordered(false);
        search.setMaxLength(64);
        this.view = new TheoryGraphView(start, search, this::onClose);
    }

    /** Opens the viewer from the screen now open, coming back to {@code back}. */
    public static void open(@Nullable AEKey start, Supplier<Screen> back) {
        Minecraft.getInstance().setScreen(new TheoryScreen(start, back));
    }

    /** A 16×16 button for other screens' toolbars that opens the viewer. */
    public static Button button(int x, int y, Runnable onPress) {
        Button b = new Button(x, y, 16, 16, Component.translatable("gui.twinstar.theory.open"), btn -> onPress.run(),
                supplier -> supplier.get()) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                // AE2's toolbar background lies at z 1: above it, like AE2's own icons
                g.pose().pushPose();
                g.pose().translate(0, 0, 10);
                g.fill(getX(), getY(), getX() + width, getY() + height, isHoveredOrFocused() ? 0xFFA082F0 : 0xFF4E4474);
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, isHoveredOrFocused() ? 0xFF2C2642 : 0xFF1E1C2C);
                PixelIcon.draw(g, PixelIcon.LENS, getX() + 4, getY() + 4, 0xFFDEDEEA);
                g.pose().popPose();
            }
        };
        b.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.theory.open.tooltip")));
        return b;
    }

    @Override
    public @Nullable KeyHit keyAt(double mouseX, double mouseY) {
        return view.keyAt(mouseX, mouseY);
    }

    @Override
    protected void init() {
        view.resize(width, height);     // the search box is drawn and fed by hand: the graph takes the mouse first
    }

    @Override
    public void tick() {
        if (ticks++ % STATUS_EVERY_TICKS == 0)
            TheoryCache.askStatus();
        view.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        view.render(graphics, mouseX, mouseY);
        search.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        // the graph fills the screen
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        view.blurSearch(mouseX, mouseY);
        return view.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return view.mouseDragged(button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return view.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        return view.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search.isFocused() && keyCode != 256) {     // Esc still leaves
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!search.isFocused()) {
            search.setFocused(true);    // typing anywhere searches
            search.setValue("");
        }
        return search.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null)
            minecraft.setScreen(back.get());
    }
}
