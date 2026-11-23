package com.lumina.twinstar.client;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the usage screen lists, apart from drawing it and apart from the game: the machines filtered by the search text, sorted,
 * and (when grouped by interface) put under their interface. It knows a machine and a row (one material's amounts) only through
 * {@link Source}, so it needs no game classes ({@code UsageOrderTest}).
 *
 * @param <M> a machine
 * @param <R> one material of a machine
 */
public final class UsageOrder<M, R> {
    public enum Sort {
        /** what the shown window used in all, the biggest first */
        AMOUNT,
        /** how fast it goes right now: the last minute */
        RATE,
        NAME
    }

    /** What the order needs to know. */
    public interface Source<M, R> {
        /** tells one machine as one interface stands for it from another */
        String id(M m);

        String name(M m);

        String ifaceId(M m);

        String ifaceName(M m);

        /** the machine place as text, searched too */
        String place(M m);

        List<R> rows(M m);

        /** the amounts of a row in each window */
        long[] totals(R r);

        String itemName(R r);

        /** how many of its amounts make one unit (1 for an item, 1000 for a fluid in buckets) */
        double unit(R r);
    }

    /**
     * One machine in the list.
     *
     * @param rows    what is drawn in the list: the materials the search leaves (the ones it names, or all when it names the
     *                machine), the biggest of the chosen window first
     * @param allRows every material the machine used, the biggest of the chosen window first
     */
    public record Entry<M, R>(M machine, List<R> rows, List<R> allRows, double amount, double rate, String sortName) {
    }

    /** A line of the list: a machine, or an interface heading (machine null, {@code first} one of its machines). */
    public record Line<M, R>(@Nullable Entry<M, R> machine, M first, int count, boolean indent) {
    }

    private final List<Line<M, R>> lines;
    private final Map<String, Entry<M, R>> byId;

    private UsageOrder(List<Line<M, R>> lines, Map<String, Entry<M, R>> byId) {
        this.lines = lines;
        this.byId = byId;
    }

    public List<Line<M, R>> lines() {
        return lines;
    }

    public @Nullable Entry<M, R> find(String machineId) {
        return byId.get(machineId);
    }

    /**
     * @param window    which of the windows (the index the totals come in)
     * @param ascending the smallest (or A to Z) first
     */
    public static <M, R> UsageOrder<M, R> build(List<M> machines, Source<M, R> src, int window, boolean byInterface, Sort sort,
                                                boolean ascending, String search) {
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<Entry<M, R>> entries = new ArrayList<>();
        Map<String, Entry<M, R>> byId = new LinkedHashMap<>();
        for (M m : machines) {
            String name = src.name(m);
            String iface = src.ifaceName(m);
            boolean nameHit = q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q) || iface.toLowerCase(Locale.ROOT).contains(q)
                    || src.place(m).toLowerCase(Locale.ROOT).contains(q);
            List<R> all = new ArrayList<>(src.rows(m));
            all.sort(Comparator.comparingLong((R r) -> src.totals(r)[window]).reversed());
            List<R> shown = new ArrayList<>();
            for (R r : all)
                if (nameHit || src.itemName(r).toLowerCase(Locale.ROOT).contains(q))
                    shown.add(r);
            if (!nameHit && shown.isEmpty())
                continue;
            double amount = 0, rate = 0;
            for (R r : shown) {
                double unit = Math.max(1, src.unit(r));
                amount += src.totals(r)[window] / unit;
                rate += src.totals(r)[0] / unit;
            }
            Entry<M, R> e = new Entry<>(m, shown, all, amount, rate, name.toLowerCase(Locale.ROOT));
            entries.add(e);
            byId.put(src.id(m), e);
        }
        Comparator<Entry<M, R>> up = switch (sort) {
            case AMOUNT -> Comparator.<Entry<M, R>>comparingDouble(Entry::amount).thenComparing(Entry::sortName);
            case RATE -> Comparator.<Entry<M, R>>comparingDouble(Entry::rate).thenComparing(Entry::sortName);
            case NAME -> Comparator.<Entry<M, R>, String>comparing(Entry::sortName).thenComparing(e -> src.place(e.machine()));
        };
        entries.sort(ascending ? up : up.reversed());
        List<Line<M, R>> lines = new ArrayList<>();
        if (!byInterface) {
            for (Entry<M, R> e : entries)
                lines.add(new Line<>(e, e.machine(), 1, false));
            return new UsageOrder<>(lines, byId);
        }
        // grouped: the interfaces in the order of their first machine (by name: their own name)
        Map<String, List<Entry<M, R>>> groups = new LinkedHashMap<>();
        for (Entry<M, R> e : entries)
            groups.computeIfAbsent(src.ifaceId(e.machine()), k -> new ArrayList<>()).add(e);
        List<List<Entry<M, R>>> ordered = new ArrayList<>(groups.values());
        if (sort == Sort.NAME) {
            Comparator<List<Entry<M, R>>> byTitle = Comparator.comparing(g -> src.ifaceName(g.get(0).machine()).toLowerCase(Locale.ROOT));
            ordered.sort(ascending ? byTitle : byTitle.reversed());
        }
        for (List<Entry<M, R>> g : ordered) {
            lines.add(new Line<>(null, g.get(0).machine(), g.size(), false));
            for (Entry<M, R> e : g)
                lines.add(new Line<>(e, e.machine(), 1, true));
        }
        return new UsageOrder<>(lines, byId);
    }

    /** How much a minute that is: {@code total} over {@code spanTicks} (at least ten seconds, so the first seconds do not look huge). */
    public static long perMinute(long total, long spanTicks) {
        long span = Math.max(200, spanTicks);
        double v = total * 1200.0 / span;
        return v >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(v);
    }
}
