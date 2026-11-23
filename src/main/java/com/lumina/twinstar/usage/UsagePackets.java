package com.lumina.twinstar.usage;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.menu.AEBaseMenu;
import com.lumina.twinstar.Twinstar;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The usage screen's traffic. The screen sits over an AE2 menu (a hub, a CPU, the order screen, a machine access interface):
 * the server answers for the grid of that menu's host and for no other (a player sees the networks whose menus they can open; this
 * AE2 build has no security terminal to ask, so there is nothing more to check).
 * The client asks once every couple of seconds while the screen is open; the server never sends on its own.
 */
public final class UsagePackets {
    private UsagePackets() {
    }

    /** a player is answered at most this often (ticks), however often the client asks */
    private static final int MIN_GAP_TICKS = 10;
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    /** Client to server: "what did my network's machines use up". */
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "usage_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of((buf, r) -> {
        }, buf -> new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Request r, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!(context.player() instanceof ServerPlayer player))
                    return;
                int now = player.server.getTickCount();
                Integer last = LAST.put(player.getUUID(), now);
                if (last != null && now - last < MIN_GAP_TICKS && now >= last)
                    return;
                PacketDistributor.sendToPlayer(player, new Reply(answer(player)));
            });
        }
    }

    /** What the player may see of the network behind the AE2 menu they have open. */
    static UsageSnapshot answer(ServerPlayer player) {
        if (!(player.containerMenu instanceof AEBaseMenu menu) || !(menu.getTarget() instanceof IActionHost host))
            return UsageSnapshot.NONE;
        IGrid grid = OrderFlow.gridOf(host);
        if (grid == null)
            return UsageSnapshot.NONE;
        if (!(grid.getService(com.lumina.twinstar.api.ITwinStarService.class) instanceof TwinStarService service))
            return UsageSnapshot.NONE;
        return UsageQueries.build(player.server, service.accessOwners());
    }

    /** Server to client. */
    public record Reply(UsageSnapshot snapshot) implements CustomPacketPayload {
        public static final Type<Reply> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "usage_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> STREAM_CODEC = StreamCodec.of(
                (buf, r) -> r.snapshot.write(buf), buf -> new Reply(UsageSnapshot.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Reply r, IPayloadContext context) {
            context.enqueueWork(() -> UsageCache.set(r.snapshot));
        }
    }

    /** Client: the newest answer. */
    public static final class UsageCache {
        private static @Nullable UsageSnapshot latest;
        private static int version;

        private UsageCache() {
        }

        static void set(UsageSnapshot s) {
            latest = s;
            version++;
        }

        public static @Nullable UsageSnapshot latest() {
            return latest;
        }

        /** bumped on every answer */
        public static int version() {
            return version;
        }

        public static void clear() {
            latest = null;
            version++;
        }

        public static void ask() {
            PacketDistributor.sendToServer(new Request());
        }
    }
}
