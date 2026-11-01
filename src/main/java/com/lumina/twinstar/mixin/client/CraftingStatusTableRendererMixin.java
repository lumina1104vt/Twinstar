package com.lumina.twinstar.mixin.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.core.localization.GuiText;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.lumina.twinstar.menu.AmountText;
import com.lumina.twinstar.menu.BigStatusAmounts;
import com.lumina.twinstar.menu.BigStatusEntry;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/** CPU-status entries of hub jobs: exact amounts (beyond a long too), a hint for infinite sources, 機率產物不足. */
@Mixin(CraftingStatusTableRenderer.class)
public abstract class CraftingStatusTableRendererMixin {
    @Inject(method = "getEntryDescription(Lappeng/menu/me/crafting/CraftingStatusEntry;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void twinstar$description(CraftingStatusEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        BigStatusAmounts big = ((BigStatusEntry) entry).twinstar$big();
        if (big != null && entry.getWhat() != null)
            cir.setReturnValue(twinstar$lines(entry.getWhat(), big, false));
    }

    @Inject(method = "getEntryTooltip(Lappeng/menu/me/crafting/CraftingStatusEntry;)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true)
    private void twinstar$tooltip(CraftingStatusEntry entry, CallbackInfoReturnable<List<Component>> cir) {
        BigStatusAmounts big = ((BigStatusEntry) entry).twinstar$big();
        if (big == null || entry.getWhat() == null)
            return;
        List<Component> tip = new ArrayList<>(AEKeyRendering.getTooltip(entry.getWhat()));
        tip.addAll(twinstar$lines(entry.getWhat(), big, true));
        if (big.infinite())
            tip.add(Component.translatable("gui.twinstar.infinite_drawing", AmountText.format(entry.getWhat(), big.stored(), true)));
        cir.setReturnValue(tip);
    }

    @Unique
    private static List<Component> twinstar$lines(AEKey what, BigStatusAmounts big, boolean full) {
        List<Component> lines = new ArrayList<>(3);
        if (big.infinite())
            lines.add(GuiText.FromStorage.text(Component.translatable("gui.twinstar.infinite")));
        else if (big.stored().signum() > 0)
            lines.add(GuiText.FromStorage.text(AmountText.format(what, big.stored(), full)));
        if (big.active().signum() > 0)
            lines.add(GuiText.Crafting.text(AmountText.format(what, big.active(), full)));
        if (big.pending().signum() > 0)
            lines.add(GuiText.Scheduled.text(AmountText.format(what, big.pending(), full)));
        if (big.machineLost().signum() > 0)
            lines.add(Component.translatable("gui.twinstar.machine_lost", big.machineLost().toString())
                    .withStyle(net.minecraft.ChatFormatting.RED));
        if (big.waitingPattern())
            lines.add(Component.translatable("gui.twinstar.waiting_pattern").withStyle(net.minecraft.ChatFormatting.RED));
        if (big.mayBeInNetwork())
            lines.add(Component.translatable("gui.twinstar.may_be_in_network").withStyle(net.minecraft.ChatFormatting.GOLD));
        if (big.chanceShort().signum() > 0)
            lines.add(Component.translatable("gui.twinstar.chance_short", AmountText.format(what, big.chanceShort(), full))
                    .withStyle(net.minecraft.ChatFormatting.RED));
        return lines;
    }
}
