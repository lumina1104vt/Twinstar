package com.lumina.twinstar.usage;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionHost;
import appeng.menu.AEBaseMenu;
import com.lumina.twinstar.TwinNet;
import com.lumina.twinstar.order.OrderFlow;
import com.lumina.twinstar.service.TwinStarService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The usage screen's traffic. The screen sits over an AE2 menu (a hub, a CPU, a machine access interface): the server answers for
 * the grid of that menu's host and for no other (a player sees the networks whose menus they can open; this AE2 build has no
 * security terminal to ask, so there is nothing more to check). The client asks once every couple of seconds while the screen is
 * open; the server never sends on its own.
 */
public final class UsagePackets {
    private UsagePackets() {
    }

    /** a player is answered at most this often (ticks), however often the client asks */
    private static final int MIN_GAP_TICKS = 10;
    private static final Map<UUID, Integer> LAST = new HashMap<>();

    /** Client to server: "what did my network's machines use up". */
    public record Request() {
        public void write(FriendlyByteBuf buf) {
        }

        public static Request read(FriendlyByteBuf buf) {
            return new Request();
        }

        public static void handle(Request r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player == null)
                    return;
                int now = player.server.getTickCount();
                Integer last = LAST.put(player.getUUID(), now);
                if (last != null && now - last < MIN_GAP_TICKS && now >= last)
                    return;
                TwinNet.toPlayer(player, new Reply(answer(player)));
            });
            ctx.setPacketHandled(true);
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
        return UsageQueries.build(player.server, service.accessOwners(), gridPlaces(grid));
    }

    /** Where the nodes of the network are (a block entity, or the block a part sits in). */
    private static java.util.Set<net.minecraft.core.GlobalPos> gridPlaces(IGrid grid) {
        java.util.Set<net.minecraft.core.GlobalPos> places = new java.util.HashSet<>();
        for (appeng.api.networking.IGridNode node : grid.getNodes()) {
            // a block entity, a part, or a machine of another mod that is none (GregTech's: found by what it can tell)
            net.minecraft.core.GlobalPos at = com.lumina.bridge.Positions.toMc(HostPlaces.placeOf(node.getOwner()));
            if (at != null)
                places.add(at);
        }
        return places;
    }

    /** Server to client. */
    public record Reply(UsageSnapshot snapshot) {
        public void write(FriendlyByteBuf buf) {
            snapshot.write(buf);
        }

        public static Reply read(FriendlyByteBuf buf) {
            return new Reply(UsageSnapshot.read(buf));
        }

        public static void handle(Reply r, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> UsageCache.set(r.snapshot));
            ctx.setPacketHandled(true);
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
            TwinNet.toServer(new Request());
        }
    }
}
