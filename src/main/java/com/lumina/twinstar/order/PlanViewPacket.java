package com.lumina.twinstar.order;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: a finished plan for the open plan screen. */
public record PlanViewPacket(int containerId, PlanView view) {
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        view.write(buf);
    }

    public static PlanViewPacket read(FriendlyByteBuf buf) {
        return new PlanViewPacket(buf.readVarInt(), PlanView.read(buf));
    }

    public static void handle(PlanViewPacket packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            var player = com.lumina.twinstar.client.ClientHooks.player();
            if (player != null && player.containerMenu instanceof PlanMenu menu && menu.containerId == packet.containerId)
                menu.setView(packet.view);
        });
        ctx.setPacketHandled(true);
    }
}
