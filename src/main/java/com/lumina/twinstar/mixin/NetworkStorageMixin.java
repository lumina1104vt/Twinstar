package com.lumina.twinstar.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.storage.NetworkStorage;
import com.lumina.twinstar.monitor.Transfers;
import com.lumina.twinstar.usage.HostPlaces;
import com.lumina.twinstar.usage.TransferSites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes out of the network by a block of another mod (GregTech's ME input and stocking buses and hatches, and the like): those
 * call the network's storage themselves, so this is where they are seen. The blocks of AE2, and of the mods that build on them,
 * are hooked where they send (their host is a block entity or a part), so they are left out here and nothing is counted twice.
 */
@Mixin(value = NetworkStorage.class, remap = false)
public abstract class NetworkStorageMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void twinstar$taken(AEKey what, long amount, Actionable mode, IActionSource source, CallbackInfoReturnable<Long> cir) {
        long taken = cir.getReturnValueJ();
        if (taken <= 0 || mode != Actionable.MODULATE || source.player().isPresent())
            return;
        IActionHost host = source.machine().orElse(null);
        if (host == null || HostPlaces.isBlockEntityHost(host))
            return;
        TransferSites.sentFrom(HostPlaces.placeOf(host), null, what, taken, Transfers.OTHER);
    }
}
