package com.lumina.twinstar.tools;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.PatternChances;
import com.lumina.twinstar.storage.TwinstarContent;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import com.lumina.twinstar.algorithm.ae2.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Encodes every recipe as an AE2 pattern and puts them into a block (anything that takes items:
 * the test hub with its 50,000 slots, a chest, a pattern provider). Op level 2.
 * <pre>
 * /twinstar gen_patterns &lt;pos&gt; [filter]                          all into pos
 * /twinstar gen_patterns &lt;pos&gt; no_processing [filter]            no processing patterns
 * /twinstar gen_patterns &lt;pos&gt; processing_to &lt;pos2&gt; [filter]    processing patterns into pos2
 * </pre>
 * {@code filter} keeps the recipes whose id contains it ({@code minecraft:}, {@code ae2:}, {@code iron}).
 * The work runs on the server thread a little each tick ({@link #TICK_BUDGET_NANOS}), so a pack
 * with tens of thousands of recipes does not freeze the server; {@code /twinstar gen_patterns cancel}
 * stops it. Patterns can only be put into blocks on the server thread, hence no worker thread.
 * <ul>
 *   <li>crafting table recipes (the server's) → crafting patterns, laid out as the recipe is shaped,
 *   substitutes allowed (tag recipes take any matching item);</li>
 *   <li>stonecutter recipes (the server's) → stonecutting patterns;</li>
 *   <li>everything else (furnaces, smithing, mod machines) → processing patterns from what the
 *   player's JEI shows (the vanilla recipe interface has no amounts for most mod machines, no fluids
 *   and only the first output): every input slot's first option with its amount, every output.
 *   Outputs that come only with some probability ({@link RecipeChances}, from the server's recipe
 *   by the id JEI gives) are marked on the pattern ({@link PatternChances}). Items left behind
 *   that JEI does not show as outputs (the bucket of a water bucket) are not written: an output
 *   written in a pattern is a main product to the engine, and a remainder is not one.</li>
 * </ul>
 * The JEI part needs a player with JEI to run the command (the server asks the player's client).
 * Left out: recipes JEI has no id for or the server does not know (their outputs' probabilities
 * cannot be read), smithing trims (one recipe per armour × trim × material), the anvil, recipes with
 * a slot AE2 cannot hold, special recipes (fireworks, map cloning) and pure A → A recipes (only the
 * result item goes in). The summary counts them, per JEI category. Every pattern is decoded once;
 * one AE2 does not accept as crafting/stonecutting is made as a processing pattern instead.
 */
public final class PatternGen {
    private PatternGen() {
    }

    /** Where processing patterns go: with the rest, nowhere, or into a second block. */
    private enum ProcessingTo { SAME, NONE, OTHER }

    /** Generations in progress, stepped on the server thread a little each tick. */
    private static final List<Job> JOBS = new ArrayList<>();
    /** server-thread time all jobs together may use per tick (a tick is 50 ms) */
    private static final long TICK_BUDGET_NANOS = 20_000_000L;
    private static final long PROGRESS_EVERY_NANOS = 10_000_000_000L;

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("twinstar").requires(s -> s.hasPermission(2))
                .then(Commands.literal("gen_patterns")
                        .then(Commands.literal("cancel").executes(ctx -> cancel(ctx.getSource())))
                        .then(withFilter(Commands.argument("pos", BlockPosArgument.blockPos()), ProcessingTo.SAME)
                                .then(withFilter(Commands.literal("no_processing"), ProcessingTo.NONE))
                                .then(Commands.literal("processing_to")
                                        .then(withFilter(Commands.argument("processing_pos", BlockPosArgument.blockPos()),
                                                ProcessingTo.OTHER))))));
    }

    /** {@code node} runs as is, or with a trailing filter. */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T withFilter(T node, ProcessingTo mode) {
        return node.executes(ctx -> start(ctx, mode, ""))
                .then(Commands.argument("filter", StringArgumentType.greedyString())
                        .executes(ctx -> start(ctx, mode, StringArgumentType.getString(ctx, "filter"))));
    }

    /** Checks the targets and queues the job; the work happens in {@link #onServerTick}. */
    private static int start(CommandContext<CommandSourceStack> ctx, ProcessingTo mode, String filter) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
        BlockPos processingPos = mode == ProcessingTo.OTHER ? BlockPosArgument.getLoadedBlockPos(ctx, "processing_pos") : null;
        if (handlerAt(source.getLevel(), pos) == null) {
            source.sendFailure(Component.literal("[雙星] " + pos.toShortString() + " 的方塊不能放入物品"));
            return 0;
        }
        if (processingPos != null && handlerAt(source.getLevel(), processingPos) == null) {
            source.sendFailure(Component.literal("[雙星] " + processingPos.toShortString() + " 的方塊不能放入物品"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (mode != ProcessingTo.NONE && player == null) {
            source.sendFailure(Component.literal("[雙星] 處理樣板要從玩家的 JEI 讀配方，需要由裝有 JEI 的玩家下指令（或加 no_processing 只生成合成與切石機樣板）"));
            return 0;
        }
        Job job = new Job(source, source.getLevel(), pos, processingPos, mode, filter);
        JOBS.add(job);
        if (mode != ProcessingTo.NONE)
            JeiRequests.request(player, job);
        source.sendSuccess(() -> Component.literal("[雙星] 開始生成樣板：伺服器的合成與切石機配方 " + job.recipes.size() + " 條"
                + (mode != ProcessingTo.NONE ? "，其他配方從你的 JEI 讀取" : "")
                + "，在背景分批進行（每 tick 最多 20 毫秒）；/twinstar gen_patterns cancel 可取消"), true);
        return job.recipes.size();
    }

    private static int cancel(CommandSourceStack source) {
        int n = JOBS.size();
        for (Job job : JOBS)
            job.report("[雙星] 已取消生成樣板（" + job.next + " / " + job.recipes.size() + "）：", false);
        JOBS.clear();
        source.sendSuccess(() -> Component.literal("[雙星] 取消了 " + n + " 個生成樣板的工作"), true);
        return n;
    }

    /** No generation running (dev automation waits for this before stopping the server). */
    public static boolean isIdle() {
        return JOBS.isEmpty();
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (JOBS.isEmpty())
            return;
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext() && System.nanoTime() < deadline) {
            Job job = it.next();
            if (job.step(deadline))
                it.remove();
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        JOBS.clear();
    }

    private static @Nullable IItemHandler handlerAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        return com.lumina.twinstar.util.Caps.item(level, pos, null);
    }

    /**
     * Startup check ({@code -Dtwinstar.genPatternsCheck=true}): encode and decode at once, insert
     * nothing. Crafting and stonecutting only: processing patterns need a player's JEI.
     */
    public static String check(ServerLevel level) {
        Job job = new Job(null, level, null, null, ProcessingTo.NONE, "");
        job.step(Long.MAX_VALUE);
        return job.summary() + "（處理樣板需要玩家的 JEI，不在檢查範圍內）";
    }

    /**
     * One generation: the server's crafting and stonecutter recipes, then what the player's JEI
     * sends (as it arrives), how far it got, what went where.
     */
    private static final class Job implements JeiRequests.Receiver {
        final @Nullable CommandSourceStack source;
        final ServerLevel level;
        final @Nullable BlockPos pos;
        final @Nullable BlockPos processingPos;
        final ProcessingTo mode;
        final String filter;
        final List<RecipeHolder<?>> recipes = new ArrayList<>();
        int next;
        int crafting, stonecutting, processing, skipped, selfLoops;
        final List<String> selfLoopIds = new ArrayList<>();
        final Sink main = new Sink();
        final Sink other = new Sink();
        long lastProgress = System.nanoTime();

        // ── from JEI ──
        final ArrayDeque<JeiRecipeData> jei = new ArrayDeque<>();
        boolean jeiDone;
        @Nullable String jeiFailure;
        int jeiSeen;
        final JeiRecipes.Tally jeiTally = new JeiRecipes.Tally();

        Job(@Nullable CommandSourceStack source, ServerLevel level, @Nullable BlockPos pos, @Nullable BlockPos processingPos,
            ProcessingTo mode, String filter) {
            this.source = source;
            this.level = level;
            this.pos = pos;
            this.processingPos = processingPos;
            this.mode = mode;
            this.filter = filter;
            for (RecipeHolder<?> h : RecipeHolder.all(level.getRecipeManager().getRecipes()))
                if ((h.value() instanceof CraftingRecipe || h.value() instanceof StonecutterRecipe)
                        && (filter.isEmpty() || h.id().toString().contains(filter)))
                    recipes.add(h);
            jeiDone = mode == ProcessingTo.NONE;
        }

        @Override
        public void recipes(List<JeiRecipeData> batch) {
            jei.addAll(batch);
            jeiSeen += batch.size();
        }

        @Override
        public void done(Map<ResourceLocation, Integer> skippedByClient) {
            skippedByClient.forEach(jeiTally::left);
            jeiDone = true;
        }

        @Override
        public void failed(String why) {
            jeiFailure = why;
            jeiDone = true;
        }

        /** Works until {@code deadline}; true when done or stopped. */
        boolean step(long deadline) {
            // the targets are looked up again every tick: the blocks may have been broken meanwhile
            IItemHandler target = null;
            IItemHandler processingTarget = null;
            if (pos != null) {
                target = handlerAt(level, pos);
                if (target == null)
                    return stop(pos);
                processingTarget = target;
                if (mode == ProcessingTo.OTHER) {
                    processingTarget = handlerAt(level, processingPos);
                    if (processingTarget == null)
                        return stop(processingPos);
                }
            }
            while (next < recipes.size() && System.nanoTime() < deadline)
                one(recipes.get(next++), target, processingTarget);
            while (next >= recipes.size() && !jei.isEmpty() && System.nanoTime() < deadline)
                oneJei(jei.poll(), target, processingTarget);
            if (jeiFailure != null) {
                report("[雙星] 讀不到 JEI 的配方：" + jeiFailure + "。" + summary(), false);
                return true;
            }
            if (next >= recipes.size() && jeiDone && jei.isEmpty()) {
                report(summary(), true);
                return true;
            }
            long now = System.nanoTime();
            if (now - lastProgress > PROGRESS_EVERY_NANOS) {
                lastProgress = now;
                report("[雙星] 生成樣板進度：伺服器配方 " + next + " / " + recipes.size()
                        + (mode != ProcessingTo.NONE ? "；JEI 已收到 " + jeiSeen + " 條" + (jeiDone ? "（全部）" : "") : ""), true);
            }
            return false;
        }

        /** One recipe from JEI → a processing pattern, or counted as left out. */
        private void oneJei(JeiRecipeData r, @Nullable IItemHandler target, @Nullable IItemHandler processingTarget) {
            if (!filter.isEmpty() && (r.id() == null || !r.id().toString().contains(filter)))
                return;
            ItemStack pattern = null;
            try {
                JeiRecipes.Converted c = JeiRecipes.convert(r, level, jeiTally);
                if (c != null) {
                    pattern = PatternDetailsHelper.encodeProcessingPattern(c.patternInputs().toArray(new GenericStack[0]), c.patternOutputs().toArray(new GenericStack[0]));
                    if (!c.chances().isEmpty()) {
                        List<PatternChances.Entry> entries = new ArrayList<>();
                        c.chances().forEach((k, p) -> entries.add(new PatternChances.Entry(k, p)));
                        TwinstarContent.PATTERN_CHANCES.set(pattern, new PatternChances(entries));
                    }
                    if (PatternDetailsHelper.decodePattern(pattern, level) == null) {
                        pattern = null;
                        jeiTally.unholdable++;
                    } else {
                        jeiTally.chances(c);
                    }
                }
            } catch (RuntimeException e) {
                pattern = null;
                jeiTally.unholdable++;
            }
            if (pattern == null) {
                jeiTally.left(r.category(), 1);
                return;
            }
            jeiTally.made(r.category());
            processing++;
            if (mode == ProcessingTo.SAME)
                main.put(target, pattern);
            else if (mode == ProcessingTo.OTHER)
                other.put(processingTarget, pattern);
        }

        private void one(RecipeHolder<?> holder, @Nullable IItemHandler target, @Nullable IItemHandler processingTarget) {
            ItemStack pattern;
            try {
                if (pureSelfRecipe(holder.value(), level.registryAccess())) {
                    selfLoops++;
                    if (selfLoopIds.size() < 5)
                        selfLoopIds.add(holder.id().toString());
                    return;
                }
                pattern = encode(holder, level.registryAccess(), level);
            } catch (RuntimeException e) {
                pattern = null;     // a mod's recipe that cannot answer outside its machine
            }
            if (pattern == null) {
                skipped++;
                return;
            }
            switch (kindOf(holder, pattern, level)) {
                case 0 -> {
                    crafting++;
                    main.put(target, pattern);
                }
                case 1 -> {
                    stonecutting++;
                    main.put(target, pattern);
                }
                default -> {
                    processing++;
                    if (mode == ProcessingTo.SAME)
                        main.put(target, pattern);
                    else if (mode == ProcessingTo.OTHER)
                        other.put(processingTarget, pattern);
                }
            }
        }

        private boolean stop(BlockPos where) {
            report("[雙星] 生成樣板中止：" + where.toShortString() + " 的方塊不見了或所在區塊已卸載（"
                    + next + " / " + recipes.size() + "）。" + summary(), false);
            return true;
        }

        void report(String text, boolean ok) {
            if (source == null)
                return;
            if (ok)
                source.sendSuccess(() -> Component.literal(text), true);
            else
                source.sendFailure(Component.literal(text));
        }

        String summary() {
            StringBuilder sb = new StringBuilder("[雙星] 生成樣板：合成 " + crafting + "、切石機 " + stonecutting);
            sb.append(mode == ProcessingTo.NONE ? "（不生成處理樣板）" : "、處理 " + processing);
            if (jeiTally.chanceRecipes > 0)
                sb.append("（其中 ").append(jeiTally.chanceRecipes).append(" 張有機率產物，共 ").append(jeiTally.chanceOutputs)
                        .append(" 個機率輸出）");
            sb.append("；略過 ").append(skipped).append(" 條特殊或無法讀取的配方");
            sb.append(jeiTally.leftOut());
            int self = selfLoops + jeiTally.selfLoops;
            if (self > 0) {
                List<String> ids = new ArrayList<>(selfLoopIds);
                ids.addAll(jeiTally.selfLoopIds);
                List<String> shown = ids.subList(0, Math.min(5, ids.size()));
                sb.append("、").append(self).append(" 條純粹 A 變 A 的配方（").append(String.join("、", shown))
                        .append(self > shown.size() ? "…" : "").append("）");
            }
            sb.append("。");
            if (!jeiTally.perCategory.isEmpty()) {
                // the categories with the most recipes first; the full list goes to the log
                Twinstar.LOGGER.info("[雙星] 生成樣板，各 JEI 分類（生成／跳過）：{}", jeiTally.categories(-1));
                sb.append("各 JEI 分類（生成／跳過，完整清單在日誌）：").append(jeiTally.categories(8)).append("。");
            }
            if (pos == null)
                return sb.toString();
            sb.append("放入 ").append(main.inserted).append(" 張");
            if (main.full > 0)
                sb.append("，目標已滿，剩 ").append(main.full).append(" 張沒放");
            if (mode == ProcessingTo.OTHER) {
                sb.append("；處理樣板放入另一個容器 ").append(other.inserted).append(" 張");
                if (other.full > 0)
                    sb.append("，已滿，剩 ").append(other.full).append(" 張沒放");
            }
            return sb.toString();
        }
    }

    /** One target's tally: once it is full the rest is only counted. */
    private static final class Sink {
        int inserted;
        int full;

        void put(@Nullable IItemHandler handler, ItemStack pattern) {
            if (handler == null)
                return;
            if (full == 0 && ItemHandlerHelper.insertItem(handler, pattern, false).isEmpty())
                inserted++;
            else
                full++;
        }
    }

    /**
     * Pure A → A: every input is the result item itself and nothing else (a tag ingredient counts
     * only when all its options are that item). Usually a recipe that clears an item's data. Not
     * generated. Template duplication (template + diamonds → 2 templates) and upgrades (terminal +
     * another terminal → terminal) need other inputs and stay.
     */
    static boolean pureSelfRecipe(Recipe<?> r, RegistryAccess registries) {
        ItemStack result = r.getResultItem(registries);
        if (result.isEmpty())
            return false;
        boolean any = false;
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty())
                continue;
            ItemStack[] options = ing.getItems();
            if (options.length == 0)
                return false;
            for (ItemStack option : options)
                if (!option.is(result.getItem()))
                    return false;
            any = true;
        }
        return any;
    }

    /** 0 = crafting, 1 = stonecutting, 2 = processing (as encoded). */
    private static int kindOf(RecipeHolder<?> holder, ItemStack pattern, ServerLevel level) {
        var details = PatternDetailsHelper.decodePattern(pattern, level);
        String name = details == null ? "" : details.getClass().getSimpleName();
        return name.contains("Crafting") ? 0 : name.contains("Stonecutting") ? 1 : 2;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable ItemStack encode(RecipeHolder<?> holder, RegistryAccess registries, ServerLevel level) {
        Recipe<?> r = holder.value();
        if (r.isSpecial())
            return null;
        ItemStack result = r.getResultItem(registries);
        if (result.isEmpty())
            return null;
        ItemStack pattern = null;
        if (r instanceof CraftingRecipe) {
            ItemStack[] grid = grid(r);
            if (grid != null)
                pattern = PatternDetailsHelper.encodeCraftingPattern((CraftingRecipe) r, grid, result, true, false);
        } else if (r instanceof StonecutterRecipe && !r.getIngredients().isEmpty()) {
            ItemStack[] in = r.getIngredients().get(0).getItems();
            if (in.length > 0)
                pattern = PatternDetailsHelper.encodeStonecuttingPattern((StonecutterRecipe) r,
                        AEItemKey.of(in[0]), AEItemKey.of(result), true);
        }
        if (pattern != null && PatternDetailsHelper.decodePattern(pattern, level) != null)
            return pattern;
        return processing(r, result, level);
    }

    /** The 3×3 grid as the recipe is shaped (shapeless: in order); null when it does not fit. */
    private static ItemStack @Nullable [] grid(Recipe<?> r) {
        NonNullList<Ingredient> ings = r.getIngredients();
        ItemStack[] grid = new ItemStack[9];
        java.util.Arrays.fill(grid, ItemStack.EMPTY);
        int width = r instanceof ShapedRecipe s ? s.getWidth() : 3;
        if (width > 3 || ings.size() > 9)
            return null;
        for (int i = 0; i < ings.size(); i++) {
            Ingredient ing = ings.get(i);
            if (ing.isEmpty())
                continue;
            ItemStack[] options = ing.getItems();
            if (options.length == 0)
                return null;
            int slot = (i / width) * 3 + i % width;
            if (slot >= 9)
                return null;
            grid[slot] = options[0].copyWithCount(1);
        }
        return grid;
    }

    private static @Nullable ItemStack processing(Recipe<?> r, ItemStack result, ServerLevel level) {
        Map<AEItemKey, Long> in = new LinkedHashMap<>();
        Map<AEItemKey, Long> out = new LinkedHashMap<>();
        out.merge(AEItemKey.of(result), (long) result.getCount(), Long::sum);
        for (Ingredient ing : r.getIngredients()) {
            if (ing.isEmpty())
                continue;
            ItemStack[] options = ing.getItems();
            if (options.length == 0)
                return null;
            long count = Math.max(1, options[0].getCount());
            in.merge(AEItemKey.of(options[0]), count, Long::sum);
        }
        if (in.isEmpty())
            return null;
        List<GenericStack> inputs = new ArrayList<>();
        in.forEach((k, v) -> inputs.add(new GenericStack(k, v)));
        List<GenericStack> outputs = new ArrayList<>();
        out.forEach((k, v) -> outputs.add(new GenericStack(k, v)));
        ItemStack pattern = PatternDetailsHelper.encodeProcessingPattern(inputs.toArray(new GenericStack[0]), outputs.toArray(new GenericStack[0]));
        return PatternDetailsHelper.decodePattern(pattern, level) != null ? pattern : null;
    }
}
