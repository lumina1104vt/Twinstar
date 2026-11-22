package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.metrics.Fmt;
import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.metrics.Rating;
import com.lumina.twinstar.metrics.Ratings;
import com.lumina.twinstar.metrics.TheoryReport;
import com.lumina.twinstar.metrics.Verdict;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The texts of the memory and speed panel: one row per number, each with a tooltip that says where
 * the number comes from and, when the row is rated, what it was checked against.
 */
public final class PerfContent {
    private static final int DIM = 0x9A94B8;

    private PerfContent() {
    }

    /**
     * One row of the panel.
     *
     * @param fraction 0..1 for a bar under the row, −1 for none
     * @param rating   the worst of the row's checks, null when the row is not rated
     */
    public record Row(Component label, String value, float fraction, @Nullable Rating rating, List<Component> tooltip) {
    }

    public record Content(Component title, Rating rating, List<Row> rows, List<Component> headerTooltip) {
    }

    // ── theory layer ──

    public static Content theory(TheoryReport r) {
        Verdict v = Ratings.theory(r);
        List<Row> rows = new ArrayList<>();
        long total = r.totalBytes();
        if (r.memoryReady()) {
            rows.add(part("patterns", r.patternBytes(), total, v));
            rows.add(part("items", r.itemBytes(), total, v));
            rows.add(part("bom", r.bomBytes(), total, v));
            rows.add(part("other", r.otherBytes(), total, v));
            rows.add(row("total", Fmt.bytes(total), -1, v, "size"));
            rows.add(row("per_item", Fmt.bytes(r.bytesPerItem()), -1, v));
            rows.add(row("per_pattern", Fmt.bytes(r.bytesPerPattern()), -1, v, "density"));
        } else {
            rows.add(row("measuring", "…", -1, v));
        }
        rows.add(row("update", Fmt.nanos(r.lastFlushNanos()) + (r.lastRebuilt() ? " ⟳" : ""), -1, v, "rebuild", "incremental",
                r.flushes(), r.rebuilds(), Fmt.nanos(r.lastRebuildNanos()), Fmt.nanos(r.incrMaxNanos())));
        if (r.lastFlushAlloc() >= 0 && r.memoryReady() && total > 0)
            rows.add(row("churn", Fmt.bytes(r.lastFlushAlloc()) + " " + Fmt.times((double) r.lastFlushAlloc() / total), -1, v, "churn"));
        rows.add(row("plan", r.plans() == 0 ? "—" : Fmt.nanos(r.maxPlanNanos()), -1, v, "plan_time", "plan_soft", "plan_hard",
                r.plans(), r.softHits(), r.hardHits(), Fmt.nanos(r.lastPlanNanos())));
        rows.add(row("growth", r.growthPermille() < 0 ? "—" : Fmt.times(r.growthPermille() / 1000.0), -1, v, "growth"));
        return new Content(Component.translatable("gui.twinstar.perf.title.theory"), v.rating(), rows, header(v, "theory",
                r.items(), r.patterns()));
    }

    // ── hub ──

    /** @param idleEntry the CPU entry is the hub's idle one (no job of its own) */
    public static Content hub(HubReport r, boolean idleEntry) {
        Verdict v = Ratings.hub(r);
        List<Row> rows = new ArrayList<>();
        if (!idleEntry)
            rows.add(row("job", Fmt.bytes(r.jobBytes()), -1, v, r.jobItems(), r.jobTasks()));
        rows.add(row("all", Fmt.bytes(r.totalBytes()), -1, v, "size", r.jobs(), r.trackedItems()));
        rows.add(row("pool", Fmt.bytes(r.poolBytes()), r.totalBytes() > 0 ? (float) r.poolBytes() / r.totalBytes() : -1, v));
        if (r.trackedItems() > 0)
            rows.add(row("per_kind", Fmt.bytes(r.bytesPerItem()), -1, v, "density", r.trackedItems()));
        rows.add(row("residual", r.idleResidualBytes() < 0 ? "—" : Fmt.bytes(r.idleResidualBytes()), -1, v, "residual"));
        rows.add(row("tick", r.tickAvgNanos() == 0 ? "—" : Fmt.nanos(r.tickAvgNanos()) + " / " + Fmt.nanos(r.tickMaxNanos()), -1, v,
                "tick_avg", "tick_max"));
        rows.add(row("alloc", r.allocPerSecond() < 0 || r.tickAvgNanos() == 0 ? "—" : Fmt.bytes(r.allocPerSecond()) + "/s", -1, v, "alloc"));
        if (r.oldSnapshots() > 0)
            rows.add(row("old", String.valueOf(r.oldSnapshots()), -1, v, "old_snapshots", r.oldSnapshots(), Fmt.bytes(r.oldSnapshotBytes())));
        return new Content(Component.translatable("gui.twinstar.perf.title.hub"), v.rating(), rows, header(v, "hub", r.jobs(), r.trackedItems()));
    }

    // ── pieces ──

    private static Row part(String key, long bytes, long total, Verdict v) {
        return row("part_" + key, Fmt.bytes(bytes) + "  " + Fmt.percent(bytes, total), total > 0 ? (float) bytes / total : 0, v);
    }

    private static Row row(String key, String value, float fraction, Verdict v, Object... args) {
        String[] checks = new String[0];
        int n = 0;
        while (n < args.length && args[n] instanceof String s && isCheck(s))
            n++;
        checks = new String[n];
        for (int i = 0; i < n; i++)
            checks[i] = (String) args[i];
        Object[] rest = java.util.Arrays.copyOfRange(args, n, args.length);
        return makeRow(key, value, fraction, v, checks, rest);
    }

    private static boolean isCheck(String s) {
        return switch (s) {
            case "size", "density", "growth", "rebuild", "incremental", "churn", "plan_time", "plan_soft", "plan_hard",
                 "residual", "tick_avg", "tick_max", "alloc", "old_snapshots" -> true;
            default -> false;
        };
    }

    /** The domain the row is in decides its text keys: theory rows and hub rows do not share names. */
    private static Row makeRow(String key, String value, float fraction, Verdict v, String[] checks, Object[] rest) {
        String domain = isHubRow(key) ? "hub" : "theory";
        List<Component> tip = new ArrayList<>();
        tip.add(Component.translatable("gui.twinstar.perf." + domain + "." + key).withStyle(s -> s.withBold(true)));
        tip.add(Component.translatable("gui.twinstar.perf." + domain + "." + key + ".tip", rest).withColor(DIM));
        Rating worst = null;
        for (String c : checks)
            for (Verdict.Reason reason : v.reasons())
                if (reason.key().equals(c)) {
                    worst = worst == null ? reason.rating() : worst.worst(reason.rating());
                    tip.add(checkLine(reason));
                }
        return new Row(Component.translatable("gui.twinstar.perf." + domain + "." + key), value, fraction, worst, tip);
    }

    private static boolean isHubRow(String key) {
        return switch (key) {
            case "job", "all", "pool", "per_kind", "residual", "tick", "alloc", "old" -> true;
            default -> false;
        };
    }

    /** "▲ Size 80 MB (high from 64 MB, very high from 256 MB)": the shape and the word say the level as well as the colour. */
    public static Component checkLine(Verdict.Reason r) {
        int color = PerfIcons.color(r.rating());
        Component text = Component.translatable("gui.twinstar.perf.check." + r.key(), value(r.key(), r.value()))
                .append(Component.literal("  "))
                .append(Component.translatable("gui.twinstar.perf.limits", value(r.key(), r.high()), value(r.key(), r.very())).withColor(DIM));
        return Component.literal(PerfIcons.symbol(r.rating()) + " ").withColor(color)
                .append(Component.translatable("gui.twinstar.perf.rating." + ratingKey(r.rating())).append(Component.literal(": ")).withColor(color))
                .append(text);
    }

    public static String ratingKey(Rating r) {
        return switch (r) {
            case NORMAL -> "normal";
            case HIGH -> "high";
            case VERY_HIGH -> "very_high";
        };
    }

    private static String value(String check, double v) {
        return switch (check) {
            case "size", "density", "residual", "old_snapshots" -> Fmt.bytes((long) v);
            case "rebuild", "incremental", "plan_time", "tick_avg", "tick_max" -> Fmt.nanos((long) v);
            case "alloc" -> Fmt.bytes((long) v) + "/s";
            case "churn" -> Fmt.times(v);
            case "growth" -> Fmt.times(v / 1000);
            default -> String.valueOf((long) v);
        };
    }

    private static List<Component> header(Verdict v, String domain, Object a, Object b) {
        List<Component> tip = new ArrayList<>();
        tip.add(Component.translatable("gui.twinstar.perf.header." + domain, a, b).withStyle(s -> s.withBold(true)));
        tip.add(Component.translatable("gui.twinstar.perf.header.how").withColor(DIM));
        for (Verdict.Reason r : v.reasons())
            tip.add(checkLine(r));
        return tip;
    }
}
