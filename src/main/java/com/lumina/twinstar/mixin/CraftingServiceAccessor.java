package com.lumina.twinstar.mixin;

import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The network's pattern registry, so the theory layer can follow pattern changes. */
@Mixin(value = CraftingService.class, remap = false)
public interface CraftingServiceAccessor {
    @Accessor("craftingProviders")
    NetworkCraftingProviders twinstar$getCraftingProviders();
}
