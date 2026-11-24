package com.lumina.twinstar.algorithm.execution;

import com.lumina.bridge.Face;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.ByteUnits;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;
import com.lumina.twinstar.algorithm.model.Rational;
import com.lumina.twinstar.algorithm.planning.CycleContract;
import com.lumina.twinstar.algorithm.planning.Plan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Saves the executor's jobs with the hub's grid node and reads them back. Item and pattern ids
 * are given out afresh every time the server starts, so a save keeps a table of the item keys and
 * one of the patterns the jobs use, and refers to them by their place in those tables; reading
 * maps them to the ids of the running server. A pattern keeps everything a job needs of it (its
 * inputs and outputs) and the encoded pattern it came from, decoded again to find who still
 * offers it: a pattern nobody offers waits, as AE2's own CPUs do.
 * <p>
 * Not saved: the batch windows of providers and the stuck timers (they start over), a job's ready
 * queue (rebuilt from its inventory).
 */
public final class JobStore {
    private JobStore() {
    }

    /** A job read back, with what the caller saved beside it (its crafting link). */
    public record Loaded(Job job, CompoundTag extra) {
    }

    /** An item or pattern the save refers to that this server no longer knows. */
    private static final class Missing extends RuntimeException {
        Missing(String what) {
            super(what, null, false, false);
        }
    }

    // ───────────────────────────── save ─────────────────────────────

    /**
     * Everything the hub's jobs need to go on after a restart. {@code extra} adds what the caller
     * keeps per job (its crafting link).
     */
    public static CompoundTag save(HubState hub, ItemIndex index, HolderLookup.Provider regs, long nowNanos,
                                   Function<Job, CompoundTag> extra) {
        Writer w = new Writer(index, regs);
        ListTag jobs = new ListTag();
        for (Job job : hub.jobs)
            if (!job.finished)
                jobs.add(w.job(job, nowNanos, extra.apply(job)));
        CompoundTag out = new CompoundTag();
        out.put("jobs", jobs);
        out.put("pool", w.amounts(hub.pool, w::key));
        ListTag orphans = new ListTag();
        hub.orphans.forEach((k, n) -> orphans.add(w.keyCount(k, n)));
        out.put("orphans", orphans);
        ListTag lost = new ListTag();
        hub.lostAt.forEach((at, flights) -> {
            CompoundTag t = new CompoundTag();
            t.put("at", place(at));
            t.put("flights", w.flights(flights));
            lost.add(t);
        });
        out.put("lostAt", lost);
        ListTag machines = new ListTag();
        for (HubState.MachineState m : hub.machines.values()) {
            GlobalPos at = m.place;
            if (at == null)
                continue;
            machines.add(w.machine(at, m.feed, m.lastPattern, m.lastPat, m.inFlight, m.overflow));
        }
        hub.restoring.forEach((at, r) -> machines.add(w.machine(at, r.feed(), r.lastPattern(), r.lastPat(), r.inFlight(), r.overflow())));
        out.put("machines", machines);
        // the tables last: everything above added to them
        out.put("keys", w.keys);
        out.put("patterns", w.patterns);
        return out;
    }

    private static final class Writer {
        final ItemIndex index;
        final HolderLookup.Provider regs;
        final Map<Integer, Integer> keyAt = new HashMap<>();
        final ListTag keys = new ListTag();
        final Map<Pattern, Integer> patternAt = new IdentityHashMap<>();
        final ListTag patterns = new ListTag();

        Writer(ItemIndex index, HolderLookup.Provider regs) {
            this.index = index;
            this.regs = regs;
        }

        int key(int item) {
            Integer at = keyAt.get(item);
            if (at != null)
                return at;
            AEKey k = index.keyOf(item);
            if (k == null)
                throw new IllegalStateException("no key for item id " + item);
            keys.add(k.toTagGeneric(regs));
            keyAt.put(item, keys.size() - 1);
            return keys.size() - 1;
        }

        int key(Key k) {
            return key(index.idOf(k));
        }

        CompoundTag keyCount(Key k, long n) {
            CompoundTag t = new CompoundTag();
            t.putInt("k", key(k));
            t.putLong("n", n);
            return t;
        }

        int pattern(Pattern p) {
            Integer at = patternAt.get(p);
            if (at != null)
                return at;
            CompoundTag t = new CompoundTag();
            ListTag in = new ListTag();
            for (int i = 0; i < p.inputCount(); i++) {
                CompoundTag s = new CompoundTag();
                s.putInt("k", key(p.inputItem(i)));
                s.putString("a", amount(p.inputAmount(i)));
                s.putString("kind", p.inputKind(i).name());
                s.putInt("dur", p.inputDurability(i));
                int[] alts = new int[p.alternativeCount(i)];
                for (int j = 0; j < alts.length; j++)
                    alts[j] = key(p.alternative(i, j));
                s.putIntArray("alt", alts);
                s.putInt("faces", p.inputFaces(i));
                s.putInt("target", p.inputTarget(i));
                s.putInt("role", p.inputRole(i));
                s.putInt("guiSlot", p.inputGuiSlot(i));
                in.add(s);
            }
            t.put("in", in);
            ListTag out = new ListTag();
            for (int o = 0; o < p.outputCount(); o++) {
                CompoundTag s = new CompoundTag();
                s.putInt("k", key(p.outputItem(o)));
                s.putString("a", amount(p.outputAmount(o)));
                s.putBoolean("by", p.isByproduct(o));
                s.putDouble("chance", p.outputChance(o));
                s.putInt("guiSlot", p.outputGuiSlotOf(p.outputItem(o)));
                out.add(s);
            }
            t.put("out", out);
            t.putString("where", p.where.name());
            t.putInt("machine", p.machine < 0 ? -1 : key(p.machine));
            int[] free = p.nbtFreeOutputs();
            for (int i = 0; i < free.length; i++)
                free[i] = key(free[i]);
            if (free.length > 0)
                t.putIntArray("nbtFree", free);
            int[] subs = new int[p.subMachineCount()];
            for (int i = 0; i < subs.length; i++)
                subs[i] = key(p.subMachine(i));
            if (subs.length > 0)
                t.putIntArray("subMachines", subs);
            t.putInt("priority", p.priority);
            t.putBoolean("subst", p.allowSubst);
            t.putString("energyNum", p.energyPerRun.num.toString());
            t.putString("energyDen", p.energyPerRun.den.toString());
            AEItemKey definition = definitionOf(p);
            if (definition != null)
                t.put("definition", definition.toTagGeneric(regs));
            patterns.add(t);
            patternAt.put(p, patterns.size() - 1);
            return patterns.size() - 1;
        }

        int pattern(Job job, int p) {
            return pattern(job.snap.pattern(p));
        }

        ListTag amounts(AmountMap m, IntUnaryOperator id) {
            ListTag l = new ListTag();
            m.forEach((k, a) -> {
                CompoundTag t = new CompoundTag();
                t.putInt("k", id.applyAsInt(k));
                t.putString("a", amount(a));
                l.add(t);
            });
            return l;
        }

        ListTag flights(Map<HubState.Flight, Amount> flights) {
            ListTag l = new ListTag();
            flights.forEach((f, runs) -> {
                CompoundTag t = new CompoundTag();
                t.putLong("job", f.job().id);
                t.putInt("p", pattern(f.job(), f.pattern()));
                t.putString("runs", amount(runs));
                l.add(t);
            });
            return l;
        }

        CompoundTag machine(GlobalPos at, @Nullable HubState.Feed feed, int lastPattern, @Nullable Pattern lastPat,
                            Map<HubState.Flight, Amount> inFlight, Map<Key, Long> overflow) {
            CompoundTag t = new CompoundTag();
            t.put("at", place(at));
            if (feed != null && feed.job.finished) {
                // its job is not saved: what had not gone in yet is kept as loose products
                overflow = new LinkedHashMap<>(overflow);
                HubState.feedLeft(feed, overflow);
            } else if (feed != null) {
                CompoundTag f = new CompoundTag();
                f.putLong("job", feed.job.id);
                f.putInt("p", pattern(feed.job, feed.pattern));
                f.putLong("total", feed.total);
                f.putLong("fed", feed.fed);
                ListTag parts = new ListTag();
                for (HubState.Part part : feed.parts) {
                    CompoundTag pt = new CompoundTag();
                    pt.putInt("k", key(part.key));
                    pt.putLong("left", part.left);
                    int[] faces = new int[part.faces.size()];
                    for (int i = 0; i < faces.length; i++)
                        faces[i] = part.faces.get(i) == null ? -1 : part.faces.get(i).ordinal();
                    pt.putIntArray("faces", faces);
                    pt.putInt("target", part.target);
                    pt.putInt("role", part.role);
                    pt.putInt("guiSlot", part.guiSlot);
                    parts.add(pt);
                }
                f.put("parts", parts);
                ListTag tools = new ListTag();
                for (Key tool : feed.tools) {
                    CompoundTag tt = new CompoundTag();
                    tt.putInt("k", key(tool));
                    tools.add(tt);
                }
                f.put("tools", tools);
                t.put("feed", f);
            }
            t.putInt("last", lastPat == null ? -1 : pattern(lastPat));
            Map<HubState.Flight, Amount> live = new LinkedHashMap<>();
            inFlight.forEach((f, r) -> {
                if (!f.job().finished)
                    live.put(f, r);
            });
            t.put("inFlight", flights(live));
            ListTag over = new ListTag();
            overflow.forEach((k, n) -> over.add(keyCount(k, n)));
            t.put("overflow", over);
            return t;
        }

        CompoundTag job(Job job, long nowNanos, CompoundTag extra) {
            CompoundTag t = new CompoundTag();
            t.putLong("id", job.id);
            if (job.player != null)
                t.putUUID("player", job.player);
            t.putLong("elapsed", Math.max(0, nowNanos - job.startNanos));
            t.put("plan", plan(job));
            t.put("inventory", amounts(job.inventory, this::key));
            ListTag tasks = new ListTag();
            for (Task task : job.tasks) {
                CompoundTag s = new CompoundTag();
                s.putInt("p", pattern(job, task.pattern));
                s.putInt("level", task.level);
                s.putInt("scc", task.scc);
                s.putString("left", amount(task.remaining));
                s.putString("inFlight", amount(task.inFlight));
                tasks.add(s);
            }
            t.put("tasks", tasks);
            t.put("outstanding", amounts(job.outstanding, this::key));
            t.putString("occupancy", amount(job.occupancy));
            t.put("releasedUnits", amounts(job.releasedUnits, this::key));
            t.put("releasedBytes", amounts(job.releasedBytes, this::key));
            t.putString("delivered", amount(job.delivered));
            t.put("protectedSeeds", amounts(job.protectedSeeds, this::key));
            ListTag sccSeeds = new ListTag();
            job.sccSeeds.forEach((scc, seeds) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("scc", scc);
                s.put("seeds", amounts(seeds, this::key));
                sccSeeds.add(s);
            });
            t.put("sccSeeds", sccSeeds);
            int[] guards = new int[job.recycleGuards.size()];
            for (int i = 0; i < guards.length; i++)
                guards[i] = job.plan.recycles.indexOf(job.recycleGuards.get(i));
            t.putIntArray("recycleGuards", guards);
            ListTag contracts = new ListTag();
            job.contractStep.forEach((scc, step) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("scc", scc);
                s.putInt("step", step);
                Amount left = job.contractLeft.get(scc);
                if (left != null)
                    s.putString("left", amount(left));
                contracts.add(s);
            });
            t.put("contracts", contracts);
            t.put("toolUses", amounts(job.toolUses, this::key));
            t.put("drawLater", amounts(job.drawLater, this::key));
            ListTag chances = new ListTag();
            job.chances.forEach((p, c) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("p", pattern(job, p));
                s.putString("runsDone", amount(c.runsDone));
                s.put("got", amounts(c.got, this::key));
                s.putBoolean("expected", c.expected);
                s.putBoolean("marked", c.marked);
                chances.add(s);
            });
            t.put("chances", chances);
            t.put("chanceShort", amounts(job.chanceShort, this::key));
            t.put("shortage", amounts(job.shortage, this::key));
            t.putBoolean("forced", job.forced);
            t.put("machineLost", amounts(job.machineLost, p -> pattern(job, p)));
            t.putBoolean("suspended", job.suspended);
            t.putBoolean("unloaded", job.unloaded);
            t.putBoolean("cancelled", job.cancelled);
            t.put("extra", extra);
            return t;
        }

        private CompoundTag plan(Job job) {
            Plan plan = job.plan;
            CompoundTag t = new CompoundTag();
            t.putInt("target", key(plan.target));
            t.putString("amount", amount(plan.amount));
            t.put("patternTimes", amounts(plan.patternTimes, p -> pattern(job, p)));
            t.put("pulled", amounts(plan.pulled, this::key));
            t.put("drawLater", amounts(plan.drawLater, this::key));
            t.put("leftover", amounts(plan.leftover, this::key));
            t.putString("usage", amount(plan.usage));
            ListTag contracts = new ListTag();
            plan.contracts.forEach((scc, c) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("scc", scc);
                int[] pats = new int[c.patterns().length];
                for (int i = 0; i < pats.length; i++)
                    pats[i] = pattern(job, c.patterns()[i]);
                s.putIntArray("patterns", pats);
                ListTag batches = new ListTag();
                for (Amount b : c.batches())
                    batches.add(net.minecraft.nbt.StringTag.valueOf(amount(b)));
                s.put("batches", batches);
                contracts.add(s);
            });
            t.put("contracts", contracts);
            ListTag loopSeeds = new ListTag();
            plan.loopSeeds.forEach((scc, seeds) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("scc", scc);
                s.put("seeds", amounts(seeds, this::key));
                loopSeeds.add(s);
            });
            t.put("loopSeeds", loopSeeds);
            ListTag recycles = new ListTag();
            for (Plan.Recycle r : plan.recycles) {
                CompoundTag s = new CompoundTag();
                s.putInt("k", key(r.item()));
                s.putString("seed", amount(r.seed()));
                int[] pats = new int[r.patterns().length];
                for (int i = 0; i < pats.length; i++)
                    pats[i] = pattern(job, r.patterns()[i]);
                s.putIntArray("patterns", pats);
                recycles.add(s);
            }
            t.put("recycles", recycles);
            ListTag chanceNeeds = new ListTag();
            plan.chanceNeeds.forEach((p, need) -> {
                CompoundTag s = new CompoundTag();
                s.putInt("p", pattern(job, p));
                s.put("items", amounts(need.items(), this::key));
                s.putString("sureRuns", amount(need.sureRuns()));
                chanceNeeds.add(s);
            });
            t.put("chanceNeeds", chanceNeeds);
            return t;
        }
    }

    /** The encoded pattern a pattern came from: AE2's pattern, or the key a save could not decode again. */
    static @Nullable AEItemKey definitionOf(Pattern p) {
        if (p.source() instanceof IPatternDetails details)
            return details.getDefinition();
        if (p.source() instanceof AEItemKey undecoded)
            return undecoded;
        return null;
    }

    // ───────────────────────────── load ─────────────────────────────

    /**
     * Puts the saved jobs back into {@code hub} (which should have none) and returns them with
     * what the caller saved beside each. A job that refers to an item this server no longer knows
     * (its mod is gone) cannot go on: it is left out and reported to {@code warn}.
     */
    public static List<Loaded> load(HubState hub, CompoundTag tag, ItemIndex index, HolderLookup.Provider regs, Level level,
                                    IntSupplier newPatternId, ByteUnits units, long nowNanos, Consumer<String> warn) {
        Reader r = new Reader(tag, index, regs, level, newPatternId, units, warn);
        List<Loaded> out = new ArrayList<>();
        Map<Long, Job> byId = new HashMap<>();
        for (Tag t : tag.getList("jobs", Tag.TAG_COMPOUND)) {
            CompoundTag jt = (CompoundTag) t;
            try {
                Job job = r.job(jt, nowNanos);
                hub.restore(job, nowNanos);
                byId.put(job.id, job);
                out.add(new Loaded(job, jt.getCompound("extra")));
            } catch (RuntimeException e) {
                warn.accept("雙星：存檔裡的工作 #" + jt.getLong("id") + " 無法還原（" + e.getMessage() + "），已略過");
                r.salvage(jt, hub.orphans);     // what it held and this server still knows goes to network storage
            }
        }
        r.amounts(tag.getList("pool", Tag.TAG_COMPOUND), r::item, hub.pool, true);
        for (Tag t : tag.getList("orphans", Tag.TAG_COMPOUND)) {
            Key k = r.key(((CompoundTag) t).getInt("k"));
            if (k != null)
                hub.orphans.merge(k, ((CompoundTag) t).getLong("n"), Long::sum);
        }
        for (Tag t : tag.getList("lostAt", Tag.TAG_COMPOUND)) {
            CompoundTag lt = (CompoundTag) t;
            GlobalPos at = place(lt.getCompound("at"));
            Map<HubState.Flight, Amount> flights = r.flights(lt.getList("flights", Tag.TAG_COMPOUND), byId);
            if (at != null && !flights.isEmpty())
                hub.lostAt.put(at, flights);
        }
        for (Tag t : tag.getList("machines", Tag.TAG_COMPOUND)) {
            CompoundTag mt = (CompoundTag) t;
            GlobalPos at = place(mt.getCompound("at"));
            if (at == null)
                continue;
            HubState.Feed feed = null;
            Map<Key, Long> overflow = new LinkedHashMap<>();
            if (mt.contains("feed")) {
                CompoundTag ft = mt.getCompound("feed");
                Job job = byId.get(ft.getLong("job"));
                Integer p = r.patternOrNull(ft.getInt("p"));
                feed = new HubState.Feed(job, p == null ? -1 : p);
                feed.total = ft.getLong("total");
                feed.fed = ft.getLong("fed");
                for (Tag pt : ft.getList("parts", Tag.TAG_COMPOUND)) {
                    CompoundTag ptt = (CompoundTag) pt;
                    Key k = r.key(ptt.getInt("k"));
                    if (k == null)
                        continue;
                    List<@Nullable Face> faces = new ArrayList<>();
                    for (int f : ptt.getIntArray("faces"))
                        faces.add(f < 0 ? null : Face.values()[f]);
                    feed.parts.add(new HubState.Part(k, ptt.getLong("left"), faces, ptt.getInt("target"), ptt.getInt("role"), ptt.getInt("guiSlot")));
                }
                for (Tag tt : ft.getList("tools", Tag.TAG_COMPOUND)) {
                    Key k = r.key(((CompoundTag) tt).getInt("k"));
                    if (k != null)
                        feed.tools.add(k);
                }
                if (job == null || p == null) {
                    // its job did not come back: what had not gone in yet is kept as loose products
                    HubState.feedLeft(feed, overflow);
                    feed = null;
                }
            }
            Integer last = r.patternOrNull(mt.getInt("last"));
            Pattern lastPat = last == null ? null : r.patterns[mt.getInt("last")];
            Map<HubState.Flight, Amount> inFlight = r.flights(mt.getList("inFlight", Tag.TAG_COMPOUND), byId);
            for (Tag ot : mt.getList("overflow", Tag.TAG_COMPOUND)) {
                Key k = r.key(((CompoundTag) ot).getInt("k"));
                if (k != null)
                    overflow.merge(k, ((CompoundTag) ot).getLong("n"), Long::sum);
            }
            hub.restoring.put(at, new HubState.RestoredMachine(feed, last == null ? -1 : last, lastPat, inFlight, overflow));
        }
        hub.onHubResume();      // what machines returned while the hub was down may be in network storage
        return out;
    }

    private static final class Reader {
        final HolderLookup.Provider regs;
        final ItemIndex index;
        final Consumer<String> warn;
        final Key[] keys;
        final int[] items;
        final Pattern[] patterns;
        final Snapshot snap;

        Reader(CompoundTag tag, ItemIndex index, HolderLookup.Provider regs, Level level, IntSupplier newPatternId,
               ByteUnits units, Consumer<String> warn) {
            this.regs = regs;
            this.index = index;
            this.warn = warn;
            ListTag keyList = tag.getList("keys", Tag.TAG_COMPOUND);
            keys = new Key[keyList.size()];
            items = new int[keyList.size()];
            for (int i = 0; i < keys.length; i++) {
                AEKey k = null;
                try {
                    k = AEKey.fromTagGeneric(regs, keyList.getCompound(i));
                } catch (RuntimeException e) {
                    // its mod is gone
                }
                keys[i] = k == null ? null : Keys.of(k);
                items[i] = k == null ? -1 : index.idOf(k);
            }
            ListTag patList = tag.getList("patterns", Tag.TAG_COMPOUND);
            patterns = new Pattern[patList.size()];
            int maxId = -1;
            for (int i = 0; i < patterns.length; i++) {
                try {
                    patterns[i] = pattern(patList.getCompound(i), newPatternId.getAsInt(), level);
                    maxId = Math.max(maxId, patterns[i].id);
                } catch (RuntimeException e) {
                    patterns[i] = null;     // a job that uses it is left out
                }
            }
            Pattern[] byId = new Pattern[maxId + 1];
            for (Pattern p : patterns)
                if (p != null)
                    byId[p.id] = p;
            snap = Snapshot.ofPatterns(byId, units);
        }

        @Nullable Key key(int at) {
            return at >= 0 && at < keys.length ? keys[at] : null;
        }

        int item(int at) {
            if (at < 0 || at >= items.length || items[at] < 0)
                throw new Missing("一個物品已不存在");
            return items[at];
        }

        int pattern(int at) {
            if (at < 0 || at >= patterns.length || patterns[at] == null)
                throw new Missing("一個樣板讀不回來");
            return patterns[at].id;
        }

        @Nullable Integer patternOrNull(int at) {
            return at >= 0 && at < patterns.length && patterns[at] != null ? patterns[at].id : null;
        }

        private Pattern pattern(CompoundTag t, int id, Level level) {
            Pattern.Builder b = new Pattern.Builder();
            PatternLocation where = PatternLocation.valueOf(t.getString("where"));
            b.where(where);
            int machine = t.getInt("machine");
            if (machine >= 0) {
                b.machine(item(machine));
                for (int sub : t.getIntArray("subMachines"))
                    b.subMachine(item(sub));
                for (int free : t.getIntArray("nbtFree"))
                    b.ignoreNbtOf(item(free));
            }
            for (Tag it : t.getList("in", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) it;
                int[] alts = s.getIntArray("alt");
                int[] altIds = new int[alts.length];
                for (int j = 0; j < alts.length; j++)
                    altIds[j] = item(alts[j]);
                b.input(item(s.getInt("k")), amount(s.getString("a")), InputKind.valueOf(s.getString("kind")), s.getInt("dur"), altIds);
                if (s.getInt("faces") != 0)
                    b.faces(s.getInt("faces"));
                if (s.getInt("target") != 0)
                    b.target(s.getInt("target"));
                if (s.getInt("role") != 0)
                    b.role(s.getInt("role"));
                if (s.getInt("guiSlot") != 0)
                    b.guiSlot(s.getInt("guiSlot"));
            }
            for (Tag ot : t.getList("out", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) ot;
                b.output(item(s.getInt("k")), amount(s.getString("a")), s.getBoolean("by"), s.getDouble("chance"));
                if (s.getInt("guiSlot") > 0)
                    b.outputGuiSlot(item(s.getInt("k")), s.getInt("guiSlot"));
            }
            b.priority(t.getInt("priority")).allowSubst(t.getBoolean("subst"))
                    .energyPerRun(Rational.of(new BigInteger(t.getString("energyNum")), new BigInteger(t.getString("energyDen"))));
            if (t.contains("definition")) {
                Object source = null;
                AEKey def = null;
                try {
                    def = AEKey.fromTagGeneric(regs, t.getCompound("definition"));
                } catch (RuntimeException e) {
                    // the pattern item's mod is gone: nobody can offer it again
                }
                if (def instanceof AEItemKey item) {
                    IPatternDetails details = null;
                    try {
                        details = PatternDetailsHelper.decodePattern(item, level);
                    } catch (RuntimeException e) {
                        // undecodable now: kept as the key, so the save keeps it and it waits
                    }
                    source = details != null ? details : item;
                }
                b.source(source);
            }
            return b.build(id);
        }

        void amounts(ListTag l, IntUnaryOperator id, AmountMap into, boolean skipMissing) {
            for (Tag t : l) {
                CompoundTag s = (CompoundTag) t;
                try {
                    into.add(id.applyAsInt(s.getInt("k")), amount(s.getString("a")));
                } catch (Missing e) {
                    if (!skipMissing)
                        throw e;
                }
            }
        }

        AmountMap amounts(ListTag l, IntUnaryOperator id) {
            AmountMap m = new AmountMap();
            amounts(l, id, m, false);
            return m;
        }

        /** The inventory of a job that cannot go on: the items this server still knows. */
        void salvage(CompoundTag job, Map<Key, Long> into) {
            for (Tag t : job.getList("inventory", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) t;
                Key k = key(s.getInt("k"));
                Amount a = amount(s.getString("a"));
                if (k != null && a.signum() > 0)
                    into.merge(k, a.saturatedLong(), (x, y) -> Amount.of(x).add(Amount.of(y)).saturatedLong());
            }
        }

        Map<HubState.Flight, Amount> flights(ListTag l, Map<Long, Job> byId) {
            Map<HubState.Flight, Amount> out = new LinkedHashMap<>();
            for (Tag t : l) {
                CompoundTag s = (CompoundTag) t;
                Job job = byId.get(s.getLong("job"));
                Integer p = patternOrNull(s.getInt("p"));
                if (job != null && p != null)
                    out.merge(new HubState.Flight(job, p), amount(s.getString("runs")), Amount::add);
            }
            return out;
        }

        Job job(CompoundTag t, long nowNanos) {
            Plan plan = plan(t.getCompound("plan"));
            Job job = new Job(t.getLong("id"), plan, snap, nowNanos - t.getLong("elapsed"), amount(t.getString("occupancy")));
            if (t.hasUUID("player"))
                job.player = t.getUUID("player");
            amounts(t.getList("inventory", Tag.TAG_COMPOUND), this::item, job.inventory, false);
            for (Tag tt : t.getList("tasks", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) tt;
                Task task = new Task(pattern(s.getInt("p")), s.getInt("level"), s.getInt("scc"), amount(s.getString("left")));
                task.inFlight = amount(s.getString("inFlight"));
                job.tasks.add(task);
            }
            amounts(t.getList("outstanding", Tag.TAG_COMPOUND), this::item, job.outstanding, false);
            amounts(t.getList("releasedUnits", Tag.TAG_COMPOUND), this::item, job.releasedUnits, false);
            amounts(t.getList("releasedBytes", Tag.TAG_COMPOUND), this::item, job.releasedBytes, false);
            job.delivered = amount(t.getString("delivered"));
            amounts(t.getList("protectedSeeds", Tag.TAG_COMPOUND), this::item, job.protectedSeeds, false);
            for (Tag st : t.getList("sccSeeds", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) st;
                job.sccSeeds.put(s.getInt("scc"), amounts(s.getList("seeds", Tag.TAG_COMPOUND), this::item));
            }
            for (int g : t.getIntArray("recycleGuards"))
                if (g >= 0 && g < plan.recycles.size())
                    job.recycleGuards.add(plan.recycles.get(g));
            for (Tag ct : t.getList("contracts", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) ct;
                job.contractStep.put(s.getInt("scc"), s.getInt("step"));
                if (s.contains("left"))
                    job.contractLeft.put(s.getInt("scc"), amount(s.getString("left")));
            }
            amounts(t.getList("toolUses", Tag.TAG_COMPOUND), this::item, job.toolUses, false);
            amounts(t.getList("drawLater", Tag.TAG_COMPOUND), this::item, job.drawLater, false);
            for (Tag ct : t.getList("chances", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) ct;
                int p = pattern(s.getInt("p"));
                Plan.ChanceNeed need = plan.chanceNeeds.get(p);
                if (need == null)
                    continue;
                ChanceProgress c = new ChanceProgress(need);
                c.runsDone = amount(s.getString("runsDone"));
                amounts(s.getList("got", Tag.TAG_COMPOUND), this::item, c.got, false);
                c.expected = s.getBoolean("expected");
                c.marked = s.getBoolean("marked");
                job.chances.put(p, c);
            }
            amounts(t.getList("chanceShort", Tag.TAG_COMPOUND), this::item, job.chanceShort, false);
            amounts(t.getList("shortage", Tag.TAG_COMPOUND), this::item, job.shortage, false);
            job.forced = t.getBoolean("forced");
            amounts(t.getList("machineLost", Tag.TAG_COMPOUND), this::pattern, job.machineLost, false);
            job.suspended = t.getBoolean("suspended");
            job.unloaded = t.getBoolean("unloaded");
            job.cancelled = t.getBoolean("cancelled");
            return job;
        }

        private Plan plan(CompoundTag t) {
            Map<Integer, CycleContract> contracts = new HashMap<>();
            for (Tag ct : t.getList("contracts", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) ct;
                int[] pats = s.getIntArray("patterns");
                int[] ids = new int[pats.length];
                for (int i = 0; i < pats.length; i++)
                    ids[i] = pattern(pats[i]);
                ListTag bl = s.getList("batches", Tag.TAG_STRING);
                Amount[] batches = new Amount[bl.size()];
                for (int i = 0; i < batches.length; i++)
                    batches[i] = amount(bl.getString(i));
                contracts.put(s.getInt("scc"), new CycleContract(s.getInt("scc"), ids, batches));
            }
            Map<Integer, AmountMap> loopSeeds = new HashMap<>();
            for (Tag st : t.getList("loopSeeds", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) st;
                loopSeeds.put(s.getInt("scc"), amounts(s.getList("seeds", Tag.TAG_COMPOUND), this::item));
            }
            List<Plan.Recycle> recycles = new ArrayList<>();
            for (Tag rt : t.getList("recycles", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) rt;
                int[] pats = s.getIntArray("patterns");
                int[] ids = new int[pats.length];
                for (int i = 0; i < pats.length; i++)
                    ids[i] = pattern(pats[i]);
                recycles.add(new Plan.Recycle(item(s.getInt("k")), amount(s.getString("seed")), ids));
            }
            Map<Integer, Plan.ChanceNeed> chanceNeeds = new HashMap<>();
            for (Tag nt : t.getList("chanceNeeds", Tag.TAG_COMPOUND)) {
                CompoundTag s = (CompoundTag) nt;
                chanceNeeds.put(pattern(s.getInt("p")), new Plan.ChanceNeed(amounts(s.getList("items", Tag.TAG_COMPOUND), this::item),
                        amount(s.getString("sureRuns"))));
            }
            return Plan.restore(item(t.getInt("target")), amount(t.getString("amount")),
                    amounts(t.getList("patternTimes", Tag.TAG_COMPOUND), this::pattern),
                    amounts(t.getList("pulled", Tag.TAG_COMPOUND), this::item),
                    amounts(t.getList("drawLater", Tag.TAG_COMPOUND), this::item),
                    amounts(t.getList("leftover", Tag.TAG_COMPOUND), this::item),
                    amount(t.getString("usage")), contracts, loopSeeds, recycles, chanceNeeds);
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String amount(Amount a) {
        return a.bigValue().toString();
    }

    private static Amount amount(String s) {
        return s.isEmpty() ? Amount.ZERO : Amount.of(new BigInteger(s));
    }

    private static CompoundTag place(GlobalPos at) {
        CompoundTag t = new CompoundTag();
        t.putString("dim", at.dimension().location().toString());
        t.putLong("pos", at.pos().asLong());
        return t;
    }

    private static @Nullable GlobalPos place(CompoundTag t) {
        ResourceLocation dim = ResourceLocation.tryParse(t.getString("dim"));
        if (dim == null)
            return null;
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dim), BlockPos.of(t.getLong("pos")));
    }
}
