package com.lumina.twinstar.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lumina.twinstar.monitor.Transfers;
import com.lumina.twinstar.usage.TransferSites;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;

/** The monitor's view of a pattern provider: when it pushes a run's materials, that is what the provider sent. */
@Mixin(value = PatternProviderLogic.class, remap = false)
public abstract class PatternProviderLogicMixin {
    @Shadow
    @Final
    private PatternProviderLogicHost host;

    @WrapMethod(method = "pushPattern")
    private boolean twinstar$pushed(IPatternDetails pattern, KeyCounter[] inputHolder, Operation<Boolean> original) {
        // what is pushed is what the holder has before the call
        List<AEKey> keys = new ArrayList<>();
        List<Long> amounts = new ArrayList<>();
        for (KeyCounter counter : inputHolder)
            for (var e : counter) {
                keys.add(e.getKey());
                amounts.add(e.getLongValue());
            }
        boolean pushed = original.call(pattern, inputHolder);
        if (pushed)
            for (int i = 0; i < keys.size(); i++)
                TransferSites.sent(host.getBlockEntity(), null, keys.get(i), amounts.get(i), Transfers.PROVIDER);
        return pushed;
    }
}
