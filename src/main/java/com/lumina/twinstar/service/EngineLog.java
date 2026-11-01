package com.lumina.twinstar.service;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.Tarjan;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.execution.Job;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.LogMath;
import com.lumina.twinstar.algorithm.model.OrderMode;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.planning.Plan;
import com.lumina.twinstar.util.BigAmountFormat;
import net.neoforged.fml.loading.FMLEnvironment;

import java.text.NumberFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Engine trace in the console: pattern sync, the theory tree after each update, the crafting tree
 * of every plan, jobs starting and ending. On in the dev environment; elsewhere with
 * {@code -Dtwinstar.debug=true} (off with {@code =false}) or in game with {@code /twinstar debug on}.
 */
public final class EngineLog {
    private static volatile boolean enabled = Boolean.parseBoolean(
            System.getProperty("twinstar.debug", String.valueOf(!FMLEnvironment.production)));

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean on) {
        enabled = on;
    }
    /** longer trees are cut here */
    private static final int MAX_LINES = 300;

    private EngineLog() {
    }

    // ───────────────────────────── events ─────────────────────────────

    static void patternsSynced(List<Pattern> added, List<Integer> removed, int total, ItemIndex index) {
        if (!enabled)
            return;
        Lines out = new Lines();
        out.add("[雙星] 樣板同步：新增 " + added.size() + "、移除 " + removed.size() + "，網路上共 " + total + " 張 AE2 樣板");
        for (Pattern p : added)
            out.add("  + " + pattern(p, index));
        if (!removed.isEmpty())
            out.add("  - " + removed.stream().map(id -> "P" + id).toList());
        out.print();
    }

    static void theoryFlushed(TheoryLayer.FlushStats st, Snapshot snap, ItemIndex index, String why) {
        if (!enabled || (!st.rebuilt() && st.processed() == 0))
            return;
        Lines out = new Lines();
        String what = st.rebuilt() ? "全部重建" : "增量更新 " + st.processed() + " 個元件";
        int patterns = 0;
        for (int p = 0; p < snap.patternCount(); p++)
            if (snap.pattern(p) != null)
                patterns++;
        out.add(String.format("[雙星] 理論層 v%d（%s）：%s，%.2f ms；物品 %d、樣板 %d、策略環 %d",
                st.version(), why, what, st.nanos() / 1e6, snap.itemCount(), patterns, snap.sccCount()));
        if (st.rebuilt())
            theoryTree(snap, index, out);
        out.print();
    }

    static void planned(Plan plan, Snapshot snap, ItemIndex index, OrderMode mode, String outcome, long nanos) {
        if (!enabled)
            return;
        Lines out = new Lines();
        out.add(String.format("[雙星] 規劃 %s ×%s（%s）：%s，%.2f ms；理論層 v%d，容量用量 %s",
                item(index, plan.target), amount(plan.amount), mode == OrderMode.BEST_EFFORT ? "能做多少做多少" : "照數量",
                outcome, nanos / 1e6, plan.snapshotVersion, amount(plan.usage)));
        planTree(plan, snap, index, out);
        if (!plan.missing.isEmpty()) {
            List<String> miss = new ArrayList<>();
            plan.missing.forEach((id, a) -> miss.add(item(index, id) + " ×" + amount(a)));
            out.add("  缺料：" + String.join("、", miss));
        }
        for (var r : plan.recycles)
            out.add("  循環使用：" + item(index, r.item()) + " 只準備種子 ×" + amount(r.seed()) + "，其餘靠樣板 "
                    + java.util.Arrays.toString(r.patterns()) + " 吐回後重複使用");
        if (!plan.leftover.isEmpty()) {
            List<String> left = new ArrayList<>();
            plan.leftover.forEach((id, a) -> left.add(item(index, id) + " ×" + amount(a)));
            out.add("  結束後歸還：" + String.join("、", left));
        }
        out.print();
    }

    static void jobStarted(Job job, ItemIndex index) {
        if (enabled)
            Twinstar.LOGGER.info("[雙星] 工作 #{} 開始：{} ×{}，{} 種樣板，佔用容量 {}", job.id, item(index, job.plan.target),
                    amount(job.plan.amount), job.plan.patternTimes.size(), amount(job.plan.usage));
    }

    static void jobEnded(Job job, ItemIndex index) {
        if (enabled)
            Twinstar.LOGGER.info("[雙星] 工作 #{} {}：{} 交出 {}，用時 {} 秒", job.id, job.isCancelled() ? "已取消" : "完成",
                    item(index, job.plan.target), amount(job.delivered()),
                    String.format("%.1f", (System.nanoTime() - job.startNanos()) / 1e9));
    }

    // ───────────────────────────── trees ─────────────────────────────

    /** Every craftable item by level: its best pattern, theory cost, policy cycle. */
    private static void theoryTree(Snapshot snap, ItemIndex index, Lines out) {
        TreeMap<Integer, List<Integer>> byLevel = new TreeMap<>();
        int raws = 0;
        for (int x = 0; x < snap.itemCount(); x++) {
            if (snap.isRaw(x)) {
                raws++;
                continue;
            }
            byLevel.computeIfAbsent(snap.level(x), k -> new ArrayList<>()).add(x);
        }
        out.add("  原料（沒有樣板做得出來）：" + raws + " 種");
        patternLoops(snap, index, out);
        for (Map.Entry<Integer, List<Integer>> e : byLevel.entrySet())
            for (int x : e.getValue()) {
                int best = snap.best(x);
                String loop = snap.scc(x) >= 0 ? "，策略環 #" + snap.scc(x) : "";
                String others = snap.producers(x).length > 1 ? "，另有 " + (snap.producers(x).length - 1) + " 條路線" : "";
                String route = best >= 0 ? "最佳 P" + best : snap.isMined(x) ? "挖礦取得（依庫存定價，配方只當轉換）" : "無";
                out.add(String.format("  層 %d  %s  成本 %s  %s%s%s", e.getKey(), item(index, x),
                        cost(snap.logCost(x)), route, loop, others));
            }
    }

    /**
     * Every loop in the pattern graph (input → output over all patterns), not only the policy cycles:
     * a loop becomes a policy cycle only when its patterns are the cheapest routes of its items.
     */
    private static void patternLoops(Snapshot snap, ItemIndex index, Lines out) {
        int items = snap.itemCount();
        List<Set<Integer>> next = new ArrayList<>(items);
        for (int x = 0; x < items; x++)
            next.add(new HashSet<>());
        for (int x = 0; x < items; x++)
            for (int p : snap.producers(x)) {
                Pattern pat = snap.pattern(p);
                if (pat != null)
                    for (int s = 0; s < pat.inputCount(); s++)
                        if (pat.inputItem(s) < items)
                            next.get(pat.inputItem(s)).add(x);
            }
        int[] all = new int[items];
        for (int x = 0; x < items; x++)
            all[x] = x;
        List<int[]> loops = new ArrayList<>();
        for (int[] comp : Tarjan.components(all, x -> next.get(x).stream().mapToInt(Integer::intValue).toArray()))
            if (comp.length > 1 || next.get(comp[0]).contains(comp[0]))
                loops.add(comp);
        out.add("  樣板圖中的循環：" + loops.size() + " 個（被選為最佳路線的才算策略環：" + snap.sccCount() + " 個）");
        for (int[] comp : loops) {
            List<String> names = new ArrayList<>();
            boolean policy = false;
            for (int x : comp) {
                names.add(item(index, x));
                policy |= snap.scc(x) >= 0;
            }
            out.add("    " + (policy ? "策略環 #" + snap.scc(comp[0]) : "未採用（另有更便宜的路線，或缺少外部輸入）")
                    + "：" + String.join(" ⇄ ", names));
        }
    }

    /** The order as a tree: each item with the pattern(s) and runs the plan chose; shared items are listed once. */
    private static void planTree(Plan plan, Snapshot snap, ItemIndex index, Lines out) {
        Map<Integer, List<Integer>> producedBy = new TreeMap<>();
        plan.patternTimes.forEach((p, runs) -> {
            Pattern pat = snap.pattern(p);
            if (pat != null)
                for (int o = 0; o < pat.outputCount(); o++)
                    producedBy.computeIfAbsent(pat.outputItem(o), k -> new ArrayList<>()).add(p);
        });
        // depth first, with a stack of its own: a chain thousands of levels deep must not overflow the
        // engine thread's stack, and nothing past the line limit is built at all
        Set<Integer> listed = new HashSet<>();
        ArrayDeque<TreeStep> todo = new ArrayDeque<>();
        todo.push(new TreeStep(plan.target, -1, 1));
        while (!todo.isEmpty()) {
            if (out.full()) {
                out.cut();
                return;
            }
            TreeStep step = todo.pop();
            int x = step.item();
            String pad = "  ".repeat(step.depth());
            String from = sources(plan, index, x);
            if (step.pattern() >= 0) {
                // one of x's patterns: its line, then its inputs (in order) one level down
                int p = step.pattern();
                Pattern pat = snap.pattern(p);
                out.add(pad + item(index, x) + " ← " + "P" + p + " ×" + amount(plan.patternTimes.get(p)) + " 次 "
                        + pattern(pat, index) + (from.isEmpty() ? "" : "；另 " + from));
                for (int s = pat.inputCount() - 1; s >= 0; s--)
                    todo.push(new TreeStep(pat.inputItem(s), -1, step.depth() + 1));
                continue;
            }
            List<Integer> ps = producedBy.getOrDefault(x, List.of());
            if (ps.isEmpty()) {
                out.add(pad + item(index, x) + " ← " + (from.isEmpty() ? "（無來源）" : from));
                continue;
            }
            if (!listed.add(x)) {
                out.add(pad + item(index, x) + " ← （共用，已列在上方）");
                continue;
            }
            for (int k = ps.size() - 1; k >= 0; k--)
                todo.push(new TreeStep(x, ps.get(k), step.depth()));
        }
    }

    /** An item to list, or (pattern ≥ 0) one of its patterns with the inputs below it. */
    private record TreeStep(int item, int pattern, int depth) {
    }

    private static String sources(Plan plan, ItemIndex index, int x) {
        List<String> parts = new ArrayList<>();
        Amount pulled = plan.pulled.get(x);
        if (pulled.signum() > 0)
            parts.add((plan.drawLater.contains(x) ? "無限來源 " : "庫存 ") + amount(pulled));
        Amount missing = plan.missing.get(x);
        if (missing.signum() > 0)
            parts.add("缺 " + amount(missing));
        return String.join("，", parts);
    }

    // ───────────────────────────── text ─────────────────────────────

    static String pattern(Pattern p, ItemIndex index) {
        StringBuilder sb = new StringBuilder("P").append(p.id).append(" [");
        for (int i = 0; i < p.inputCount(); i++) {
            if (i > 0)
                sb.append(" + ");
            sb.append(amount(p.inputAmount(i))).append('×').append(item(index, p.inputItem(i)));
            if (p.inputKind(i) != InputKind.CONSUME)
                sb.append(switch (p.inputKind(i)) {
                    case CATALYST -> "（觸媒）";
                    case CONTAINER -> "（容器）";
                    case TOOL -> "（工具，耐久 " + p.inputDurability(i) + "）";
                    default -> "";
                });
            if (p.allowSubst && p.alternativeCount(i) > 0)
                sb.append("（可替換 ").append(p.alternativeCount(i)).append(" 種）");
        }
        sb.append(" → ");
        for (int i = 0; i < p.outputCount(); i++) {
            if (i > 0)
                sb.append(" + ");
            sb.append(amount(p.outputAmount(i))).append('×').append(item(index, p.outputItem(i)));
            if (p.isByproduct(i))
                sb.append("（副產物）");
            if (p.isChance(i))
                sb.append(String.format(java.util.Locale.ROOT, "（機率 %.4g%%）", p.outputChance(i) * 100));
        }
        sb.append("] ").append(switch (p.where) {
            case HUB_INTERNAL -> "中樞內部";
            case HUB_MACHINE -> "機器接入接口";
            case EXTERNAL_CRAFT -> "外部合成";
            case EXTERNAL_PROCESS -> "外部處理";
        });
        if (p.priority != 0)
            sb.append(" 優先權 ").append(p.priority);
        return sb.toString();
    }

    static String item(ItemIndex index, int id) {
        AEKey key = index.keyOf(id);
        if (key == null)
            return "#" + id;
        try {
            return key.getDisplayName().getString();
        } catch (RuntimeException e) {
            return key.getId().toString();
        }
    }

    static String amount(Amount a) {
        return BigAmountFormat.fitsLong(a.bigValue()) ? NumberFormat.getInstance().format(a.longValue())
                : BigAmountFormat.unitForm(a.bigValue());
    }

    private static String cost(double logCost) {
        if (logCost == LogMath.INF)
            return "∞（做不出來）";
        if (logCost == LogMath.ZERO)
            return "0";
        if (Math.abs(logCost) < 300)
            return String.format("%.3g", Math.pow(10, logCost));
        return String.format("1e%.1f", logCost);
    }

    /** Collects lines and prints them as one log entry (cut at MAX_LINES). */
    private static final class Lines {
        private final List<String> lines = new ArrayList<>();
        private int dropped;

        private boolean cut;

        void add(String line) {
            if (lines.size() < MAX_LINES)
                lines.add(line);
            else
                dropped++;
        }

        boolean full() {
            return lines.size() >= MAX_LINES;
        }

        /** The rest was not even looked at (too much to list). */
        void cut() {
            cut = true;
        }

        void print() {
            if (cut)
                lines.add("  …（以下省略）");
            else if (dropped > 0)
                lines.add("  …（省略 " + dropped + " 行）");
            Twinstar.LOGGER.info(String.join("\n", lines));
        }
    }
}
