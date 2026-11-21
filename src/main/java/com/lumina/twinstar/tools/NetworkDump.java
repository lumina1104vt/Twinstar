package com.lumina.twinstar.tools;

import appeng.api.stacks.AEKey;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import com.lumina.twinstar.service.TwinStarService;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes what the engine itself works on, for the offline benchmark ({@code gradlew :core:recipeBench}):
 * {@code /twinstar dump_network [file]} (op level 2), one file per network with a hub; default
 * {@code <server dir>/twinstar/network.json}, further networks {@code network-2.json}, ….
 * <p>
 * Unlike {@code dump_recipes} (every recipe the server knows, read through the vanilla recipe
 * interface), this is the network's patterns as {@code PatternCompiler} translated them, read from
 * the theory layer's last published version, and the network's stock as the engine takes it before
 * planning. Format: {@code items} = key names ({@link KeyNames}; index = the engine's item id),
 * {@code patterns} = {id, where, priority, subst, in: [[item, amount, kind, durability,
 * [alternatives…]]…], out: [[item, amount, byproduct]…]}, {@code stock} = [[item, amount]…], {@code infinite} =
 * [item…]. Amounts are decimal strings (they can exceed a long).
 */
public final class NetworkDump {
    private NetworkDump() {
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("twinstar").requires(s -> s.hasPermission(2))
                .then(Commands.literal("dump_network")
                        .executes(ctx -> dump(ctx.getSource(), null))
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> dump(ctx.getSource(), StringArgumentType.getString(ctx, "file"))))));
    }

    private static int dump(CommandSourceStack source, String file) {
        List<TwinStarService> nets = TwinStarService.withHub();
        if (nets.isEmpty()) {
            source.sendFailure(Component.literal("[雙星] 沒有任何網路有運作中的中樞"));
            return 0;
        }
        Path first = file != null ? Path.of(file) : source.getServer().getServerDirectory().toPath().resolve("twinstar").resolve("network.json");
        int written = 0;
        for (int n = 0; n < nets.size(); n++) {
            TwinStarService service = nets.get(n);
            Snapshot snap = service.getLastSnapshot();
            if (snap == null) {
                source.sendFailure(Component.literal("[雙星] 第 " + (n + 1) + " 個網路的引擎還沒更新過，略過"));
                continue;
            }
            Path path = n == 0 ? first : numbered(first, n + 1);
            try {
                write(service, snap, source.getServer().registryAccess(), path);
                written++;
                boolean upToDate = service.isUpToDate();
                source.sendSuccess(() -> Component.literal("[雙星] 網路已匯出：物品 " + snap.itemCount() + " 種、樣板版本 "
                        + snap.version() + (upToDate ? "" : "（引擎還有變動沒套用完，匯出的是上一個版本）") + " → "
                        + path.toAbsolutePath()), true);
            } catch (IOException e) {
                source.sendFailure(Component.literal("[雙星] 匯出失敗：" + e.getMessage()));
            }
        }
        return written;
    }

    /** network.json → network-2.json */
    private static Path numbered(Path first, int n) {
        String name = first.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String numbered = dot < 0 ? name + "-" + n : name.substring(0, dot) + "-" + n + name.substring(dot);
        return first.resolveSibling(numbered);
    }

    private static void write(TwinStarService service, Snapshot snap, RegistryAccess registries, Path path) throws IOException {
        // the stock first: taking it gives ids to keys the patterns never mention
        StockSnapshot stock = service.takeStock();
        ItemIndex index = service.getIndex();

        JsonArray patterns = new JsonArray();
        for (int p = 0; p < snap.patternCount(); p++) {
            Pattern pat = snap.pattern(p);
            if (pat != null)
                patterns.add(pattern(pat));
        }

        JsonArray stocked = new JsonArray();
        JsonArray infinite = new JsonArray();
        for (int i = 0; i < stock.size(); i++) {
            if (stock.isInfinite(i))
                infinite.add(i);
            if (stock.stock(i).signum() > 0) {
                JsonArray e = new JsonArray();
                e.add(i);
                e.add(stock.stock(i).bigValue().toString());
                stocked.add(e);
            }
        }

        JsonArray items = new JsonArray();
        for (int i = 0; i < index.size(); i++) {
            AEKey key = index.keyOf(i);
            items.add(key == null ? "?" : KeyNames.of(key, registries));
        }

        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.addProperty("source", "network");
        root.addProperty("version", snap.version());
        root.add("items", items);
        root.add("patterns", patterns);
        root.add("stock", stocked);
        root.add("infinite", infinite);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.writeString(path, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static JsonObject pattern(Pattern p) {
        JsonObject json = new JsonObject();
        json.addProperty("id", p.id);
        json.addProperty("where", p.where.name());
        json.addProperty("priority", p.priority);
        json.addProperty("subst", p.allowSubst);
        JsonArray in = new JsonArray();
        for (int i = 0; i < p.inputCount(); i++) {
            JsonArray slot = new JsonArray();
            slot.add(p.inputItem(i));
            slot.add(p.inputAmount(i).bigValue().toString());
            slot.add(p.inputKind(i).name());
            slot.add(p.inputDurability(i));
            JsonArray alts = new JsonArray();
            for (int j = 0; j < p.alternativeCount(i); j++)
                alts.add(p.alternative(i, j));
            slot.add(alts);
            in.add(slot);
        }
        json.add("in", in);
        JsonArray out = new JsonArray();
        for (int i = 0; i < p.outputCount(); i++) {
            JsonArray o = new JsonArray();
            o.add(p.outputItem(i));
            o.add(p.outputAmount(i).bigValue().toString());
            o.add(p.isByproduct(i));
            out.add(o);
        }
        json.add("out", out);
        return json;
    }
}
