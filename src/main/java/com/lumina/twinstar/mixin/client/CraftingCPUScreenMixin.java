package com.lumina.twinstar.mixin.client;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.me.crafting.CraftingCPUScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lumina.twinstar.menu.BigStatusAmounts;
import com.lumina.twinstar.menu.BigStatusEntry;
import com.lumina.twinstar.menu.PullFromNetworkMenu;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.TimeUnit;

/**
 * The estimated time in the CPU status title: when AE2's estimate overflows (an astronomical order
 * at today's speed never finishes) it shows ∞ instead of 2562047:47:16. And the "pull from network"
 * button beside the panel, shown while returns of a hub job may have gone into network storage.
 */
@Mixin(value = CraftingCPUScreen.class, remap = false)
public abstract class CraftingCPUScreenMixin extends AEBaseScreen<CraftingCPUMenu> {
    @Unique
    private static final long twinstar$SATURATED_MILLIS = TimeUnit.NANOSECONDS.toMillis(Long.MAX_VALUE);

    @Shadow
    private CraftingStatus status;

    @Unique
    private Button twinstar$pull;

    public CraftingCPUScreenMixin(CraftingCPUMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);
    }

    @WrapOperation(method = "updateBeforeRender", at = @At(value = "INVOKE",
            target = "Lorg/apache/commons/lang3/time/DurationFormatUtils;formatDuration(JLjava/lang/String;)Ljava/lang/String;"))
    private String twinstar$infiniteEta(long millis, String format, Operation<String> original) {
        if (millis >= twinstar$SATURATED_MILLIS)
            return "∞";
        return original.call(millis, format);
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void twinstar$pullButton(CallbackInfo ci) {
        if (twinstar$pull == null)
            twinstar$pull = Button.builder(Component.translatable("gui.twinstar.pull_from_network"),
                            b -> ((PullFromNetworkMenu) getMenu()).twinstar$pullFromNetwork())
                    .size(64, 20).tooltip(Tooltip.create(Component.translatable("gui.twinstar.pull_from_network.tooltip"))).build();
        if (!children().contains(twinstar$pull))
            addRenderableWidget(twinstar$pull);     // the screen's widgets are rebuilt on a resize
        twinstar$pull.setPosition(leftPos + imageWidth + 4, topPos + 19);
        twinstar$pull.visible = twinstar$returnsMayBeInNetwork();
    }

    @Unique
    private boolean twinstar$returnsMayBeInNetwork() {
        if (status == null)
            return false;
        for (CraftingStatusEntry entry : status.getEntries()) {
            BigStatusAmounts big = ((BigStatusEntry) entry).twinstar$big();
            if (big != null && big.mayBeInNetwork())
                return true;
        }
        return false;
    }
}
