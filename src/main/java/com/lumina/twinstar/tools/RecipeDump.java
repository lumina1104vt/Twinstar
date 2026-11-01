package com.lumina.twinstar.tools;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lumina.twinstar.Twinstar;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes every recipe of a modpack (all mods, datapacks and KubeJS changes) to a JSON file, for the
 * offline engine benchmark ({@code gradlew :core:recipeBench}): the recipes {@code gen_patterns}
 * makes patterns of, read the same way.
 * <ul>
 *   <li>{@code /twinstar dump_recipes [file]} (op level 2, by a player with JEI); default
 *   {@code <server dir>/twinstar/recipes.json}</li>
 *   <li>{@code -Dtwinstar.dumpRecipesOnStart=<file>} no longer works (there is no player to ask
 *   for JEI's recipes at start); {@code -Dtwinstar.stopAfterDump=true} still stops the server once
 *   the startup commands and any pattern generation are done</li>
 *   <li>dev automation: {@code -Dtwinstar.startupCommands="cmd;@40 cmd"} runs commands as the
 *   console after start ({@code @n}: n ticks later); with stopAfterDump the server stops once they
 *   and any pattern generation are done</li>
 * </ul>
 * Format 3: {@code items} = key names (index = id in the file; items and fluids), compared like
 * the engine's {@code ItemIndex} (key + data components; see {@link KeyNames}); each recipe has
 * {@code in} = [count, [key, alternatives…]] per distinct ingredient, {@code out} = [key, count]
 * or [key, count, probability] for an output that does not always come.
 * <ul>
 *   <li>crafting and stonecutter recipes, from the server: a third element {@code "container"} in
 *   {@code in} when the item comes back unchanged, {@code rem} = [key, count] for other items left
 *   behind (the bucket of a water bucket, a damaged tool), classified like {@code PatternCompiler}
 *   does for AE2's crafting patterns;</li>
 *   <li>everything else, from the player's JEI by {@link JeiRecipes} (also {@code "category"}, the
 *   JEI category): what the processing pattern of {@code gen_patterns} would hold, its inputs with
 *   all the options JEI cycles through.</li>
 * </ul>
 * What {@code gen_patterns} leaves out is left out here too (pure A → A, smithing trims, …).
 */
public final class RecipeDump {
    /** alternatives kept per ingredient (big tags such as "any planks" are cut) */
    private static final int MAX_ALTERNATIVES = 32;

    private RecipeDump() {
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("twinstar").requires(s -> s.hasPermission(2))
                .then(Commands.literal("dump_recipes")
                        .executes(ctx -> dump(ctx.getSource(), null))
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> dump(ctx.getSource(), StringArgumentType.getString(ctx, "file"))))));
    }

    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        if (Boolean.getBoolean("twinstar.genPatternsCheck"))
            Twinstar.LOGGER.info(PatternGen.check(server.overworld()));
        // dev automation: commands run as the console, ';' between them; "@40 cmd" runs 40 ticks later
        String commands = System.getProperty("twinstar.startupCommands", "");
        for (String c : commands.split(";")) {
            String cmd = c.trim();
            if (cmd.isEmpty())
                continue;
            int delay = 0;
            if (cmd.startsWith("@")) {
                int space = cmd.indexOf(' ');
                delay = Integer.parseInt(cmd.substring(1, space));
                cmd = cmd.substring(space + 1).trim();
            }
            SCHEDULED.add(new Scheduled(server.getTickCount() + delay, cmd));
        }
        stopWhenDone = Boolean.getBoolean("twinstar.stopAfterDump");
        String file = System.getProperty("twinstar.dumpRecipesOnStart");
        if (file != null && !file.isBlank())
            Twinstar.LOGGER.warn("[雙星] 不再支援 -Dtwinstar.dumpRecipesOnStart：合成表要從玩家的 JEI 讀取，"
                    + "請進遊戲後由裝有 JEI 的玩家執行 /twinstar dump_recipes");
    }

    private record Scheduled(int tick, String command) {
    }

    private static final List<Scheduled> SCHEDULED = new ArrayList<>();
    private static boolean stopWhenDone;

    /** Runs the scheduled startup commands; with stopAfterDump, stops once they and any pattern generation are done. */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        for (int i = 0; i < SCHEDULED.size(); i++) {
            Scheduled s = SCHEDULED.get(i);
            if (s.tick() <= now) {
                SCHEDULED.remove(i--);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), s.command());
            }
        }
        if (stopWhenDone && SCHEDULED.isEmpty() && PatternGen.isIdle() && RUNNING.isEmpty()) {
            stopWhenDone = false;
            server.halt(false);
        }
    }

    private static int dump(CommandSourceStack source, String file) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("[雙星] 合成表要從玩家的 JEI 讀取，需要由裝有 JEI 的玩家下指令"));
            return 0;
        }
        Path path = file != null ? Path.of(file) : server.getServerDirectory().resolve("twinstar").resolve("recipes.json");
        Dump d = new Dump(source, server.overworld(), path);
        RUNNING.add(d);
        JeiRequests.request(player, d);
        source.sendSuccess(() -> Component.literal("[雙星] 開始匯出合成表：伺服器的合成與切石機配方 " + d.recipes.size()
                + " 條，其他配方從你的 JEI 讀取"), true);
        return d.recipes.size();
    }

    private static final List<Dump> RUNNING = new ArrayList<>();

    static void onServerStopping() {
        RUNNING.clear();
    }

    /** One export: the server's crafting and stonecutter recipes at once, JEI's as they arrive. */
    private static final class Dump implements JeiRequests.Receiver {
        final CommandSourceStack source;
        final ServerLevel level;
        final Path path;
        final Map<AEKey, Integer> ids = new LinkedHashMap<>();
        final JsonArray recipes = new JsonArray();
        int skipped, selfRecipes, fromJei;
        final JeiRecipes.Tally tally = new JeiRecipes.Tally();

        Dump(CommandSourceStack source, ServerLevel level, Path path) {
            this.source = source;
            this.level = level;
            this.path = path;
            RegistryAccess registries = level.registryAccess();
            for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
                if (!(holder.value() instanceof CraftingRecipe) && !(holder.value() instanceof StonecutterRecipe))
                    continue;
                JsonObject json;
                try {
                    if (PatternGen.pureSelfRecipe(holder.value(), registries)) {
                        selfRecipes++;      // gen_patterns does not make these either
                        continue;
                    }
                    json = recipe(holder, registries, ids);
                } catch (RuntimeException e) {
                    json = null;    // a mod's recipe that cannot answer outside its machine
                }
                if (json == null) {
                    skipped++;
                    continue;
                }
                recipes.add(json);
            }
        }

        @Override
        public void recipes(List<JeiRecipeData> batch) {
            for (JeiRecipeData r : batch) {
                JeiRecipes.Converted c;
                try {
                    c = JeiRecipes.convert(r, level, tally);
                } catch (RuntimeException e) {
                    c = null;
                    tally.unholdable++;
                }
                if (c == null) {
                    tally.left(r.category(), 1);
                    continue;
                }
                tally.made(r.category());
                tally.chances(c);
                recipes.add(jeiRecipe(c, r.category(), ids));
                fromJei++;
            }
        }

        @Override
        public void done(Map<ResourceLocation, Integer> skippedByClient) {
            skippedByClient.forEach(tally::left);
            RUNNING.remove(this);
            try {
                write(level.registryAccess(), path, ids, recipes);
            } catch (IOException e) {
                source.sendFailure(Component.literal("[雙星] 匯出失敗：" + e.getMessage()));
                return;
            }
            StringBuilder sb = new StringBuilder("[雙星] 合成表已匯出：" + recipes.size() + " 條（其中從 JEI 讀取 " + fromJei + " 條");
            if (tally.chanceRecipes > 0)
                sb.append("，").append(tally.chanceRecipes).append(" 條有機率產物");
            sb.append("）；略過 ").append(skipped).append(" 條特殊或無法讀取的配方");
            sb.append(tally.leftOut());
            int self = selfRecipes + tally.selfLoops;
            if (self > 0)
                sb.append("、").append(self).append(" 條純粹 A 變 A 的配方");
            sb.append(" → ").append(path.toAbsolutePath());
            Twinstar.LOGGER.info("[雙星] 匯出合成表，各 JEI 分類（匯出／跳過）：{}", tally.categories(-1));
            String text = sb.toString();
            source.sendSuccess(() -> Component.literal(text), true);
        }

        @Override
        public void failed(String why) {
            RUNNING.remove(this);
            source.sendFailure(Component.literal("[雙星] 匯出失敗，讀不到 JEI 的配方：" + why));
        }
    }

    private static void write(RegistryAccess registries, Path path, Map<AEKey, Integer> ids, JsonArray recipes) throws IOException {
        JsonArray items = new JsonArray();
        for (AEKey key : ids.keySet())
            items.add(KeyNames.of(key, registries));
        JsonObject root = new JsonObject();
        root.addProperty("format", 3);
        root.add("items", items);
        root.add("recipes", recipes);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);
        Files.writeString(path, new GsonBuilder().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static JsonObject jeiRecipe(JeiRecipes.Converted c, ResourceLocation category, Map<AEKey, Integer> ids) {
        JsonObject json = new JsonObject();
        json.addProperty("id", c.holder().id().toString());
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(c.holder().value().getType());
        json.addProperty("type", type == null ? "?" : type.toString());
        json.addProperty("category", category.toString());
        JsonArray in = new JsonArray();
        c.inputs().forEach((options, count) -> {
            JsonArray slot = new JsonArray();
            slot.add(count);
            JsonArray opts = new JsonArray();
            for (int i = 0; i < Math.min(options.size(), MAX_ALTERNATIVES); i++)
                opts.add(id(options.get(i), ids));
            slot.add(opts);
            in.add(slot);
        });
        json.add("in", in);
        JsonArray out = new JsonArray();
        c.outputs().forEach((key, count) -> {
            JsonArray o = new JsonArray();
            o.add(id(key, ids));
            o.add(count);
            Double chance = c.chances().get(key);
            if (chance != null)
                o.add(chance);
            out.add(o);
        });
        json.add("out", out);
        return json;
    }

    private static JsonObject recipe(RecipeHolder<?> holder, RegistryAccess registries, Map<AEKey, Integer> ids) {
        Recipe<?> r = holder.value();
        if (r.isSpecial())
            return null;    // fireworks, map cloning, …: the result depends on the actual items
        ItemStack result = r.getResultItem(registries);
        NonNullList<Ingredient> ingredients = r.getIngredients();
        if (result.isEmpty() || ingredients.isEmpty())
            return null;

        // identical ingredients (the same option list) are one input with a count
        Map<List<Integer>, Integer> inputs = new LinkedHashMap<>();
        Set<List<Integer>> containers = new HashSet<>();
        Map<Integer, Integer> remainders = new HashMap<>();
        for (Ingredient ing : ingredients) {
            if (ing.isEmpty())
                continue;   // an empty grid cell
            ItemStack[] options = ing.getItems();
            if (options.length == 0)
                return null;    // a tag with no items in this pack: nothing can fill it
            List<Integer> key = new ArrayList<>();
            for (ItemStack o : options) {
                int id = id(o, ids);
                if (!key.contains(id))
                    key.add(id);
                if (key.size() >= MAX_ALTERNATIVES)
                    break;
            }
            int count = Math.max(1, options[0].getCount());
            inputs.merge(key, count, Integer::sum);
            // like PatternCompiler: the same key back makes a container, anything else is a remainder
            ItemStack rest = options[0].getCraftingRemainingItem();
            if (rest.isEmpty())
                continue;
            if (AEItemKey.of(rest).equals(AEItemKey.of(options[0])))
                containers.add(key);
            else
                remainders.merge(id(rest, ids), rest.getCount() * count, Integer::sum);
        }
        if (inputs.isEmpty())
            return null;

        JsonObject json = new JsonObject();
        json.addProperty("id", holder.id().toString());
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(r.getType());
        json.addProperty("type", type == null ? "?" : type.toString());
        JsonArray in = new JsonArray();
        inputs.forEach((options, count) -> {
            JsonArray slot = new JsonArray();
            slot.add(count);
            JsonArray opts = new JsonArray();
            options.forEach(opts::add);
            slot.add(opts);
            if (containers.contains(options))
                slot.add("container");
            in.add(slot);
        });
        json.add("in", in);
        JsonArray out = new JsonArray();
        out.add(pair(id(result, ids), result.getCount()));
        json.add("out", out);
        if (!remainders.isEmpty()) {
            JsonArray rem = new JsonArray();
            remainders.forEach((item, count) -> rem.add(pair(item, count)));
            json.add("rem", rem);
        }
        return json;
    }

    private static JsonArray pair(int item, int count) {
        JsonArray a = new JsonArray();
        a.add(item);
        a.add(count);
        return a;
    }

    private static int id(ItemStack stack, Map<AEKey, Integer> ids) {
        return id(AEItemKey.of(stack), ids);
    }

    private static int id(AEKey key, Map<AEKey, Integer> ids) {
        return ids.computeIfAbsent(key, k -> ids.size());
    }
}
