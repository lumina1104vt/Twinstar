package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.Twinstar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server → client: a job the player ordered is done; shown at the top left ({@code JobDoneNotices}). */
public record JobDonePacket(AEKey what, String amount, long seconds) implements CustomPacketPayload {
    public static final Type<JobDonePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "job_done"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JobDonePacket> STREAM_CODEC = StreamCodec.composite(
            AEKey.STREAM_CODEC, JobDonePacket::what,
            ByteBufCodecs.STRING_UTF8, JobDonePacket::amount,
            ByteBufCodecs.VAR_LONG, JobDonePacket::seconds,
            JobDonePacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(JobDonePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> com.lumina.twinstar.client.JobDoneNotices.show(packet));
    }
}
