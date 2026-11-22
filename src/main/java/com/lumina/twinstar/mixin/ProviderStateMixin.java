package com.lumina.twinstar.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.me.service.helpers.NetworkCraftingProviders;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.lumina.twinstar.service.SecondaryOutputs;

import java.util.List;

/**
 * AE2 lists a pattern as a way to make only its first output (the "primary output"), so an item that a pattern
 * makes only as its second or later output is not craftable: not in the terminal's craftable list, and the engine,
 * which learns the patterns from that list, never sees the pattern for it. Here the pattern is also listed under
 * its other outputs, the same way and in the same places AE2 lists it under the first.
 */
@Mixin(targets = "appeng.me.service.helpers.NetworkCraftingProviders$ProviderState", remap = false)
public abstract class ProviderStateMixin {
    @Shadow
    @Final
    private List<IPatternDetails> patterns;

    @Inject(method = "mount", at = @At("TAIL"))
    private void twinstar$mountSecondary(NetworkCraftingProviders owner, CallbackInfo ci) {
        SecondaryOutputs.mount(owner, this, patterns);
    }

    @Inject(method = "unmount", at = @At("TAIL"))
    private void twinstar$unmountSecondary(NetworkCraftingProviders owner, CallbackInfo ci) {
        SecondaryOutputs.unmount(owner, this, patterns);
    }
}
