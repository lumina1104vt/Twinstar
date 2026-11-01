package com.lumina.twinstar.bench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.OrderMode;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import com.lumina.twinstar.algorithm.planning.OrderResult;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.algorithm.planning.Planner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the engine on a real modpack's recipes, dumped in game with {@code /twinstar dump_recipes}
 * (every recipe the server knows), or on a network's own patterns and stock, dumped with
 * {@code /twinstar dump_network} (what the engine works on in game; benched with its real stock too).
 * <pre>
 * gradlew :core:recipeBench -Drecipes=path/to/recipes.json [-Dorders=300] [-Dseed=1]
 * </pre>
 * Measures the theory layer's full build, incremental updates (stock changes, patterns removed and
 * added back, each checked against a fresh rebuild) and random orders under two stocks: "creative"
 * (every raw material infinite) and "survival" (random finite stock). Orders run with the real time
 * limits (0.5 s soft, 1 s hard). The report is printed and written next to the recipe file;
 * the run fails only when the engine throws.
 */
class RecipePackBench {
    private static final String[] AMOUNT_LABELS = {"1", "64", "1e6", "1e18", "1e100", "1e10000"};
    private static final Amount[] AMOUNTS = {
            Amount.ONE, Amount.of(64), Amount.of(1_000_000), Amount.of(BigInteger.TEN.pow(18)),
            Amount.of(BigInteger.TEN.pow(100)), Amount.of(BigInteger.TEN.pow(10000))};
    private static final Amount UNLIMITED = Amount.of(BigInteger.ONE.shiftLeft(1 << 16));

    private final StringBuilder report = new StringBuilder();
    private final List<String> errors = new ArrayList<>();
    private List<String> items;
    /** the network's own stock when the file is a network dump, else null */
    private StockSnapshot networkStock;
    private boolean listedImpossible;

    /** Why the theory layer says x cannot be made: a growth loop needing a seed, or what its routes lack. */
    private String why(Snapshot snap, int x) {
        if (snap.scc(x) >= 0)
            return "策略環 #" + snap.scc(x) + "（" + snap.sccAt(snap.scc(x)).kind + "）";
        List<String> routes = new ArrayList<>();
        for (int p : snap.producers(x)) {
            Pattern pat = snap.pattern(p);
            List<String> lacking = new ArrayList<>();
            for (int i = 0; i < pat.inputCount(); i++)
                if (snap.logCost(pat.inputItem(i)) == LogMath.INF)
                    lacking.add(items.get(pat.inputItem(i)));
            routes.add(pat.source() + (lacking.isEmpty() ? "（輸入都做得出來？）" : " 缺 " + String.join("、", lacking)));
            if (routes.size() >= 3)
                break;
        }
        return String.join("；", routes);
    }

    /** the loops are listed once (both scenarios have the same patterns) */
    private boolean listedLoops;

    @Test
    void run() throws IOException {
        String file = System.getProperty("recipes", "");
        Assumptions.assumeTrue(!file.isBlank(), "no -Drecipes=<file>: nothing to bench");
        Path path = Path.of(file);
        int orders = Integer.getInteger("orders", 300);
        long seed = Long.getLong("seed", 1L);

        List<Pattern> patterns = load(path);
        line("合成表：%s", path.toAbsolutePath());
        line("物品 %d 種，樣板 %d 張（隨機種子 %d）", items.size(), patterns.size(), seed);

        BitSet produced = new BitSet();
        for (Pattern p : patterns)
            for (int o = 0; o < p.outputCount(); o++)
                produced.set(p.outputItem(o));
        BitSet raws = new BitSet();
        for (int i = 0; i < items.size(); i++)
            if (!produced.get(i))
                raws.set(i);
        line("原料（沒有配方做得出來）%d 種", raws.cardinality());

        // "raw" for a creative cell: no recipe, or mined (only loops without an outside input make
        // it, raw iron ⇄ raw iron block)
        BitSet mined = (BitSet) raws.clone();
        {
            TheoryLayer probe = new TheoryLayer();
            patterns.forEach(probe::addPattern);
            probe.applyStock(stock("creative", raws, new Random(seed)));
            Snapshot s = probe.flush();
            for (int x = 0; x < s.itemCount(); x++)
                if (s.isMined(x))
                    mined.set(x);
        }
        line("創造情境的無限來源：沒有配方的 %d 種 + 挖礦取得（只有無外部輸入的循環做得出來）的 %d 種",
                raws.cardinality(), mined.cardinality() - raws.cardinality());

        // a network dump also has the network's own stock: bench it as it is, too
        String[] scenarios = networkStock != null
                ? new String[]{"network", "creative", "survival"} : new String[]{"creative", "survival"};
        for (String scenario : scenarios) {
            Random rnd = new Random(seed);
            StockSnapshot stock = stock(scenario, scenario.equals("creative") ? mined : raws, rnd);
            line("");
            line("══ 庫存情境：%s ══", switch (scenario) {
                case "network" -> "網路的實際庫存";
                case "creative" -> "創造（所有原料無限）";
                default -> "生存（隨機有限庫存）";
            });
            if (!scenario.equals(scenarios[0]))
                line("（策略環同上）");
            TheoryLayer theory = new TheoryLayer();
            Snapshot snap = theoryBench(theory, patterns, stock, rnd);
            coldOrders(snap, stock);
            orderBench(snap, stock, rnd, orders);
            String trace = System.getProperty("trace", "");
            if (!trace.isBlank())
                trace(snap, stock, trace);
        }

        if (!errors.isEmpty()) {
            line("");
            line("══ 例外 %d 個（前 20 個）══", errors.size());
            errors.stream().limit(20).forEach(this::line);
        }
        String text = report.toString();
        System.out.println(text);
        Path out = path.resolveSibling(path.getFileName() + ".report.txt");
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.out.println("報告：" + out.toAbsolutePath());
        assertTrue(errors.isEmpty(), errors.size() + " exceptions, see the report");
    }

    // ───────────────────────────── loading ─────────────────────────────

    private List<Pattern> load(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        items = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("items"))
            items.add(e.getAsString());
        if (root.has("patterns"))
            return loadNetwork(root);
        List<Pattern> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("recipes")) {
            JsonObject r = e.getAsJsonObject();
            Pattern.Builder b = new Pattern.Builder().where(PatternLocation.HUB_INTERNAL).source(r.get("id").getAsString());
            boolean subst = false;
            for (JsonElement s : r.getAsJsonArray("in")) {
                JsonArray slot = s.getAsJsonArray();
                long count = slot.get(0).getAsLong();
                JsonArray opts = slot.get(1).getAsJsonArray();
                int[] alts = new int[opts.size() - 1];
                for (int i = 1; i < opts.size(); i++)
                    alts[i - 1] = opts.get(i).getAsInt();
                subst |= alts.length > 0;
                // format 2 marks an item that comes back unchanged (format 1 has no third element)
                boolean container = slot.size() > 2 && slot.get(2).getAsString().equals("container");
                b.input(opts.get(0).getAsInt(), Amount.of(count), container ? InputKind.CONTAINER : InputKind.CONSUME, 0, alts);
            }
            // format 3: a third element is the probability of an output that does not always come
            for (JsonElement o : r.getAsJsonArray("out")) {
                JsonArray a = o.getAsJsonArray();
                b.output(a.get(0).getAsInt(), a.get(1).getAsLong(), a.size() > 2 ? a.get(2).getAsDouble() : 1);
            }
            // items left behind that the recipe does not list (the bucket of a water bucket): byproducts
            if (r.has("rem"))
                for (JsonElement o : r.getAsJsonArray("rem"))
                    b.byproduct(o.getAsJsonArray().get(0).getAsInt(), o.getAsJsonArray().get(1).getAsLong());
            out.add(b.allowSubst(subst).build(out.size()));
        }
        return out;
    }

    /** {@code /twinstar dump_network}: the engine's own patterns (ids as in game) and the network's stock. */
    private List<Pattern> loadNetwork(JsonObject root) {
        List<Pattern> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("patterns")) {
            JsonObject p = e.getAsJsonObject();
            int id = p.get("id").getAsInt();
            JsonArray outs = p.getAsJsonArray("out");
            String first = outs.isEmpty() ? "?" : items.get(outs.get(0).getAsJsonArray().get(0).getAsInt());
            Pattern.Builder b = new Pattern.Builder()
                    .where(PatternLocation.valueOf(p.get("where").getAsString()))
                    .priority(p.get("priority").getAsInt())
                    .allowSubst(p.get("subst").getAsBoolean())
                    .source("樣板 #" + id + " → " + first);
            for (JsonElement s : p.getAsJsonArray("in")) {
                JsonArray slot = s.getAsJsonArray();
                JsonArray opts = slot.get(4).getAsJsonArray();
                int[] alts = new int[opts.size()];
                for (int i = 0; i < opts.size(); i++)
                    alts[i] = opts.get(i).getAsInt();
                b.input(slot.get(0).getAsInt(), Amount.of(new BigInteger(slot.get(1).getAsString())),
                        InputKind.valueOf(slot.get(2).getAsString()), slot.get(3).getAsInt(), alts);
            }
            for (JsonElement o : outs) {
                JsonArray a = o.getAsJsonArray();
                Amount n = Amount.of(new BigInteger(a.get(1).getAsString()));
                if (a.size() > 2 && a.get(2).getAsBoolean())
                    b.byproduct(a.get(0).getAsInt(), n);
                else
                    b.output(a.get(0).getAsInt(), n);
            }
            out.add(b.build(id));
        }
        AmountArray stock = new AmountArray(items.size());
        for (JsonElement e : root.getAsJsonArray("stock"))
            stock.set(e.getAsJsonArray().get(0).getAsInt(), Amount.of(new BigInteger(e.getAsJsonArray().get(1).getAsString())));
        BitSet infinite = new BitSet();
        for (JsonElement e : root.getAsJsonArray("infinite"))
            infinite.set(e.getAsInt());
        networkStock = new StockSnapshot(stock, infinite);
        return out;
    }

    private StockSnapshot stock(String scenario, BitSet raws, Random rnd) {
        if (scenario.equals("network"))
            return networkStock;
        AmountArray stock = new AmountArray(items.size());
        BitSet infinite = new BitSet();
        for (int i = 0; i < items.size(); i++) {
            if (scenario.equals("creative")) {
                if (raws.get(i)) {
                    stock.set(i, Amount.of(Integer.MAX_VALUE));
                    infinite.set(i);
                }
            } else if (raws.get(i) ? rnd.nextInt(10) < 8 : rnd.nextInt(10) < 2) {
                // most raws and a few intermediates, 1 … 10^7
                stock.set(i, Amount.of((long) Math.pow(10, rnd.nextDouble() * 7)));
            }
        }
        return new StockSnapshot(stock, infinite);
    }

    // ───────────────────────────── theory layer ─────────────────────────────

    private Snapshot theoryBench(TheoryLayer theory, List<Pattern> patterns, StockSnapshot stock, Random rnd) {
        long t0 = System.nanoTime();
        for (Pattern p : patterns)
            theory.addPattern(p);
        theory.applyStock(stock);
        Snapshot snap = theory.flush();
        long build = System.nanoTime() - t0;

        int craftable = 0, unmakeable = 0, maxLevel = 0;
        for (int x = 0; x < snap.itemCount(); x++) {
            if (snap.isRaw(x))
                continue;
            if (snap.logCost(x) == LogMath.INF)
                unmakeable++;
            else
                craftable++;
            maxLevel = Math.max(maxLevel, snap.level(x));
        }
        line("理論層全部建置 %s；可合成 %d、做不出來 %d、最深 %d 層、策略環 %d 個",
                ms(build), craftable, unmakeable, maxLevel, snap.sccCount());
        if (!listedImpossible && Boolean.getBoolean("listImpossible")) {
            listedImpossible = true;
            for (int x = 0; x < snap.itemCount(); x++)
                if (!snap.isRaw(x) && snap.logCost(x) == LogMath.INF)
                    line("  做不出來 %s：%s", items.get(x), why(snap, x));
        }
        for (int s = 0; s < (listedLoops ? 0 : Math.min(snap.sccCount(), 10)); s++) {
            var scc = snap.sccAt(s);
            List<String> names = new ArrayList<>();
            for (int i = 0; i < scc.memberCount(); i++)
                names.add(items.get(scc.member(i)));
            line("  策略環 #%d（%s）：%s", s, scc.kind, String.join(" ⇄ ", names));
        }
        if (!listedLoops && snap.sccCount() > 10)
            line("  …另有 %d 個", snap.sccCount() - 10);
        listedLoops = true;

        // incremental: stock changes
        List<Long> times = new ArrayList<>();
        int[] mismatches = new int[2];      // cost differs / only the route differs (same cost: a tie)
        int rebuilds = 0;
        List<Long> incOnly = new ArrayList<>();
        for (int round = 0; round < 20; round++) {
            for (int k = 0; k < 50; k++) {
                int item = rnd.nextInt(items.size());
                long v = rnd.nextInt(4) == 0 ? 0 : (long) Math.pow(10, rnd.nextDouble() * 7);
                theory.onStockChanged(item, Amount.of(v), stock.isInfinite(item) && v > 0);
            }
            long t = System.nanoTime();
            Snapshot inc = safe("增量更新（庫存）", theory::flush);
            times.add(System.nanoTime() - t);
            if (theory.lastFlushStats().rebuilt())
                rebuilds++;
            else
                incOnly.add(System.nanoTime() - t);
            if (inc != null && round % 5 == 4) {
                int[] d = compare(inc, theory);
                mismatches[0] += d[0];
                mismatches[1] += d[1];
            }
        }
        line("增量更新（每次 50 個庫存變動）：%s；其中全部重建 %d 次%s；與重建相比成本不同 %d 個、成本相同但路線不同（同分）%d 個",
                stats(times), rebuilds, incOnly.isEmpty() ? "" : "，未重建的 " + stats(incOnly), mismatches[0], mismatches[1]);

        // incremental: patterns removed and added back
        times.clear();
        for (int round = 0; round < 10; round++) {
            List<Pattern> picked = new ArrayList<>();
            for (int k = 0; k < 20; k++)
                picked.add(patterns.get(rnd.nextInt(patterns.size())));
            long t = System.nanoTime();
            for (Pattern p : picked)
                theory.removePattern(p.id);
            safe("增量更新（移除樣板）", theory::flush);
            for (Pattern p : picked)
                theory.addPattern(p);
            safe("增量更新（加回樣板）", theory::flush);
            times.add(System.nanoTime() - t);
        }
        line("移除 20 張樣板再加回（兩次更新合計）：%s", stats(times));

        theory.applyStock(stock);
        return theory.rebuild();
    }

    /** Items whose cost differs from a fresh rebuild, and items with the same cost on another route (a tie). */
    private int[] compare(Snapshot inc, TheoryLayer theory) {
        Snapshot full = theory.rebuild();
        int[] diff = new int[2];
        for (int x = 0; x < Math.min(inc.itemCount(), full.itemCount()); x++) {
            if (!LogMath.sameWithin(inc.logCost(x), full.logCost(x), 1e-6)) {
                if (diff[0] < 3)
                    line("  成本不同：%s 增量 %.6f／重建 %.6f", items.get(x), inc.logCost(x), full.logCost(x));
                diff[0]++;
            } else if (inc.best(x) != full.best(x))
                diff[1]++;
        }
        return diff;
    }

    /**
     * -Dcold=item[,item…]: the first plans in this JVM (the planner not compiled yet) against the
     * same plans again, to tell a cold JIT from the work itself.
     */
    private void coldOrders(Snapshot snap, StockSnapshot stock) {
        String which = System.getProperty("cold", "");
        if (which.isBlank())
            return;
        for (int round = 1; round <= 3; round++)
            for (String name : which.split(",")) {
                int x = items.indexOf(name.trim());
                if (x < 0) {
                    line("冷啟動：找不到 %s", name);
                    continue;
                }
                long t = System.nanoTime();
                OrderResult r = Planner.order(snap, stock, x, Amount.of(64), OrderMode.EXACT, UNLIMITED, new Overlay(), Deadline.NONE);
                line("冷啟動第 %d 輪：%s ×64 %s，%s", round, name, r.getClass().getSimpleName(), ms(System.nanoTime() - t));
            }
    }

    // ───────────────────────────── orders ─────────────────────────────

    private record Run(int item, int amount, long nanos, String outcome) {
    }

    private void orderBench(Snapshot snap, StockSnapshot stock, Random rnd, int orders) {
        List<Integer> targets = new ArrayList<>();
        for (int x = 0; x < snap.itemCount(); x++)
            if (!snap.isRaw(x) && snap.logCost(x) != LogMath.INF)
                targets.add(x);
        if (targets.isEmpty()) {
            line("沒有可以合成的物品");
            return;
        }
        List<Run> runs = new ArrayList<>();
        for (int i = 0; i < orders; i++) {
            int item = targets.get(rnd.nextInt(targets.size()));
            int a = rnd.nextInt(AMOUNTS.length);
            long t = System.nanoTime();
            String outcome;
            try {
                OrderResult r = Planner.order(snap, stock, item, AMOUNTS[a], OrderMode.EXACT, UNLIMITED,
                        new Overlay(), Deadline.startingNow());
                outcome = switch (r) {
                    case OrderResult.Accepted acc -> acc.plan().isComplete() ? "完成" : "缺料";
                    case OrderResult.Rejected rej -> "超過容量";
                    case OrderResult.TimedOut to -> "逾時";
                };
            } catch (Throwable e) {
                outcome = "例外";
                errors.add("下單 " + items.get(item) + " ×" + AMOUNT_LABELS[a] + "：" + trace(e));
            }
            runs.add(new Run(item, a, System.nanoTime() - t, outcome));
        }

        Map<String, Integer> outcomes = new LinkedHashMap<>();
        for (Run r : runs)
            outcomes.merge(r.outcome, 1, Integer::sum);
        line("隨機下單 %d 次（照數量）：%s", runs.size(), outcomes);
        for (int a = 0; a < AMOUNTS.length; a++) {
            List<Long> t = new ArrayList<>();
            for (Run r : runs)
                if (r.amount == a)
                    t.add(r.nanos);
            if (!t.isEmpty())
                line("  ×%-8s %s", AMOUNT_LABELS[a], stats(t));
        }
        line("  最慢的 10 次：");
        runs.stream().sorted(Comparator.comparingLong(Run::nanos).reversed()).limit(10).forEach(r ->
                line("    %s  %s ×%s（%s）", ms(r.nanos), items.get(r.item), AMOUNT_LABELS[r.amount], r.outcome));
        // every shortage when all raws are infinite (they should be rare and explainable); one example otherwise
        boolean creative = stock.isInfinite(firstInfinite(stock));
        int shown = 0;
        for (Run r : runs)
            if (r.outcome.equals("缺料") && shown < (creative ? 20 : 1)) {
                Plan sample = Planner.plan(snap, stock, r.item, AMOUNTS[r.amount], new Overlay(), Deadline.NONE);
                line("  缺料%s：%s ×%s 缺 %s", creative ? "" : "範例", items.get(r.item), AMOUNT_LABELS[r.amount], names(sample));
                shown++;
            }
    }

    /** -Dtrace=minecraft:iron_block[,…]: theory data and the plan for one of each, to see why. */
    private void trace(Snapshot snap, StockSnapshot stock, String which) {
        for (String name : which.split(",")) {
            int x = items.indexOf(name.trim());
            if (x < 0) {
                line("追蹤：找不到 %s", name);
                continue;
            }
            line("追蹤 %s：原料 %s、無限 %s、庫存 %s、成本 1e%.2f、最佳 P%d、層 %d、策略環 %d",
                    name, snap.isRaw(x), stock.isInfinite(x), stock.stock(x), snap.logCost(x), snap.best(x), snap.level(x), snap.scc(x));
            for (int p : snap.producers(x))
                line("  路線 P%d %s", p, describe(snap.pattern(p)));
            Plan plan = Planner.plan(snap, stock, x, Amount.ONE, new Overlay(), Deadline.NONE);
            line("  ×1 的計畫：樣板 %s", plan.patternTimes);
            line("    取用 %s", plan.pulled);
            line("    缺 %s", names(plan));
        }
    }

    private String describe(Pattern p) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < p.inputCount(); i++)
            sb.append(i > 0 ? " + " : "").append(p.inputAmount(i)).append('×').append(items.get(p.inputItem(i)));
        sb.append(" → ");
        for (int i = 0; i < p.outputCount(); i++)
            sb.append(i > 0 ? " + " : "").append(p.outputAmount(i)).append('×').append(items.get(p.outputItem(i)));
        return sb.append("] ").append(p.source()).toString();
    }

    private int firstInfinite(StockSnapshot stock) {
        for (int i = 0; i < items.size(); i++)
            if (stock.isInfinite(i))
                return i;
        return 0;
    }

    private String names(Plan p) {
        List<String> out = new ArrayList<>();
        p.missing.forEach((id, a) -> {
            if (out.size() < 5)
                out.add(items.get(id) + " ×" + a);
        });
        return String.join("、", out) + (p.missing.size() > 5 ? " …" : "");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private <T> T safe(String what, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (Throwable e) {
            errors.add(what + "：" + trace(e));
            return null;
        }
    }

    private static String trace(Throwable e) {
        StringWriter w = new StringWriter();
        e.printStackTrace(new PrintWriter(w));
        String[] lines = w.toString().split("\\R");
        return String.join("\n      ", java.util.Arrays.copyOf(lines, Math.min(lines.length, 8)));
    }

    private static String stats(List<Long> nanos) {
        List<Long> s = new ArrayList<>(nanos);
        s.sort(null);
        return String.format(Locale.ROOT, "中位數 %s、p90 %s、最大 %s（%d 次）",
                ms(s.get(s.size() / 2)), ms(s.get((int) (s.size() * 0.9))), ms(s.get(s.size() - 1)), s.size());
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }

    private void line(String format, Object... args) {
        report.append(args.length == 0 ? format : String.format(Locale.ROOT, format, args)).append('\n');
    }

    private void line(String text) {
        report.append(text).append('\n');
    }
}
