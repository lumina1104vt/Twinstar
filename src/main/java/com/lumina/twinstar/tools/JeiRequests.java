package com.lumina.twinstar.tools;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server side of asking a player's JEI for its recipes: who was asked, who gets the batches. Only
 * the player that was asked can answer; a client that stays silent for {@link #TIMEOUT_NANOS} fails
 * the request. Server thread only.
 */
public final class JeiRequests {
    private static final long TIMEOUT_NANOS = 120_000_000_000L;
    private static final Map<Integer, Pending> PENDING = new HashMap<>();
    private static int next = 1;

    private JeiRequests() {
    }

    /** What happens with the answer (on the server thread). */
    public interface Receiver {
        void recipes(List<JeiRecipeData> batch);

        /** All sent; {@code skipped}: recipes the client left out, per category. */
        void done(Map<ResourceLocation, Integer> skipped);

        void failed(String why);
    }

    private static final class Pending {
        final UUID player;
        final Receiver receiver;
        long lastNanos = System.nanoTime();

        Pending(UUID player, Receiver receiver) {
            this.player = player;
            this.receiver = receiver;
        }
    }

    public static void request(ServerPlayer player, Receiver receiver) {
        int id = next++;
        PENDING.put(id, new Pending(player.getUUID(), receiver));
        com.lumina.twinstar.TwinNet.toPlayer(player, new JeiPackets.Request(id));
    }

    static void received(JeiPackets.Recipes packet, Player from) {
        Pending p = PENDING.get(packet.request());
        if (p == null || !p.player.equals(from.getUUID()))
            return;     // not asked, or not this player
        p.lastNanos = System.nanoTime();
        if (!packet.error().isEmpty()) {
            PENDING.remove(packet.request());
            p.receiver.failed(packet.error());
            return;
        }
        p.receiver.recipes(packet.recipes());
        if (packet.last()) {
            PENDING.remove(packet.request());
            p.receiver.done(packet.skipped());
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        long now = System.nanoTime();
        for (Iterator<Pending> it = PENDING.values().iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (now - p.lastNanos > TIMEOUT_NANOS) {
                it.remove();
                p.receiver.failed("客戶端超過 " + TIMEOUT_NANOS / 1_000_000_000L + " 秒沒有回應");
            }
        }
    }

    /** A single-player world closing: nobody answers the requests any more. */
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
        RecipeDump.onServerStopping();
    }
}
