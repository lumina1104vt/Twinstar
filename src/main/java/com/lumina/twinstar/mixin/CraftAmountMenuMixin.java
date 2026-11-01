package com.lumina.twinstar.mixin;

import appeng.api.stacks.AEKey;
import appeng.menu.locator.MenuHostLocator;
import appeng.menu.me.crafting.CraftAmountMenu;
import com.lumina.twinstar.order.OrderFlow;
import com.lumina.twinstar.order.OrderMenu;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On a grid with a working hub the twin-star order screen opens instead of AE2's amount screen
 * (every way in - AE2's terminals, wireless terminals, recipe viewers - goes through here). Its
 * "original screen" button comes back here once without being redirected, and from there on AE2's
 * own order path (as other mods extend it) runs untouched.
 */
@Mixin(CraftAmountMenu.class)
public abstract class CraftAmountMenuMixin {
    @Inject(method = "open", at = @At("HEAD"), cancellable = true)
    private static void twinstar$orderScreen(ServerPlayer player, MenuHostLocator locator, AEKey whatToCraft, int initialAmount,
                                             CallbackInfo ci) {
        if (OrderFlow.replaces(player, locator)) {
            OrderMenu.open(player, locator, whatToCraft, initialAmount);
            ci.cancel();
        }
    }
}
