package com.lumina.twinstar.tools;

import com.lumina.twinstar.Twinstar;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two packets that bring JEI's recipes from a player's client to the server: the server asks
 * ({@link Request}), the client answers in batches ({@link Recipes}, the last one marked).
 */
public final class JeiPackets {
    private JeiPackets() {
    }

    /** Server → client: send me what your JEI shows. */
    public record Request(int request) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "jei_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.request), buf -> new Request(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        /** Client side. Without JEI the answer is a refusal; the collector is only loaded when JEI is there. */
        public static void handle(Request packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (!ModList.get().isLoaded("jei")) {
                    PacketDistributor.sendToServer(Recipes.failure(packet.request, "客戶端沒有安裝 JEI"));
                    return;
                }
                com.lumina.twinstar.client.jei.JeiCollector.start(packet.request);
            });
        }
    }

    /**
     * Client → server: a batch of recipes. The last batch has {@code last} set and carries how many
     * recipes the client left out per category (crafting and stonecutter recipes come from the
     * server; information pages, fuel, composting and the anvil make no patterns); {@code error}
     * non-empty means the client could not do it at all.
     */
    public record Recipes(int request, List<JeiRecipeData> recipes, Map<ResourceLocation, Integer> skipped, boolean last,
                          String error) implements CustomPacketPayload {
        public static final Type<Recipes> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "jei_recipes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Recipes> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.request);
                    buf.writeVarInt(p.recipes.size());
                    for (JeiRecipeData r : p.recipes)
                        JeiRecipeData.STREAM_CODEC.encode(buf, r);
                    buf.writeVarInt(p.skipped.size());
                    p.skipped.forEach((k, v) -> {
                        buf.writeResourceLocation(k);
                        buf.writeVarInt(v);
                    });
                    buf.writeBoolean(p.last);
                    buf.writeUtf(p.error);
                },
                buf -> {
                    int request = buf.readVarInt();
                    int n = buf.readVarInt();
                    List<JeiRecipeData> recipes = new ArrayList<>(n);
                    for (int i = 0; i < n; i++)
                        recipes.add(JeiRecipeData.STREAM_CODEC.decode(buf));
                    int m = buf.readVarInt();
                    Map<ResourceLocation, Integer> skipped = new LinkedHashMap<>();
                    for (int i = 0; i < m; i++)
                        skipped.put(buf.readResourceLocation(), buf.readVarInt());
                    return new Recipes(request, recipes, skipped, buf.readBoolean(), buf.readUtf());
                });

        public static Recipes failure(int request, String why) {
            return new Recipes(request, List.of(), Map.of(), true, why);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public static void handle(Recipes packet, IPayloadContext context) {
            context.enqueueWork(() -> JeiRequests.received(packet, context.player()));
        }
    }
}
