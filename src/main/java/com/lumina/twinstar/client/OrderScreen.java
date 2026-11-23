package com.lumina.twinstar.client;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolderMenu;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.OrderUI;
import com.lumina.twinstar.order.OrderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

/**
 * The twin-star order screen: a plain container screen holding the LDLib2 UI of {@link OrderUI}
 * (attached by LDLib2 when the screen is initialised, as for {@link PlanScreen}).
 */
public class OrderScreen extends AbstractContainerScreen<OrderMenu> implements KeyHover {
    private final OrderUI ui;

    public OrderScreen(OrderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.ui = new OrderUI(menu, playerInventory.player, () -> {
            var stack = menu.getWhatToCraft();
            TheoryScreen.open(stack == null ? null : stack.what(), () -> new OrderScreen(menu, playerInventory, title));
        });
        ((IModularUIHolderMenu) (AbstractContainerMenu) menu).setModularUI(ui.modularUI());
        menu.hideItemSlot();    // the UI shows the item itself
    }

    @Override
    public @Nullable KeyHit keyAt(double mouseX, double mouseY) {
        return ui.keyAt();
    }

    @Override
    protected void init() {
        imageWidth = OrderUI.WIDTH;
        imageHeight = OrderUI.HEIGHT;
        super.init();
        ui.refresh();
        ui.focusAmount();
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
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // drawn by the LDLib2 UI
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // drawn by the LDLib2 UI
    }
}
