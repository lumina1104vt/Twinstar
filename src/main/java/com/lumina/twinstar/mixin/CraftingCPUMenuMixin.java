package com.lumina.twinstar.mixin;

import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.core.sync.packets.CraftingStatusPacket;
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

@Mixin(value = CraftingCPUMenu.class, remap = false)
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

    @Inject(method = "setCPU", at = @At("HEAD"))
    private void twinstar$setCPU(ICraftingCPU cpu, CallbackInfo ci) {
        twinstar$serials.clear();
        if (cpu instanceof TwinStarCpu c) {
            twinstar$cpu = c;
        } else {
            twinstar$cpu = null;
        }
    }

    @Inject(method = "cancelCrafting", at = @At("HEAD"), cancellable = true)
    private void twinstar$cancelCrafting(CallbackInfo ci) {
        if (isServerSide() && twinstar$cpu != null) {
            twinstar$cpu.cancelJob();
            ci.cancel();
        }
    }

    // vanilla's method: named here, SRG in a production AE2 (this mixin does not remap AE2's class)
    @Unique
    private int twinstar$reportTicks;

    @Inject(method = {"broadcastChanges", "m_38946_"}, at = @At("HEAD"))
    private void twinstar$broadcastChanges(CallbackInfo ci) {
        if (isServerSide() && twinstar$cpu != null) {
            if (twinstar$reportTicks++ % 20 == 0 && getPlayer() instanceof net.minecraft.server.level.ServerPlayer player)
                // memory and speed of the hub's data: once a second is plenty
                com.lumina.twinstar.TwinNet.toPlayer(player, new CpuReportPacket(containerId, twinstar$cpu.report()));
            var status = twinstar$cpu.createStatus(key -> twinstar$serials.computeIfAbsent(key, k -> twinstar$nextSerial++));
            sendPacketToClient(new CraftingStatusPacket(containerId, status));
        }
    }
}
