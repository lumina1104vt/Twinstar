package com.lumina.twinstar.tools;

import com.lumina.twinstar.TwinNet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The two packets that bring JEI's recipes from a player's client to the server: the server asks
 * ({@link Request}), the client answers in batches ({@link Recipes}, the last one marked).
 */
public final class JeiPackets {
    private JeiPackets() {
    }

    /** Server → client: send me what your JEI shows. */
    public record Request(int request) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(request);
        }

        public static Request read(FriendlyByteBuf buf) {
            return new Request(buf.readVarInt());
        }

        /** Client side. Without JEI the answer is a refusal; the collector is only loaded when JEI is there. */
        public static void handle(Request packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                if (!ModList.get().isLoaded("jei")) {
                    TwinNet.toServer(Recipes.failure(packet.request, "客戶端沒有安裝 JEI"));
                    return;
                }
                com.lumina.twinstar.client.jei.JeiCollector.start(packet.request);
            });
            ctx.setPacketHandled(true);
        }
    }

    /**
     * Client → server: a batch of recipes. The last batch has {@code last} set and carries how many
     * recipes the client left out per category (crafting and stonecutter recipes come from the
     * server; information pages, fuel, composting and the anvil make no patterns); {@code error}
     * non-empty means the client could not do it at all.
     */
    public record Recipes(int request, List<JeiRecipeData> recipes, Map<ResourceLocation, Integer> skipped, boolean last,
                          String error) {
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(request);
            buf.writeVarInt(recipes.size());
            for (JeiRecipeData r : recipes)
                r.write(buf);
            buf.writeVarInt(skipped.size());
            skipped.forEach((k, v) -> {
                buf.writeResourceLocation(k);
                buf.writeVarInt(v);
            });
            buf.writeBoolean(last);
            buf.writeUtf(error);
        }

        public static Recipes read(FriendlyByteBuf buf) {
            int request = buf.readVarInt();
            int n = buf.readVarInt();
            List<JeiRecipeData> recipes = new ArrayList<>(n);
            for (int i = 0; i < n; i++)
                recipes.add(JeiRecipeData.read(buf));
            int m = buf.readVarInt();
            Map<ResourceLocation, Integer> skipped = new LinkedHashMap<>();
            for (int i = 0; i < m; i++)
                skipped.put(buf.readResourceLocation(), buf.readVarInt());
            return new Recipes(request, recipes, skipped, buf.readBoolean(), buf.readUtf());
        }

        public static Recipes failure(int request, String why) {
            return new Recipes(request, List.of(), Map.of(), true, why);
        }

        public static void handle(Recipes packet, Supplier<NetworkEvent.Context> context) {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> JeiRequests.received(packet, ctx.getSender()));
            ctx.setPacketHandled(true);
        }
    }
}
