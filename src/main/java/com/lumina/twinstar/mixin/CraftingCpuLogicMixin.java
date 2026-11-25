package com.lumina.twinstar.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.lumina.twinstar.monitor.Transfers;
import com.lumina.twinstar.usage.HostPlaces;
import com.lumina.twinstar.usage.TransferSites;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * The monitor's view of a pattern buffer of another mod (GregTech's ME pattern buffers): when the crafting CPU pushes a run's
 * materials into it, that is what the buffer was sent. AE2's pattern providers are hooked in {@link PatternProviderLogicMixin}.
 */
@Mixin(value = CraftingCpuLogic.class, remap = false)
public abstract class CraftingCpuLogicMixin {
    @WrapOperation(method = "executeCrafting",
            at = @At(value = "INVOKE", target = "Lappeng/api/networking/crafting/ICraftingProvider;pushPattern(Lappeng/api/crafting/IPatternDetails;[Lappeng/api/stacks/KeyCounter;)Z"))
    private boolean twinstar$pushed(ICraftingProvider provider, IPatternDetails pattern, KeyCounter[] inputHolder, Operation<Boolean> original) {
        if (provider instanceof PatternProviderLogic || HostPlaces.isBlockEntityHost(provider))
            return original.call(provider, pattern, inputHolder);
        List<AEKey> keys = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (KeyCounter counter : inputHolder)
            for (var e : counter) {
                keys.add(e.getKey());
                amounts.add(e.getLongValue());
            }
        boolean pushed = original.call(provider, pattern, inputHolder);
        if (pushed) {
            var at = HostPlaces.placeOf(provider);
            for (int i = 0; i < keys.size(); i++)
                TransferSites.sentFrom(at, null, keys.get(i), amounts.get(i), Transfers.PROVIDER);
        }
        return pushed;
    }
}
