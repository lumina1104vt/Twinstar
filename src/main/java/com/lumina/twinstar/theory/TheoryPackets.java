package com.lumina.twinstar.theory;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.menu.AEBaseMenu;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.order.OrderFlow;
import com.lumina.twinstar.service.TwinStarService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * The theory viewer's traffic. The viewer is a client screen over whatever AE2 menu is open (the
 * order screen's, the hub's): the server answers for the grid of that menu's host. Requests are
 * small and answered from the engine's last published layer, so the viewer can ask every second.
 */
public final class TheoryPackets {
    private TheoryPackets() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, path);
    }

    // ── client → server ──

    /** {@link #STATUS}, {@link #MAP} (the whole layer, in parts) or {@link #ITEM} ({@code key}). */
    public record Request(int what, @Nullable AEKey key) implements CustomPacketPayload {
        public static final int STATUS = 0, MAP = 1, ITEM = 2;
        public static final Type<Request> TYPE = new Type<>(id("theory_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeVarInt(r.what);
                    buf.writeBoolean(r.key != null);
                    if (r.key != null)
                        AEKey.writeKey(buf, r.key);
                },
                buf -> new Request(buf.readVarInt(), buf.readBoolean() ? AEKey.readKey(buf) : null));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Request r, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player))
                    return;
                TwinStarService service = serviceOf(player);
                if (r.what == MAP) {
                    List<TheoryMap.Part> parts = service == null
                            ? List.of(new TheoryMap.Part(-1, 0, 1, 0, 0, 0, List.of(), 0, List.of())) : TheoryQueries.map(service);
                    for (TheoryMap.Part part : parts)
                        PacketDistributor.sendToPlayer(player, new MapReply(part));
                    return;
                }
                Object reply = switch (r.what) {
                    case STATUS -> new StatusReply(service == null ? TwinStarService.TheoryStatus.NONE : service.theoryStatus());
                    case ITEM -> r.key == null ? null
                            : new ItemReply(service == null ? TheoryItem.unknown(r.key) : TheoryQueries.item(service, r.key));
                    default -> null;
                };
                if (reply instanceof CustomPacketPayload payload)
                    PacketDistributor.sendToPlayer(player, payload);
            });
        }
    }

    /** The engine of the grid the player's open AE2 menu belongs to. */
    private static @Nullable TwinStarService serviceOf(ServerPlayer player) {
        if (!(player.containerMenu instanceof AEBaseMenu menu) || !(menu.getTarget() instanceof IActionHost host))
            return null;
        IGrid grid = OrderFlow.gridOf(host);
        if (grid == null)
            return null;
        return grid.getService(ITwinStarService.class) instanceof TwinStarService ts ? ts : null;
    }

    // ── server → client ──

    public record StatusReply(TwinStarService.TheoryStatus status) implements CustomPacketPayload {
        public static final Type<StatusReply> TYPE = new Type<>(id("theory_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, StatusReply> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> {
                    TwinStarService.TheoryStatus s = r.status;
                    buf.writeLong(s.version());
                    buf.writeVarInt(s.items());
                    buf.writeVarInt(s.patterns());
                    buf.writeVarInt(s.loops());
                    buf.writeBoolean(s.upToDate());
                    buf.writeBoolean(s.busy());
                    buf.writeVarInt(s.pendingBatches());
                    buf.writeVarInt(s.queued());
                    buf.writeVarLong(s.flushNanos());
                    buf.writeBoolean(s.rebuilt());
                    buf.writeVarLong(s.flushAgoMillis());
                    com.lumina.twinstar.codec.ReportCodec.write(buf, s.report());
                },
                buf -> new StatusReply(new TwinStarService.TheoryStatus(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarLong(), buf.readBoolean(), buf.readVarLong(), com.lumina.twinstar.codec.ReportCodec.readTheory(buf))));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(StatusReply r, IPayloadContext context) {
            context.enqueueWork(() -> TheoryCache.status(r.status));
        }
    }

    public record MapReply(TheoryMap.Part part) implements CustomPacketPayload {
        public static final Type<MapReply> TYPE = new Type<>(id("theory_map"));
        public static final StreamCodec<RegistryFriendlyByteBuf, MapReply> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> r.part.write(buf), buf -> new MapReply(TheoryMap.Part.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(MapReply r, IPayloadContext context) {
            context.enqueueWork(() -> TheoryCache.mapPart(r.part));
        }
    }

    public record ItemReply(TheoryItem item) implements CustomPacketPayload {
        public static final Type<ItemReply> TYPE = new Type<>(id("theory_item"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ItemReply> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> r.item.write(buf), buf -> new ItemReply(TheoryItem.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(ItemReply r, IPayloadContext context) {
            context.enqueueWork(() -> TheoryCache.item(r.item));
        }
    }
}
