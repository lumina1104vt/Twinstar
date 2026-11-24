package com.lumina.twinstar.mixin;

import appeng.api.behaviors.StackTransferContext;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.parts.automation.StorageExportStrategy;
import com.lumina.bridge.Place;
import com.lumina.twinstar.monitor.Transfers;
import com.lumina.twinstar.usage.TransferSites;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The monitor's view of an ME export bus: what it moved out of the network into the block it faces. */
@Mixin(value = StorageExportStrategy.class, remap = false)
public abstract class StorageExportStrategyMixin {
    @Shadow
    @Final
    private BlockCapabilityCache<?, Direction> cache;

    @Inject(method = "transfer", at = @At("RETURN"))
    private void twinstar$moved(StackTransferContext context, AEKey what, long amount, CallbackInfoReturnable<Long> cir) {
        long moved = cir.getReturnValueJ();
        if (moved <= 0)
            return;
        IActionHost host = context.getActionSource().machine().orElse(null);
        if (host == null || host.getActionableNode() == null)
            return;
        Place to = TransferSites.placeOf(cache.level(), cache.pos());
        TransferSites.sent(TransferSites.blockOf(host.getActionableNode().getOwner()), to, what, moved, Transfers.EXPORT_BUS);
    }
}
