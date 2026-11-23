package com.lumina.twinstar.client;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.PlanUI;
import com.lumina.twinstar.order.PlanMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

/**
 * The twin-star plan screen: a plain container screen holding the LDLib2 UI of {@link PlanUI}.
 * The UI is set on the menu here (LDLib2 gives every menu a place for one) and LDLib2 attaches it
 * to this screen when the screen is initialised; this screen only sizes itself to it and keeps it
 * following the menu.
 */
public class PlanScreen extends net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<PlanMenu> implements KeyHover {
    private final PlanUI ui;

    public PlanScreen(PlanMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.ui = new PlanUI(menu, playerInventory.player,
                () -> openGraph(menu, playerInventory, title));
        ((IModularUIHolderMenu) (AbstractContainerMenu) menu).setModularUI(ui.modularUI());
    }

    /** The crafting graph; a failure is told in the chat and logged (the UI's click handling would hide it). */
    private void openGraph(PlanMenu menu, Inventory playerInventory, Component title) {
        try {
            minecraft.setScreen(new PlanGraphScreen(menu, playerInventory, title));
        } catch (RuntimeException | StackOverflowError e) {
            Twinstar.LOGGER.error("[雙星] 合成圖打不開", e);
            playerInventory.player.displayClientMessage(Component.translatable("gui.twinstar.graph.failed", e.toString()), false);
        }
    }

    @Override
    public @Nullable KeyHit keyAt(double mouseX, double mouseY) {
        return ui.keyAt();
    }

    @Override
    protected void init() {
        imageWidth = PlanUI.WIDTH;
        imageHeight = PlanUI.HEIGHT;
        super.init();
        ui.refresh();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        ui.beginKey();
        return super.keyPressed(keyCode, scanCode, modifiers) || ui.enterPressed(keyCode);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ui.refresh();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        ui.updateStartButton();     // Shift is read every frame: held, the start button is "bookmark missing"
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // drawn by the LDLib2 UI
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // drawn by the LDLib2 UI
    }
}
