package com.lumina.twinstar.cpu;

import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.codec.ReportCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * Server to client, about once a second for an open CPU screen that shows one of the hub's entries:
 * how much Java memory the hub's data takes and how fast the executor runs ({@link HubReport}).
 */
public record CpuReportPacket(int containerId, HubReport report) implements CustomPacketPayload {
    public static final Type<CpuReportPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "cpu_report"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CpuReportPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.containerId);
                ReportCodec.write(buf, p.report);
            },
            buf -> new CpuReportPacket(buf.readVarInt(), ReportCodec.readHub(buf)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CpuReportPacket p, IPayloadContext context) {
        context.enqueueWork(() -> Latest.set(p.containerId, p.report));
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
