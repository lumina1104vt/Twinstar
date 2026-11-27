package com.lumina.twinstar.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.me.service.CraftingService;
import com.google.common.collect.ImmutableSet;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.cpu.TwinStarCpu;
import com.lumina.twinstar.service.TwinStarService;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Future;

/**
 * Every hook first asks whether the grid has an active hub; without one AE2 runs unchanged.
 * AE2's own CPUs keep working beside the hub; a plan only ever runs on the engine that made it.
 */
@Mixin(CraftingService.class)
public abstract class CraftingServiceMixin {
    @Shadow
    @Final
    private IGrid grid;

    @Unique
    private TwinStarService twinstar$service() {
        return (TwinStarService) grid.getService(ITwinStarService.class);
    }

    /**
     * Hub first for automated requests (interfaces, emitters, other machines): on a grid with a
     * working hub the twin-star engine plans them. A wrapper applied late (order 1500) ends up
     * outermost, so it decides before other mods that wrap this method too - Data Energistics plans
     * with its own engine whenever the grid has one of its data cores. Players order through the
     * twin-star order screen, which asks the engine itself; a player who picks AE2's original screen
     * gets AE2's (or another mod's) planning, so player requests are left alone here.
     */
    @WrapMethod(method = "beginCraftingCalculation", order = 1500)
    private Future<ICraftingPlan> twinstar$hubFirst(Level level, ICraftingSimulationRequester simRequester, AEKey what,
                                                    long amount, CalculationStrategy strategy,
                                                    Operation<Future<ICraftingPlan>> original) {
        var service = twinstar$service();
        IActionSource source = simRequester != null ? simRequester.getActionSource() : null;
        boolean byPlayer = source != null && source.player().isPresent();
        if (service.hasHub() && amount > 0 && !byPlayer)
            return service.beginCalculation(what, amount, strategy);
        return original.call(level, simRequester, what, amount, strategy);
    }

    @ModifyExpressionValue(method = "getCpus", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/ImmutableSet;builder()Lcom/google/common/collect/ImmutableSet$Builder;"))
    private ImmutableSet.Builder<ICraftingCPU> twinstar$addIdleCpu(ImmutableSet.Builder<ICraftingCPU> builder) {
        var service = twinstar$service();
        if (service.hasHub()) {
            builder.add(service.getIdleCpu());
            builder.addAll(service.getWorkingCpus());
        }

        return builder;
    }

    @Inject(method = "hasCpu", at = @At("HEAD"), cancellable = true)
    private void twinstar$hasCpu(ICraftingCPU cpu, CallbackInfoReturnable<Boolean> cir) {
        if (cpu instanceof TwinStarCpu tsc) {
            cir.setReturnValue(twinstar$service().hasCpu(tsc));
        }
    }

    /**
     * Our plans run on the hub (picked or not); a plan AE2 made cannot be run by the hub and is
     * refused when the hub is picked, never crashed on.
     */
    @Inject(method = "submitJob", at = @At("HEAD"), cancellable = true)
    private void twinstar$submitJob(
            ICraftingPlan job,
            ICraftingRequester requestingMachine,
            ICraftingCPU target,
            boolean prioritizePower,
            IActionSource src,
            CallbackInfoReturnable<ICraftingSubmitResult> cir
    ) {
        var service = twinstar$service();
        boolean ours = service.isOurPlan(job);
        if (target instanceof TwinStarCpu c && service.hasCpu(c)) {
            cir.setReturnValue(ours ? service.submit(job, requestingMachine, src)
                    : TwinStarService.wrongEngine(src, false));
        } else if (ours) {
            if (target == null && service.hasHub())
                cir.setReturnValue(service.submit(job, requestingMachine, src));
            else
                cir.setReturnValue(TwinStarService.wrongEngine(src, true));
        }
    }

    /*
     * The four below wrap the whole method, not its return: another mod may answer from a handler of its own at
     * RETURN (GTLCore does), which ends the method there and then, and a hook of ours placed after it never runs.
     * What the hub's jobs wait for would then go into storage unclaimed.
     */

    /** The only way returns reach CPUs: AE2's CPUs take theirs first, the hub keeps what its jobs expect. */
    @WrapMethod(method = "insertIntoCpus")
    private long twinstar$insertIntoCpus(AEKey what, long amount, Actionable type, Operation<Long> original) {
        long taken = original.call(what, amount, type);
        var service = twinstar$service();
        if (taken < amount && service.hasHub())
            taken += service.insertIntoCpus(what, amount - taken, type);
        return taken;
    }

    @WrapMethod(method = "isRequesting")
    private boolean twinstar$isRequesting(AEKey what, Operation<Boolean> original) {
        return original.call(what) || twinstar$service().getRequestedAmount(what) > 0;
    }

    @WrapMethod(method = "getRequestedAmount")
    private long twinstar$getRequestedAmount(AEKey what, Operation<Long> original) {
        long theirs = original.call(what);
        long ours = twinstar$service().getRequestedAmount(what);
        if (ours <= 0)
            return theirs;
        long sum = theirs + ours;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    @WrapMethod(method = "isRequestingAny")
    private boolean twinstar$isRequestingAny(Operation<Boolean> original) {
        return original.call() || twinstar$service().isRequestingAny();
    }
}
