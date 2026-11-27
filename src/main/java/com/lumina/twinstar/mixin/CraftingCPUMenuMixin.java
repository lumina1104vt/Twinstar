package com.lumina.twinstar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.CraftingStatusPacket;
import appeng.menu.AEBaseMenu;
import appeng.menu.me.crafting.CraftingCPUMenu;
import com.lumina.twinstar.cpu.CpuReportPacket;
import com.lumina.twinstar.cpu.TwinStarCpu;
import com.lumina.twinstar.menu.PullFromNetworkMenu;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;

@Mixin(CraftingCPUMenu.class)
public abstract class CraftingCPUMenuMixin extends AEBaseMenu implements PullFromNetworkMenu {
    @Unique
    private final Map<AEKey, Long> twinstar$serials = new HashMap<>();
    @Unique
    private long twinstar$nextSerial;

    @Unique
    private TwinStarCpu twinstar$cpu;

    public CraftingCPUMenuMixin(MenuType<?> menuType, int id, Inventory playerInventory, Object host) {
        super(menuType, id, playerInventory, host);
    }

    @Unique
    private static final String twinstar$PULL = "twinstarPullFromNetwork";

    @Inject(method = "<init>", at = @At("TAIL"))
    private void twinstar$init(CallbackInfo ci) {
        registerClientAction(twinstar$PULL, () -> {
            if (twinstar$cpu != null)
                twinstar$cpu.pullFromNetwork();
        });
    }

    @Override
    public void twinstar$pullFromNetwork() {
        sendClientAction(twinstar$PULL);
    }

    // wraps the method: another mod may end it from its own HEAD handler (GTLCore does for its CPUs), and the
    // hub's CPU would then stay selected here and go on sending its status over theirs
    @WrapMethod(method = "setCPU")
    private void twinstar$setCPU(ICraftingCPU cpu, Operation<Void> original) {
        twinstar$serials.clear();
        twinstar$cpu = cpu instanceof TwinStarCpu c ? c : null;
        original.call(cpu);
    }

    @Inject(method = "cancelCrafting", at = @At("HEAD"), cancellable = true)
    private void twinstar$cancelCrafting(CallbackInfo ci) {
        if (isServerSide() && twinstar$cpu != null) {
            twinstar$cpu.cancelJob();
            ci.cancel();
        }
    }

    /** Pause button: the job keeps its capacity and still receives returns, but nothing runs. */
    @Inject(method = "toggleScheduling", at = @At("HEAD"), cancellable = true)
    private void twinstar$toggleScheduling(CallbackInfo ci) {
        if (isServerSide() && twinstar$cpu != null) {
            twinstar$cpu.toggleSuspended();
            ci.cancel();
        }
    }

    @Unique
    private int twinstar$reportTicks;

    @Inject(method = "broadcastChanges", at = @At("HEAD"))
    private void twinstar$broadcastChanges(CallbackInfo ci) {
        if (isServerSide() && twinstar$cpu != null) {
            if (twinstar$reportTicks++ % 20 == 0 && getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)
                // memory and speed of the hub's data: once a second is plenty
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, new CpuReportPacket(containerId, twinstar$cpu.report()));
            var status = twinstar$cpu.createStatus(key -> twinstar$serials.computeIfAbsent(key, k -> twinstar$nextSerial++));
            sendPacketToClient(new CraftingStatusPacket(containerId, status));
        }
    }
}
