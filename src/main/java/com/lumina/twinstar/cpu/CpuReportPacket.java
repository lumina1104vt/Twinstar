package com.lumina.twinstar.cpu;

import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.codec.ReportCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * Server to client, about once a second for an open CPU screen that shows one of the hub's entries:
 * how much Java memory the hub's data takes and how fast the executor runs ({@link HubReport}).
 */
public record CpuReportPacket(int containerId, HubReport report) {
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        ReportCodec.write(buf, report);
    }

    public static CpuReportPacket read(FriendlyByteBuf buf) {
        return new CpuReportPacket(buf.readVarInt(), ReportCodec.readHub(buf));
    }

    public static void handle(CpuReportPacket p, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> Latest.set(p.containerId, p.report));
        ctx.setPacketHandled(true);
    }

    /** Client: the newest report of the CPU screen that is open (one screen at a time). */
    public static final class Latest {
        private static int containerId = -1;
        private static @Nullable HubReport report;
        private static long at;

        private Latest() {
        }

        static void set(int id, HubReport r) {
            containerId = id;
            report = r;
            at = System.nanoTime();
        }

        /** The report for the menu with this id, or null when none came lately (a CPU that is not ours, or the server is far behind). */
        public static @Nullable HubReport of(int id) {
            return id == containerId && report != null && System.nanoTime() - at < 5_000_000_000L ? report : null;
        }
    }
}
