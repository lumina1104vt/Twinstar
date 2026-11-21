package com.lumina.twinstar.order;

import appeng.api.stacks.AEKey;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: a job the player ordered is done; shown at the top left ({@code JobDoneNotices}). */
public record JobDonePacket(AEKey what, String amount, long seconds) {
    public void write(FriendlyByteBuf buf) {
        AEKey.writeKey(buf, what);
        buf.writeUtf(amount);
        buf.writeVarLong(seconds);
    }

    public static JobDonePacket read(FriendlyByteBuf buf) {
        return new JobDonePacket(AEKey.readKey(buf), buf.readUtf(), buf.readVarLong());
    }

    public static void handle(JobDonePacket packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> com.lumina.twinstar.client.JobDoneNotices.show(packet));
        ctx.setPacketHandled(true);
    }
}
