package com.lumina.twinstar.theory;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.menu.AEBaseMenu;
import com.lumina.twinstar.TwinNet;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.order.OrderFlow;
import com.lumina.twinstar.service.TwinStarService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Supplier;

/**
 * The theory viewer's traffic. The viewer is a client screen over whatever AE2 menu is open (the
 * order screen's, the hub's): the server answers for the grid of that menu's host. Requests are
 * small and answered from the engine's last published layer, so the viewer can ask every second.
 */
public final class TheoryPackets {
    private TheoryPackets() {
    }

    // ── client → server ──

    /** {@link #STATUS}, {@link #MAP} (the whole layer, in parts) or {@link #ITEM} ({@code key}). */
    public record Request(int what, @Nullable AEKey key) {
        public static final int STATUS = 0, MAP = 1, ITEM = 2;

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(what);
            buf.writeBoolean(key != null);
            if (key != null)
                AEKey.writeKey(buf, key);
        }

        public static Request read(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt(), buf.readBoolean() ? AEKey.readKey(buf) : null);
        }

        public static void handle(Request r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null)
                    return;
                TwinStarService service = serviceOf(player);
                if (r.what == MAP) {
                    List<TheoryMap.Part> parts = service == null
                            ? List.of(new TheoryMap.Part(-1, 0, 1, 0, 0, 0, List.of(), 0, List.of())) : TheoryQueries.map(service);
                    for (TheoryMap.Part part : parts)
                        TwinNet.toPlayer(player, new MapReply(part));
                    return;
                }
                Object reply = switch (r.what) {
                    case STATUS -> new StatusReply(service == null ? TwinStarService.TheoryStatus.NONE : service.theoryStatus());
                    case ITEM -> r.key == null ? null
                            : new ItemReply(service == null ? TheoryItem.unknown(r.key) : TheoryQueries.item(service, r.key));
                    default -> null;
                };
                if (reply != null)
                    TwinNet.toPlayer(player, reply);
            });
            ctx.setPacketHandled(true);
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

    public record StatusReply(TwinStarService.TheoryStatus status) {
        public void write(FriendlyByteBuf buf) {
            TwinStarService.TheoryStatus s = status;
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
        }

        public static StatusReply read(FriendlyByteBuf buf) {
            return new StatusReply(new TwinStarService.TheoryStatus(buf.readLong(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarLong(), buf.readBoolean(), buf.readVarLong()));
        }

        public static void handle(StatusReply r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> TheoryCache.status(r.status));
            ctx.setPacketHandled(true);
        }
    }

    public record MapReply(TheoryMap.Part part) {
        public void write(FriendlyByteBuf buf) {
            part.write(buf);
        }

        public static MapReply read(FriendlyByteBuf buf) {
            return new MapReply(TheoryMap.Part.read(buf));
        }

        public static void handle(MapReply r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> TheoryCache.mapPart(r.part));
            ctx.setPacketHandled(true);
        }
    }

    public record ItemReply(TheoryItem item) {
        public void write(FriendlyByteBuf buf) {
            item.write(buf);
        }

        public static ItemReply read(FriendlyByteBuf buf) {
            return new ItemReply(TheoryItem.read(buf));
        }

        public static void handle(ItemReply r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> TheoryCache.item(r.item));
            ctx.setPacketHandled(true);
        }
    }
}
