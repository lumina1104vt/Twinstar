package com.lumina.twinstar.mixin;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.helpers.externalstorage.GenericStackInv;
import com.lumina.twinstar.monitor.Transfers;
import com.lumina.twinstar.usage.OwnedInventory;
import com.lumina.twinstar.usage.TransferSites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The monitor's view of an ME interface: what is taken out of its storage (by a pipe, a hopper, a machine pulling) is what the
 * interface sent. Every way out comes through this slot method (the key-based one and the item handler both call it).
 */
@Mixin(value = GenericStackInv.class, remap = false)
public abstract class GenericStackInvMixin implements OwnedInventory {
    @Unique
    private Object twinstar$owner;

    @Override
    public void twinstar$setOwner(Object owner) {
        twinstar$owner = owner;
    }

    @Override
    public Object twinstar$getOwner() {
        return twinstar$owner;
    }

    @Inject(method = "extract(ILappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;)J", at = @At("RETURN"))
    private void twinstar$extracted(int slot, AEKey what, long amount, Actionable mode, CallbackInfoReturnable<Long> cir) {
        if (twinstar$owner == null || mode != Actionable.MODULATE || cir.getReturnValueJ() <= 0)
            return;
        TransferSites.sent(TransferSites.blockOf(twinstar$owner), null, what, cir.getReturnValueJ(), Transfers.INTERFACE);
    }
}
