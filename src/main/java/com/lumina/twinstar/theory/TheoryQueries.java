package com.lumina.twinstar.theory;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.cache.Scc;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.corelib.util.BigAmountFormat;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Server: reads the engine's last published theory layer for the theory viewer (never waits for the engine). */
public final class TheoryQueries {
    /** map items / patterns per packet: well under the packet limit even with long item data */
    public static final int ITEMS_PER_PART = 1500, PATTERNS_PER_PART = 800;

    private TheoryQueries() {
    }

    /** The whole layer (the items patterns take or make, and the patterns), cut into packets. */
    public static List<TheoryMap.Part> map(TwinStarService service) {
        Snapshot snap = service.getLastSnapshot();
        ItemIndex index = service.getIndex();
        if (snap == null)
            return List.of(new TheoryMap.Part(-1, 0, 1, 0, 0, 0, List.of(), 0, List.of()));
        // only items some pattern takes or makes: the index also has stock-only items and the
        // machines patterns run in, which would stand alone on the map
        boolean[] inPatterns = new boolean[snap.itemCount()];
        for (int p = 0; p < snap.patternCount(); p++) {
            Pattern pat = snap.pattern(p);
            if (pat == null)
                continue;
            for (int s = 0; s < pat.inputCount(); s++)
                if (pat.inputItem(s) < inPatterns.length)
                    inPatterns[pat.inputItem(s)] = true;
            for (int o = 0; o < pat.outputCount(); o++)
                if (pat.outputItem(o) < inPatterns.length)
                    inPatterns[pat.outputItem(o)] = true;
        }
        int[] itemAt = new int[snap.itemCount()];
        List<Integer> ids = new ArrayList<>();
        List<AEKey> keys = new ArrayList<>();
        for (int x = 0; x < snap.itemCount(); x++) {
            AEKey key = inPatterns[x] ? index.keyOf(x) : null;
            itemAt[x] = key == null ? -1 : ids.size();
            if (key != null) {
                ids.add(x);
                keys.add(key);
            }
        }
        int[] patternAt = new int[snap.patternCount()];
        List<TheoryMap.Pattern> patterns = new ArrayList<>();
        for (int p = 0; p < snap.patternCount(); p++) {
            Pattern pat = snap.pattern(p);
            patternAt[p] = -1;
            if (pat == null)
                continue;
            Map<Long, BigInteger> in = new LinkedHashMap<>();       // (item, kind) → amount
            for (int s = 0; s < pat.inputCount(); s++) {
                int y = pat.inputItem(s) < itemAt.length ? itemAt[pat.inputItem(s)] : -1;
                if (y >= 0)
                    in.merge(((long) y << 8) | pat.inputKind(s).ordinal(), pat.inputAmount(s).bigValue(), BigInteger::add);
            }
            List<TheoryMap.In> ins = new ArrayList<>(in.size());
            in.forEach((k, v) -> ins.add(new TheoryMap.In((int) (k >> 8), text(v), (int) (k & 0xFF))));
            List<TheoryMap.Out> outs = new ArrayList<>();
            for (int o = 0; o < pat.outputCount(); o++) {
                int x = pat.outputItem(o);
                int at = x < itemAt.length ? itemAt[x] : -1;
                if (at >= 0)
                    outs.add(new TheoryMap.Out(at, text(pat.outputAmount(o).bigValue()), pat.isByproduct(o),
                            pat.isByproduct(o) ? Double.POSITIVE_INFINITY : snap.unitLogCost(p, x)));
            }
            AEKey machine = pat.machine >= 0 ? index.keyOf(pat.machine) : null;
            patternAt[p] = patterns.size();
            patterns.add(new TheoryMap.Pattern(machine, ins, outs));
        }
        List<TheoryMap.Item> items = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            int x = ids.get(i);
            boolean raw = snap.isRaw(x), mined = snap.isMined(x);
            double cost = snap.logCost(x);
            int flags = (raw ? TheoryMap.RAW : 0) | (mined ? TheoryMap.MINED : 0)
                    | (!raw && !mined && cost == Double.POSITIVE_INFINITY ? TheoryMap.CANNOT : 0)
                    | (raw && service.isInfinite(keys.get(i)) ? TheoryMap.INFINITE : 0);
            int best = snap.best(x);
            items.add(new TheoryMap.Item(keys.get(i), snap.level(x), cost, flags,
                    best >= 0 && best < patternAt.length ? patternAt[best] : -1, snap.scc(x)));
        }
        int count = Math.max(1, Math.max((items.size() + ITEMS_PER_PART - 1) / ITEMS_PER_PART,
                (patterns.size() + PATTERNS_PER_PART - 1) / PATTERNS_PER_PART));
        List<TheoryMap.Part> parts = new ArrayList<>(count);
        for (int k = 0; k < count; k++) {
            int i0 = Math.min(items.size(), k * ITEMS_PER_PART), i1 = Math.min(items.size(), i0 + ITEMS_PER_PART);
            int p0 = Math.min(patterns.size(), k * PATTERNS_PER_PART), p1 = Math.min(patterns.size(), p0 + PATTERNS_PER_PART);
            parts.add(new TheoryMap.Part(snap.version(), k, count, items.size(), patterns.size(),
                    i0, List.copyOf(items.subList(i0, i1)), p0, List.copyOf(patterns.subList(p0, p1))));
        }
        return parts;
    }

    public static TheoryItem item(TwinStarService service, AEKey key) {
        Snapshot snap = service.getLastSnapshot();
        ItemIndex index = service.getIndex();
        int x = index.find(key);
        if (snap == null || x < 0 || x >= snap.itemCount())
            return TheoryItem.unknown(key);

        int[] producers = snap.producers(x);
        int best = snap.best(x);
        List<TheoryItem.Route> routes = new ArrayList<>();
        for (int p : producers) {
            if (routes.size() >= TheoryItem.MAX_ROUTES)
                break;
            Pattern pat = snap.pattern(p);
            if (pat == null)
                continue;
            AEKey machine = pat.machine >= 0 ? index.keyOf(pat.machine) : null;
            Map<AEKey, TheoryItem.Stack> inputs = new LinkedHashMap<>();
            for (int s = 0; s < pat.inputCount() && inputs.size() < TheoryItem.MAX_LIST; s++) {
                AEKey k = index.keyOf(pat.inputItem(s));
                if (k == null)
                    continue;
                int kind = pat.inputKind(s).ordinal();
                BigInteger a = pat.inputAmount(s).bigValue();
                TheoryItem.Stack old = inputs.get(k);
                if (old != null)
                    a = a.add(new BigInteger(digitsOr(old.amount())));
                inputs.put(k, new TheoryItem.Stack(k, text(a), old != null ? old.kind() : kind));
            }
            List<TheoryItem.Stack> outputs = new ArrayList<>();
            for (int o = 0; o < pat.outputCount() && outputs.size() < TheoryItem.MAX_LIST; o++) {
                AEKey k = index.keyOf(pat.outputItem(o));
                if (k != null)
                    outputs.add(new TheoryItem.Stack(k, text(pat.outputAmount(o).bigValue()), pat.isByproduct(o) ? 1 : 0));
            }
            routes.add(new TheoryItem.Route(p, p == best, snap.unitLogCost(p, x), machine, List.copyOf(inputs.values()), outputs));
        }

        List<TheoryItem.Raw> bom = new ArrayList<>();
        int[] bomItems = snap.bomItems(x);
        double[] bomLog = snap.bomLog(x);
        for (int i = 0; i < bomItems.length; i++) {
            AEKey k = index.keyOf(bomItems[i]);
            if (k != null && bomItems[i] != x)
                bom.add(new TheoryItem.Raw(k, bomLog[i]));
        }
        bom.sort(Comparator.comparingDouble(TheoryItem.Raw::log).reversed());
        if (bom.size() > TheoryItem.MAX_BOM)
            bom = new ArrayList<>(bom.subList(0, TheoryItem.MAX_BOM));

        return new TheoryItem(key, true, snap.level(x), snap.logCost(x), snap.isRaw(x), snap.isMined(x),
                service.isInfinite(key), routes, Math.max(0, producers.length - routes.size()), bom,
                loop(snap, index, x));
    }

    private static TheoryItem.@Nullable Loop loop(Snapshot snap, ItemIndex index, int x) {
        int s = snap.scc(x);
        if (s < 0 || s >= snap.sccCount())
            return null;
        Scc scc = snap.sccAt(s);
        List<AEKey> members = new ArrayList<>();
        for (int i = 0; i < scc.memberCount() && members.size() < TheoryItem.MAX_LIST; i++) {
            AEKey k = index.keyOf(scc.member(i));
            if (k != null)
                members.add(k);
        }
        List<TheoryItem.Stack> seeds = new ArrayList<>();
        for (int i = 0; i < scc.seedCount() && seeds.size() < TheoryItem.MAX_LIST; i++) {
            AEKey k = index.keyOf(scc.seedItem(i));
            if (k != null)
                seeds.add(new TheoryItem.Stack(k, text(scc.seedAmount(i).bigValue()), 0));
        }
        return new TheoryItem.Loop(s, members, seeds);
    }

    /** Display text as the plan screen's: digits up to a long, unit form beyond. */
    private static String text(BigInteger v) {
        if (v.signum() <= 0)
            return "";
        return BigAmountFormat.fitsLong(v) ? v.toString() : BigAmountFormat.unitForm(v);
    }

    private static String digitsOr(String s) {
        return s.isEmpty() || !s.chars().allMatch(Character::isDigit) ? "0" : s;
    }
}
