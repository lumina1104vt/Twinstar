package com.lumina.twinstar.metrics;

import net.minecraft.network.FriendlyByteBuf;

/** The reports on the wire: server to client, a few dozen varints. */
public final class ReportCodec {
    private ReportCodec() {
    }

    public static void write(FriendlyByteBuf b, TheoryReport r) {
        b.writeBoolean(r.memoryReady());
        b.writeVarInt(r.items());
        b.writeVarInt(r.patterns());
        b.writeVarInt(r.loops());
        b.writeVarLong(r.patternBytes());
        b.writeVarLong(r.itemBytes());
        b.writeVarLong(r.bomBytes());
        b.writeVarLong(r.otherBytes());
        b.writeVarLong(r.flushes());
        b.writeVarLong(r.rebuilds());
        b.writeVarLong(r.lastFlushNanos());
        b.writeBoolean(r.lastRebuilt());
        b.writeVarLong(r.lastRebuildNanos());
        b.writeVarLong(r.incrMaxNanos());
        b.writeVarLong(r.lastFlushAlloc() + 1);
        b.writeVarInt(r.plans());
        b.writeVarLong(r.lastPlanNanos());
        b.writeVarLong(r.maxPlanNanos());
        b.writeVarInt(r.softHits());
        b.writeVarInt(r.hardHits());
        b.writeVarInt(r.growthPermille() + 1);
    }

    public static TheoryReport readTheory(FriendlyByteBuf b) {
        return new TheoryReport(b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt(),
                b.readVarLong(), b.readVarLong(), b.readVarLong(), b.readVarLong(),
                b.readVarLong(), b.readVarLong(), b.readVarLong(), b.readBoolean(), b.readVarLong(),
                b.readVarLong(), b.readVarLong() - 1,
                b.readVarInt(), b.readVarLong(), b.readVarLong(), b.readVarInt(), b.readVarInt(),
                b.readVarInt() - 1);
    }

    public static void write(FriendlyByteBuf b, HubReport r) {
        b.writeVarInt(r.jobs());
        b.writeVarInt(r.trackedItems());
        b.writeVarLong(r.totalBytes());
        b.writeVarLong(r.jobBytes() + 1);
        b.writeVarInt(r.jobItems());
        b.writeVarInt(r.jobTasks());
        b.writeVarLong(r.poolBytes());
        b.writeVarLong(r.idleResidualBytes() + 1);
        b.writeVarInt(r.oldSnapshots());
        b.writeVarLong(r.oldSnapshotBytes());
        b.writeVarLong(r.tickAvgNanos());
        b.writeVarLong(r.tickMaxNanos());
        b.writeVarLong(r.allocPerSecond() + 1);
    }

    public static HubReport readHub(FriendlyByteBuf b) {
        return new HubReport(b.readVarInt(), b.readVarInt(), b.readVarLong(), b.readVarLong() - 1, b.readVarInt(), b.readVarInt(),
                b.readVarLong(), b.readVarLong() - 1, b.readVarInt(), b.readVarLong(),
                b.readVarLong(), b.readVarLong(), b.readVarLong() - 1);
    }
}
