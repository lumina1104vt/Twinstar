package com.lumina.twinstar.mixin;

import appeng.api.stacks.AEKey;
import appeng.helpers.InterfaceLogic;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lumina.twinstar.usage.SendGate;
import appeng.helpers.InterfaceLogicHost;
import appeng.util.ConfigInventory;
import com.lumina.twinstar.usage.OwnedInventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells an ME interface's storage whose it is (the block, or the part, that holds the logic), for the monitor. */
@Mixin(value = InterfaceLogic.class, remap = false)
public abstract class InterfaceLogicMixin {
    @Shadow
    @Final
    protected InterfaceLogicHost host;
    @Shadow
    @Final
    private ConfigInventory storage;

    @Inject(method = "<init>(Lappeng/api/networking/IManagedGridNode;Lappeng/helpers/InterfaceLogicHost;Lnet/minecraft/world/item/Item;I)V",
            at = @At("TAIL"))
    private void twinstar$stamp(CallbackInfo ci) {
        ((OwnedInventory) (Object) storage).twinstar$setOwner(host);
    }

    /** The interface taking surplus back out of its own storage to hand to the network is no sending: it is not counted. */
    @WrapMethod(method = "tryUsePlan")
    private boolean twinstar$returning(int slot, AEKey what, int amount, Operation<Boolean> original) {
        SendGate.returning++;
        try {
            return original.call(slot, what, amount);
        } finally {
            SendGate.returning--;
        }
    }
}
