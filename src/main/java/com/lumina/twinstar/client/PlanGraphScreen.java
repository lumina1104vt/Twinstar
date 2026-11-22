package com.lumina.twinstar.client;

import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.PlanGraphView;
import com.lumina.twinstar.order.PlanMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;

/**
 * The plan's crafting graph, full screen, over the open plan screen's menu (the menu stays open on
 * the server). Back or Esc builds the plan screen again for the same menu: LDLib2 disposes a screen's
 * UI when the screen is left, so the old one cannot be shown again.
 */
public class PlanGraphScreen extends Screen implements KeyHover {
    private final PlanMenu menu;
    private final Inventory inventory;
    private final Component planTitle;
    private final PlanGraphView view;

    public PlanGraphScreen(PlanMenu menu, Inventory inventory, Component planTitle) {
        super(Component.translatable("gui.twinstar.graph.title"));
        this.menu = menu;
        this.inventory = inventory;
        this.planTitle = planTitle;
        this.view = new PlanGraphView(menu, this::onClose);
    }

    @Override
    public @Nullable KeyHit keyAt(double mouseX, double mouseY) {
        return view.keyAt(mouseX, mouseY);
    }

    @Override
    protected void init() {
        view.resize(width, height);
    }

    @Override
    public void tick() {
        // the server closed the menu (the hub went away, …): nothing to go back to
        if (minecraft != null && minecraft.player != null && minecraft.player.containerMenu != menu) {
            minecraft.setScreen(null);
            return;
        }
        view.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        view.render(graphics, mouseX, mouseY);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // the graph fills the screen
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return view.mouseScrolled(mouseX, mouseY, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null)
            minecraft.setScreen(new PlanScreen(menu, inventory, planTitle));
    }
}
