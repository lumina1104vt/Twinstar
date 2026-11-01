package com.lumina.twinstar.order;

import com.lumina.twinstar.Twinstar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: a finished plan for the open plan screen. */
public record PlanViewPacket(int containerId, PlanView view) implements CustomPacketPayload {
    public static final Type<PlanViewPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "plan_view"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlanViewPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.containerId);
                p.view.write(buf);
            },
            buf -> new PlanViewPacket(buf.readVarInt(), PlanView.read(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlanViewPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().containerMenu instanceof PlanMenu menu && menu.containerId == packet.containerId)
                menu.setView(packet.view);
        });
    }
}
