package com.lumina.twinstar.mixin.client;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.common.MEStorageMenu;
import com.lumina.twinstar.client.TheoryScreen;
import com.lumina.twinstar.client.UsageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The theory layer and the usage list, from the terminals too (ME terminal, crafting, pattern and the wireless ones): their buttons
 * go on the left toolbar of the terminal, and both come back to the same terminal.
 */
@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class MEStorageScreenMixin<C extends MEStorageMenu> extends AEBaseScreen<C> {
    public MEStorageScreenMixin(C menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void twinstar$toolbar(C menu, Inventory playerInventory, Component title, ScreenStyle style, CallbackInfo ci) {
        addToLeftToolbar(TheoryScreen.button(0, 0, () -> TheoryScreen.open(null, () -> this)));
        addToLeftToolbar(UsageScreen.button(0, 0, () -> UsageScreen.open(() -> this)));
    }
}
