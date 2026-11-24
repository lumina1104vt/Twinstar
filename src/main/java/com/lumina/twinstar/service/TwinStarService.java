package com.lumina.twinstar.service;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridServiceProvider;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.crafting.UnsuitableCpus;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.execution.CraftingSubmitResult;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.ae2.NetworkStore;
import com.lumina.twinstar.algorithm.ae2.PatternCompiler;
import com.lumina.twinstar.algorithm.ae2.PlanWrapper;
import com.lumina.twinstar.algorithm.ae2.StockSnapshotter;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.execution.ExecutionEnv;
import com.lumina.twinstar.algorithm.execution.ExecutionSettings;
import com.lumina.twinstar.algorithm.execution.HubState;
import com.lumina.twinstar.algorithm.execution.Job;
import com.lumina.twinstar.algorithm.execution.JobStore;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.metrics.PlanStats;
import com.lumina.twinstar.metrics.TheoryReport;
import com.lumina.twinstar.metrics.Trend;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.OrderMode;
import com.lumina.twinstar.algorithm.model.Overlay;
import com.lumina.twinstar.order.ItemMark;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import com.lumina.twinstar.algorithm.planning.OrderResult;
import com.lumina.twinstar.algorithm.planning.Planner;
import appeng.api.implementations.blockentities.IChestOrDrive;
import com.lumina.twinstar.api.IBigStorage;
import com.lumina.twinstar.api.IMachineAccess;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.cpu.TwinStarCpu;
import com.lumina.twinstar.host.ITwinStarHost;
import com.lumina.twinstar.menu.CpuNumbers;
import com.lumina.twinstar.mixin.CraftingServiceAccessor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The engine's grid service. AE2 creates one per grid and injects the constructor arguments.
 * <p>
 * Mixins into AE2 cast {@code grid.getService(ITwinStarService.class)} to this class and call the
 * engine-facing methods directly, the same way AE2 treats its own CraftingService. Every mixin asks
 * {@link #hasHub()} first and leaves AE2's behaviour untouched when it is false.
 * <p>
 * Threads: the theory layer and planning run on one background engine thread (all its changes are
 * queued there); the executor ({@link HubState}), AE2 storage and the pattern registry are only
 * touched on the server thread.
 */
public final class TwinStarService implements ITwinStarService, IGridServiceProvider {
    /** One background thread for every grid: theory updates and planning, in order. */
    private static final ThreadPoolExecutor ENGINE = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "TwinStar Engine");
        t.setDaemon(true);
        return t;
    });
    /** grids with this service, for /twinstar status (weak: a grid that goes away is dropped) */
    private static final Set<TwinStarService> LIVE = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** Raw price tiers follow the stock about once a second. */
    private static final int STOCK_SYNC_TICKS = 20;
    /** Executor compute budget per tick. */
    private static final long TICK_BUDGET_NANOS = 5_000_000L;
    /** Submit re-plans once when the stock changed since planning; this bounds the wait. */
    private static final long REPLAN_WAIT_MS = 2_000L;

    private final IGrid grid;
    private final IEnergyService energy;
    private final Map<IGridNode, ITwinStarHost> hosts = new LinkedHashMap<>();
    private final Map<IGridNode, IBigStorage> bigStorages = new LinkedHashMap<>();
    private final Map<IGridNode, IMachineAccess> machineAccess = new LinkedHashMap<>();

    /** The machine access interfaces on the grid (each as the object its block or part is), for the list of what machines use up. */
    public List<IMachineAccess> accessOwners() {
        return new ArrayList<>(machineAccess.values());
    }

    /** Every machine the grid's interfaces stand for: an interface may stand for several ({@link IMachineAccess#accessPoints}). */
    private List<IMachineAccess> allAccess() {
        List<IMachineAccess> all = new ArrayList<>();
        for (IMachineAccess owner : machineAccess.values())
            all.addAll(owner.accessPoints());
        return all;
    }
    private final TwinStarCpu idleCpu = new TwinStarCpu(this, null, null);
    private final List<TwinStarCpu> workingCpus = new ArrayList<>();

    private final ItemIndex index = new ItemIndex();
    private final PatternCompiler compiler = new PatternCompiler(index);
    private final TheoryLayer theory = new TheoryLayer();     // engine thread only
    private final HubState hub = new HubState();               // server thread only
    private Map<IPatternDetails, Compiled> compiled = new HashMap<>();
    /** pattern changes waiting for the engine thread (one flush applies all of them) */
    private final ConcurrentLinkedQueue<Runnable> pendingPatternChanges = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private long patternsSeenTick = Long.MIN_VALUE;
    private int ticks;

    // what the engine thread did last, read by /twinstar status on the server thread (never the
    // theory layer itself: it is locked while it rebuilds)
    private volatile @Nullable Snapshot lastSnapshot;
    private volatile TheoryLayer.@Nullable FlushStats lastFlush;
    private volatile String lastFlushWhy = "";
    private volatile long lastFlushAt;
    private volatile String lastPlan = "";
    private volatile long lastPlanAt;

    /** {@code variants}: for a pattern with materials whose NBT does not matter, which stacks of them the network had; else 0. */
    private record Compiled(int[] ids, int priority, boolean hubInternal, int variants) {
    }

    /** When the stacks behind materials whose NBT does not matter were last looked at (nanos). */
    private long variantsCheckedAt;
    /** per item the stacks of it the network has, for this sync (null outside one) */
    private @Nullable Map<net.minecraft.world.item.Item, List<appeng.api.stacks.AEItemKey>> variantIndex;

    /** The stacks of {@code key}'s item in network storage (any NBT), read once per sync. */
    private List<appeng.api.stacks.AEItemKey> variantsOf(appeng.api.stacks.AEItemKey key) {
        if (variantIndex == null) {
            variantIndex = new HashMap<>();
            for (var e : grid.getStorageService().getCachedInventory())
                if (e.getKey() instanceof appeng.api.stacks.AEItemKey k && e.getLongValue() > 0)
                    variantIndex.computeIfAbsent(k.getItem(), i -> new ArrayList<>()).add(k);
        }
        return variantIndex.getOrDefault(key.getItem(), List.of());
    }

    /** per item the stacks of it that patterns make, for this sync (null outside one) */
    private @Nullable Map<net.minecraft.world.item.Item, List<appeng.api.stacks.AEItemKey>> craftableIndex;

    /** The stacks of {@code key}'s item that patterns on the network make (any NBT), read once per sync. */
    private List<appeng.api.stacks.AEItemKey> craftableOf(appeng.api.stacks.AEItemKey key) {
        if (craftableIndex == null) {
            craftableIndex = new HashMap<>();
            NetworkCraftingProviders providers = ((CraftingServiceAccessor) grid.getCraftingService()).twinstar$getCraftingProviders();
            for (AEKey k : providers.getCraftableKeys())
                if (k instanceof appeng.api.stacks.AEItemKey i)
                    craftableIndex.computeIfAbsent(i.getItem(), x -> new ArrayList<>()).add(i);
        }
        return craftableIndex.getOrDefault(key.getItem(), List.of());
    }

    /** Patterns naming a crafting table or the like, and whether each is that block's real recipe. */
    private final Map<IPatternDetails, Boolean> workstationChecked = new java.util.WeakHashMap<>();

    private boolean isWorkstationRecipe(IPatternDetails details, net.minecraft.world.item.Item machine) {
        return workstationChecked.computeIfAbsent(details, d -> {
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null)
                return false;
            boolean ok = com.lumina.twinstar.algorithm.ae2.WorkstationRecipes.matches(machine, d,
                    server.getRecipeManager(), server.registryAccess());
            if (!ok)
                Twinstar.LOGGER.info("Twin-star engine leaves out a pattern that is no recipe of {}: {} → {}",
                        machine, java.util.Arrays.toString(d.getInputs()), d.getOutputs());
            return ok;
        });
    }

    /** Which stacks the materials of {@code details} whose NBT does not matter have now; 0 when it has none. */
    private int variantsHash(IPatternDetails details) {
        var ignore = details.getDefinition().get(com.lumina.twinstar.storage.TwinstarContent.PATTERN_IGNORE_NBT.get());
        if (ignore == null || ignore.inputs().isEmpty())
            return 0;
        int h = 1;
        for (AEKey k : ignore.inputs())
            if (k instanceof appeng.api.stacks.AEItemKey item)
                for (appeng.api.stacks.AEItemKey v : variantsOf(item))
                    if (com.lumina.twinstar.algorithm.ae2.PatternIgnoreNbt.sameIgnoringNbt(v, item))
                        h = h * 31 + v.hashCode();
        for (AEKey k : ignore.inputs())
            if (k instanceof appeng.api.stacks.AEItemKey item)
                for (appeng.api.stacks.AEItemKey v : craftableOf(item))
                    if (com.lumina.twinstar.algorithm.ae2.PatternIgnoreNbt.sameIgnoringNbt(v, item))
                        h = h * 17 + v.hashCode();
        return h == 0 ? 1 : h;
    }

    public TwinStarService(IGrid grid, IEnergyService energy) {
        this.grid = grid;
        this.energy = energy;
        hub.setDebugLog(line -> {
            if (EngineLog.enabled())
                Twinstar.LOGGER.info(line);
        });
        theory.setUnits(units);
        hub.setUsage(com.lumina.twinstar.usage.UsageStore::recordHub);
        hub.setOnDone(this::tellDone);
        LIVE.add(this);
    }

    /** How amounts count against the black-hole capacity: AE2's amount per byte of each key type. */
    private final com.lumina.twinstar.algorithm.model.ByteUnits units = id -> {
        AEKey key = index.keyOf(id);
        return key == null ? 8 : key.getType().getAmountPerByte();
    };

    // ───────────────────────────── jobs saved with the hub ─────────────────────────────

    /** jobs handed over by a hub joining the grid, put back once the hub works (its level and the patterns are there) */
    private @Nullable CompoundTag pendingJobs;

    /** The host node the jobs belong to: the first hub on the grid. */
    private @Nullable IGridNode jobsNode() {
        return hosts.isEmpty() ? null : hosts.keySet().iterator().next();
    }

    /**
     * The jobs to save with {@code host}'s block: null when they are not this hub's or there are none.
     * The block saves them itself (AE2 drops a node's service data when the chunk unloads first).
     */
    public @Nullable CompoundTag jobsFor(ITwinStarHost host) {
        IGridNode node = jobsNode();
        if (node == null || hosts.get(node) != host)
            return null;
        if (pendingJobs != null)
            return pendingJobs.copy();      // not put back yet: keep it as it was
        return saveJobs(node);
    }

    private @Nullable CompoundTag saveJobs(IGridNode node) {
        if (!hub.holdsAnything() || node.getLevel() == null)
            return null;
        try {
            return JobStore.save(hub, index, node.getLevel().registryAccess(), System.nanoTime(), job -> {
                CompoundTag extra = new CompoundTag();
                for (TwinStarCpu cpu : workingCpus)
                    if (cpu.getJob() == job && cpu.getLink() != null) {
                        CompoundTag link = new CompoundTag();
                        cpu.getLink().writeToNBT(link);
                        extra.put("link", link);
                    }
                return extra;
            });
        } catch (RuntimeException e) {
            Twinstar.LOGGER.error("Twin-star engine could not save its jobs", e);
            return null;
        }
    }

    /**
     * Puts saved jobs back once the hub's node is on a powered grid, even if the hub cannot work yet
     * (no units): AE2 drops a requester's link when it finds no CPU for 3 s, and the requester then
     * orders again. Not before: a hexagram hub is alone on its own grid until it links its ports.
     * Their CPUs and crafting links come back with them.
     */
    private void restoreJobs() {
        CompoundTag tag = pendingJobs;
        IGridNode node = jobsNode();
        if (tag == null || node == null || !node.isActive() || node.getLevel() == null)
            return;
        pendingJobs = null;
        CraftingService crafting = (CraftingService) grid.getCraftingService();
        List<JobStore.Loaded> loaded = loadJobs(hub, tag, node.getLevel());
        for (JobStore.Loaded l : loaded) {
            TwinStarCpu cpu = new TwinStarCpu(this, l.job(), null);
            if (l.extra().contains("link")) {
                CraftingLink link = new CraftingLink(l.extra().getCompound("link"), cpu);
                cpu.setLink(link);
                crafting.addLink(link);
                if (!link.isStandalone())
                    l.job().setFinalSink((key, amount) -> link.insert(key, amount, Actionable.MODULATE));
            }
            workingCpus.add(cpu);
        }
        if (!loaded.isEmpty())
            Twinstar.LOGGER.info("[雙星] 還原了 {} 個工作", loaded.size());
    }

    private List<JobStore.Loaded> loadJobs(HubState into, CompoundTag tag, net.minecraft.server.level.ServerLevel level) {
        return JobStore.load(into, tag, index, level.registryAccess(), level, compiler::newPatternId, units,
                System.nanoTime(), Twinstar.LOGGER::warn);
    }

    /**
     * The hub block is broken (not unloaded): every job ends and what it holds goes back to network
     * storage; items that do not fit drop with the block, anything else that does not fit is lost.
     * Jobs not put back yet count too: they are read back only to give back what they hold.
     */
    public void hostBroken(ITwinStarHost host, List<net.minecraft.world.item.ItemStack> drops) {
        IGridNode node = null;
        for (Map.Entry<IGridNode, ITwinStarHost> e : hosts.entrySet())
            if (e.getValue() == host)
                node = e.getKey();
        if (node == null)
            return;
        boolean first = node == jobsNode();
        CompoundTag saved = host.takeParkedJobs();
        if (first && pendingJobs != null) {
            saved = pendingJobs;
            pendingJobs = null;
        }
        if (!first && saved == null)
            return;
        // a hub that is not the first has its own jobs only: they must not touch the running ones
        HubState target = first ? hub : new HubState();
        if (saved != null && node.getLevel() != null)
            loadJobs(target, saved, node.getLevel());
        target.breakDown(env(), (key, amount) -> {
            if (!(key instanceof appeng.api.stacks.AEItemKey item))
                return;
            int max = Math.max(1, item.getMaxStackSize());
            BigInteger left = amount.bigValue();
            // an astronomical remainder cannot become item entities: at most this many stacks
            for (int i = 0; i < MAX_DROPPED_STACKS && left.signum() > 0; i++) {
                int n = left.min(BigInteger.valueOf(max)).intValueExact();
                drops.add(item.toStack(n));
                left = left.subtract(BigInteger.valueOf(n));
            }
        });
        if (!first)
            return;
        for (TwinStarCpu cpu : workingCpus)
            if (cpu.getLink() != null)
                cpu.getLink().cancel();
        workingCpus.clear();
    }

    private static final int MAX_DROPPED_STACKS = 4096;

    // ───────────────────────────── grid lifecycle ─────────────────────────────

    @Override
    public void addNode(IGridNode node, @Nullable CompoundTag savedData) {
        if (node.getOwner() instanceof ITwinStarHost host) {
            hosts.put(node, host);
            // jobs left with the hub when it last left a grid (a restart, a chunk reload, a split)
            CompoundTag parked = host.takeParkedJobs();
            if (parked != null) {
                if (node == jobsNode() && !hub.holdsAnything() && pendingJobs == null)
                    pendingJobs = parked;
                else
                    host.setParkedJobs(parked);     // a second hub on the grid: its jobs wait with it
            }
        }
        if (node.getOwner() instanceof IBigStorage big)
            bigStorages.put(node, big);
        if (node.getOwner() instanceof IMachineAccess access)
            machineAccess.put(node, access);
    }

    @Override
    public void removeNode(IGridNode node) {
        if (node == jobsNode() && hosts.get(node) != null) {
            // the hub leaves the grid (chunk unloaded, network split): its jobs go with its block
            hosts.get(node).setParkedJobs(pendingJobs != null ? pendingJobs : saveJobs(node));
            hub.forgetAll();
            workingCpus.clear();
            pendingJobs = null;
        }
        hosts.remove(node);
        bigStorages.remove(node);
        machineAccess.remove(node);
    }

    @Override
    public void onServerEndTick() {
        if (pendingJobs != null)
            restoreJobs();
        if (hasHub())
            tickHub();
        // what the hub holds changes all the time: a save must never find its chunk unchanged,
        // or an older state comes back (items lost, or delivered twice). Once more when it empties.
        boolean holds = hub.holdsAnything();
        IGridNode node = jobsNode();
        if (node != null && (holds || heldLastTick))
            hosts.get(node).markJobsChanged();
        heldLastTick = holds;
    }

    private boolean heldLastTick;

    private void tickHub() {
        syncPatterns();
        if (++ticks % STOCK_SYNC_TICKS == 0)
            syncStock();
        hub.hubTick(env());
        workingCpus.removeIf(cpu -> {
            Job job = cpu.getJob();
            if (job == null || !job.isFinished())
                return false;
            if (!job.isCancelled() && cpu.getLink() != null)
                cpu.getLink().markDone();
            else if (job.isTimedOut()) {
                if (cpu.getLink() != null)
                    cpu.getLink().cancel();
                tellTimedOut(job);
            }
            EngineLog.jobEnded(job, index);
            return true;
        });
    }

    // ───────────────────────────── theory layer ─────────────────────────────

    /**
     * Follows AE2's pattern registry: new or changed patterns are compiled here, the theory layer
     * gets them on the engine thread. A pattern's priority is the highest priority of the providers
     * offering it; it is hub-internal when the hub itself provides it.
     */
    private void syncPatterns() {
        NetworkCraftingProviders providers = ((CraftingServiceAccessor) grid.getCraftingService()).twinstar$getCraftingProviders();
        long modified = providers.getLastModifiedOnTick();
        // patterns with materials whose NBT does not matter take the stacks the network has: looked at again once a second
        boolean variantsDue = compiled.values().stream().anyMatch(c -> c.variants != 0)
                && System.nanoTime() - variantsCheckedAt > 1_000_000_000L;
        if (modified == patternsSeenTick && !variantsDue)
            return;
        patternsSeenTick = modified;
        if (variantsDue)
            variantsCheckedAt = System.nanoTime();
        variantIndex = null;
        craftableIndex = null;
        compiler.setVariants(this::variantsOf, this::craftableOf);
        compiler.setWorkstationCheck(this::isWorkstationRecipe);
        compiler.setVirtualKeys(com.lumina.twinstar.algorithm.ae2.VirtualKeys::isVirtual);

        Map<IPatternDetails, Compiled> now = new HashMap<>();
        List<Pattern> add = new ArrayList<>();
        List<Integer> remove = new ArrayList<>();
        Set<IPatternDetails> seen = new HashSet<>();
        for (AEKey key : providers.getCraftableKeys()) {
            for (IPatternDetails details : providers.getCraftingFor(key)) {
                if (!seen.add(details))
                    continue;
                int priority = Integer.MIN_VALUE;
                boolean internal = false;
                for (ICraftingProvider medium : providers.getMediums(details)) {
                    priority = Math.max(priority, medium.getPatternPriority());
                    internal |= medium instanceof ITwinStarHost;
                }
                if (priority == Integer.MIN_VALUE)
                    priority = 0;
                Compiled old = compiled.get(details);
                int variants = variantsHash(details);
                if (old != null && old.priority == priority && old.hubInternal == internal && old.variants == variants) {
                    now.put(details, old);
                    continue;
                }
                if (old != null)
                    for (int id : old.ids)
                        remove.add(id);
                try {
                    List<Pattern> patterns = compiler.compile(details, priority, internal);
                    add.addAll(patterns);
                    now.put(details, new Compiled(patterns.stream().mapToInt(p -> p.id).toArray(), priority, internal, variants));
                } catch (RuntimeException e) {
                    Twinstar.LOGGER.warn("Twin-star engine skips a pattern it cannot read: {}", details, e);
                }
            }
        }
        for (Map.Entry<IPatternDetails, Compiled> e : compiled.entrySet())
            if (!now.containsKey(e.getKey()))
                for (int id : e.getValue().ids)
                    remove.add(id);
        compiled = now;
        if (!add.isEmpty() || !remove.isEmpty()) {
            EngineLog.patternsSynced(add, remove, now.size(), index);
            pendingPatternChanges.add(() -> {
                remove.forEach(theory::removePattern);
                add.forEach(theory::addPattern);
            });
            // patterns can change every tick (a generator filling the hub): one flush takes all the
            // changes that piled up meanwhile, instead of one queued flush per tick
            if (flushScheduled.compareAndSet(false, true))
                ENGINE.execute(() -> {
                    flushScheduled.set(false);
                    int batches = applyPatternChanges();
                    flushed(theory.flush(), batches > 1 ? "樣板變動（合併 " + batches + " 批）" : "樣板變動");
                });
        }
    }

    /** Engine thread: after each flush (log, and what /twinstar status shows). */
    private Snapshot flushed(Snapshot snap, String why) {
        TheoryLayer.FlushStats st = theory.lastFlushStats();
        EngineLog.theoryFlushed(st, snap, index, why);
        if (st.rebuilt() || st.processed() > 0 || lastSnapshot == null) {
            lastFlush = st;
            lastFlushWhy = why;
            lastFlushAt = System.currentTimeMillis();
        }
        lastSnapshot = snap;
        return snap;
    }

    private void planned(AEKey what, Amount amount, String outcome, long nanos) {
        lastPlan = String.format("%s ×%s：%s，%.1f ms", what.getDisplayName().getString(), EngineLog.amount(amount), outcome, nanos / 1e6);
        lastPlanAt = System.currentTimeMillis();
    }

    /** Engine thread: applies the pattern changes not applied yet; returns how many batches. */
    private int applyPatternChanges() {
        int n = 0;
        for (Runnable change; (change = pendingPatternChanges.poll()) != null; n++)
            change.run();
        return n;
    }

    private void syncStock() {
        StockSnapshot stock = StockSnapshotter.take(store(), index);
        ENGINE.execute(() -> {
            theory.applyStock(stock);
            flushed(theory.flush(), "庫存級數變動");
        });
    }

    // ───────────────────────────── planning ─────────────────────────────

    /**
     * AE2's beginCraftingCalculation on a grid with a hub: stock is read here (server thread), the
     * plan is made on the engine thread. CRAFT_LESS = make as many as possible.
     */
    public Future<ICraftingPlan> beginCalculation(AEKey what, long amount, CalculationStrategy strategy) {
        return beginCalculation(what, Amount.of(amount), strategy);
    }

    /** Same, for amounts beyond a long (orders sent by our own packet; AE2's API stops at long). */
    public Future<ICraftingPlan> beginCalculation(AEKey what, Amount amount, CalculationStrategy strategy) {
        return beginCalculation(what, amount, strategy, Map.of());
    }

    /** Same, with the player's marks for this order (plan screen: 保留 / 隨便用). */
    public Future<ICraftingPlan> beginCalculation(AEKey what, Amount amount, CalculationStrategy strategy,
                                                  Map<AEKey, ItemMark> marks) {
        return beginCalculation(what, amount, strategy, marks, Map.of());
    }

    /**
     * @param keep for {@link ItemMark#KEEP} items, how much of the network's stock to keep out of the
     *             plan; an item without an amount here is kept whole
     */
    public Future<ICraftingPlan> beginCalculation(AEKey what, Amount amount, CalculationStrategy strategy,
                                                  Map<AEKey, ItemMark> marks, Map<AEKey, BigInteger> keep) {
        syncPatterns();
        StockSnapshot taken = StockSnapshotter.take(store(), index);
        int target = index.idOf(what);
        Overlay marked = new Overlay();
        Map<Integer, Amount> keepSome = new HashMap<>();
        Set<Integer> keepAll = new HashSet<>();
        marks.forEach((key, mark) -> {
            int id = index.idOf(key);
            switch (mark) {
                case AVOID -> marked.markReserved(id);
                case FREE -> marked.markFree(id);
                case AUTO -> marked.markAuto(id);
                case KEEP -> {
                    BigInteger n = keep.get(key);
                    if (n == null)
                        keepAll.add(id);
                    else if (n.signum() > 0)
                        keepSome.put(id, Amount.of(n));
                }
            }
        });
        // the theory layer's prices follow the real stock; only this plan sees the kept amounts gone
        StockSnapshot stock = taken.withKept(keepSome, keepAll);
        Amount free = freeCapacity();
        OrderMode mode = strategy == CalculationStrategy.CRAFT_LESS ? OrderMode.BEST_EFFORT : OrderMode.EXACT;
        Set<Integer> machinesHere = machinesOnGrid();
        return ENGINE.submit(() -> {
            applyPatternChanges();
            theory.applyStock(taken);
            Snapshot snap = flushed(theory.flush(), "下單前");
            // the time limit is for planning: bringing the theory layer up to date (a full rebuild
            // after many pattern changes) can take longer than that on a big pack and is not planning
            Deadline deadline = Deadline.startingNow();
            long t0 = System.nanoTime();
            Overlay restricted = withoutMissingMachines(snap, machinesHere, marked);
            OrderResult r = Planner.order(snap, stock, target, amount, mode, free, restricted, deadline);
            if (!restricted.disabled.isEmpty() && r instanceof OrderResult.Accepted a && !a.plan().isComplete())
                r = bestWithMachinesMarked(snap, stock, target, amount, mode, free, restricted, marked, r);
            long took = System.nanoTime() - t0;
            planStats.record(took, deadline.softPassed(), !(r instanceof OrderResult.Accepted) && !(r instanceof OrderResult.Rejected));
            if (r instanceof OrderResult.Accepted a) {
                String outcome = a.plan().isComplete() ? "可以完成"
                        : !a.plan().missingMachines.isEmpty() ? "缺機器" + (a.plan().missing.isEmpty() ? "" : "、缺料") : "缺料";
                EngineLog.planned(a.plan(), snap, index, mode, outcome, took);
                planned(what, amount, outcome, took);
                return new PlanWrapper(a.plan(), snap, index);
            }
            if (r instanceof OrderResult.Rejected rej) {
                EngineLog.planned(rej.plan(), snap, index, mode,
                        "超過黑洞剩餘容量（最多可做 " + EngineLog.amount(rej.suggested()) + "）", took);
                planned(what, amount, "超過黑洞剩餘容量", took);
                return new PlanWrapper(rej.plan(), snap, index);     // AE2 then reports "CPU too small"
            }
            planned(what, amount, "超過 1 秒，計算逾時", took);
            if (EngineLog.enabled())
                Twinstar.LOGGER.info("[雙星] 規劃 {} ×{}：超過 1 秒，計算逾時", EngineLog.item(index, target), EngineLog.amount(amount));
            throw new PlanTimedOutException();
        });
    }

    /** The plan took longer than the hard limit (the future fails with this as its cause). */
    public static final class PlanTimedOutException extends IllegalStateException {
        PlanTimedOutException() {
            super("Twin-star engine: crafting calculation timed out");
        }
    }

    /** What the order screen shows about an item (server thread). */
    public record OrderInfo(BigInteger stock, boolean infinite, int routes, boolean upToDate) {
    }

    public OrderInfo orderInfo(AEKey key) {
        long reported = grid.getStorageService().getCachedInventory().get(key);
        NetworkStore store = store();
        boolean infinite = reported >= Integer.MAX_VALUE && store.isInfinite(key);
        Snapshot snap = lastSnapshot;
        int id = index.find(key);
        int routes = snap != null && id >= 0 ? snap.producers(id).length : 0;
        return new OrderInfo(store.exactStock(key, reported), infinite, routes, isUpToDate());
    }

    /** An infinite source of {@code key} on the network (server thread). */
    public boolean isInfinite(AEKey key) {
        return grid.getStorageService().getCachedInventory().get(key) >= Integer.MAX_VALUE && store().isInfinite(key);
    }

    /** Exact amount of {@code key} on the network (server thread). */
    public BigInteger stockOf(AEKey key) {
        return store().exactStock(key, grid.getStorageService().getCachedInventory().get(key));
    }

    /** Nothing waiting on the engine thread: the theory layer has every change. */
    public boolean isUpToDate() {
        return ENGINE.getActiveCount() == 0 && ENGINE.getQueue().isEmpty() && pendingPatternChanges.isEmpty();
    }

    /** Free black-hole capacity; null = unlimited. */
    public @Nullable BigInteger freeCapacityExact() {
        BigInteger cap = getCapacity();
        return cap == null ? null : hub.freeCapacity(Amount.of(cap)).bigValue();
    }

    /** Networks with a hub, for /twinstar status. */
    public static List<TwinStarService> withHub() {
        synchronized (LIVE) {
            return LIVE.stream().filter(TwinStarService::hasHub).toList();
        }
    }

    /** Server thread: what the engine is doing for this network, in plain lines (never waits for it). */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        String where = getHost() instanceof net.minecraft.world.level.block.entity.BlockEntity be && be.getLevel() != null
                ? be.getLevel().dimension().location() + " " + be.getBlockPos().toShortString() : "?";
        Snapshot snap = lastSnapshot;
        if (snap == null) {
            out.add("[雙星] 中樞 " + where + "：理論層還沒建好（引擎還在處理第一次更新）");
        } else {
            int patterns = 0;
            for (int p = 0; p < snap.patternCount(); p++)
                if (snap.pattern(p) != null)
                    patterns++;
            out.add("[雙星] 中樞 " + where + "：理論層 v" + snap.version() + "（物品 " + snap.itemCount() + "、樣板 " + patterns
                    + "、策略環 " + snap.sccCount() + "）");
        }
        TheoryLayer.FlushStats st = lastFlush;
        if (st != null) {
            String kind = st.rebuilt() ? "全部重建" : "增量更新 " + st.processed() + " 個元件";
            out.add(String.format("  上次更新：%s，%.1f ms（%s，%d 秒前）", kind, st.nanos() / 1e6, lastFlushWhy, (now - lastFlushAt) / 1000));
        }
        int queued = ENGINE.getQueue().size();
        boolean busy = ENGINE.getActiveCount() > 0;
        int pending = pendingPatternChanges.size();
        out.add("  引擎：" + (busy ? "處理中" : "閒置") + (queued > 0 ? "，排隊 " + queued + " 項" : "")
                + (pending > 0 ? "；還有 " + pending + " 批樣板變動沒套用" : "；樣板已全部套用")
                + (busy || queued > 0 || pending > 0 ? "（還沒更新完）" : "（已更新完畢）"));
        if (!lastPlan.isEmpty())
            out.add("  上次規劃：" + lastPlan + "（" + (now - lastPlanAt) / 1000 + " 秒前）");
        out.add("  執行中的工作 " + hub.jobs().size() + " 個；詳細日誌：" + (EngineLog.enabled() ? "開（logs/latest.log）" : "關"));
        return out;
    }

    /**
     * The theory layer's state for the theory viewer (server thread, never waits): its version and
     * size, whether the engine has caught up with every change, and the last update.
     */
    public record TheoryStatus(long version, int items, int patterns, int loops, boolean upToDate, boolean busy,
                               int pendingBatches, int queued, long flushNanos, boolean rebuilt, long flushAgoMillis,
                               TheoryReport report) {
        public static final TheoryStatus NONE = new TheoryStatus(-1, 0, 0, 0, false, false, 0, 0, 0, false, 0, TheoryReport.NONE);
    }

    // ── memory and speed of our own data (what the viewer and the CPU screen show) ──

    private final PlanStats planStats = new PlanStats();
    private final Trend trend = new Trend();
    private volatile Snapshot.@Nullable Memory lastMemory;
    private final java.util.concurrent.atomic.AtomicLong memoryAskedFor = new java.util.concurrent.atomic.AtomicLong(-1);
    private volatile HubReport hubCache = HubReport.NONE;
    private volatile long hubCacheJob = Long.MIN_VALUE, hubCacheAt;

    /**
     * Server thread, never waits: the theory layer's size and speed. The size of a version is worked
     * out on the engine thread the first time a viewer asks (a pack has tens of thousands of patterns), so
     * the viewer sees the version before it, for a second or two, until it is done.
     */
    private TheoryReport theoryReport(Snapshot snap) {
        Snapshot.Memory mem = snap.memoryIfReady();
        if (mem == null) {
            if (memoryAskedFor.getAndSet(snap.version()) != snap.version())
                ENGINE.execute(() -> {
                    Snapshot.Memory m = snap.memory();
                    trend.add(snap.itemCount() + (long) snap.patternCount(), m.total());
                    lastMemory = m;
                });
            mem = lastMemory;
        } else {
            lastMemory = mem;
        }
        TheoryLayer.FlushPerf perf = theory.perf();
        PlanStats.Summary plans = planStats.summary();
        int patterns = 0;
        for (int p = 0; p < snap.patternCount(); p++)
            if (snap.pattern(p) != null)
                patterns++;
        return new TheoryReport(mem != null, snap.itemCount(), patterns, snap.sccCount(),
                mem == null ? 0 : mem.patterns(), mem == null ? 0 : mem.items(), mem == null ? 0 : mem.bom(), mem == null ? 0 : mem.other(),
                perf.flushes(), perf.rebuilds(), perf.lastNanos(), perf.lastRebuilt(), perf.lastRebuildNanos(), perf.incrMaxNanos(), perf.lastAlloc(),
                plans.plans(), plans.lastNanos(), plans.maxNanos(), plans.softHits(), plans.hardHits(), trend.growthPermille());
    }

    /**
     * Server thread: what a CPU screen shows about the hub memory and speed; {@code job} is the one the
     * screen's CPU entry stands for (null for the idle entry). Worked out at most once a second per job
     * (it walks the data of every job), however many screens look.
     */
    public HubReport hubReport(@Nullable Job job) {
        long now = System.nanoTime();
        long key = job == null ? -1 : job.id;
        if (key == hubCacheJob && now - hubCacheAt < 1_000_000_000L)
            return hubCache;
        Snapshot snap = lastSnapshot;
        Snapshot.Memory mem = snap == null ? null : snap.memoryIfReady();
        HubReport r = hub.report(job, snap, mem == null ? 0 : mem.total());
        hubCache = r;
        hubCacheJob = key;
        hubCacheAt = now;
        return r;
    }

    public TheoryStatus theoryStatus() {
        Snapshot snap = lastSnapshot;
        if (snap == null)
            return TheoryStatus.NONE;
        int patterns = 0;
        for (int p = 0; p < snap.patternCount(); p++)
            if (snap.pattern(p) != null)
                patterns++;
        TheoryLayer.FlushStats st = lastFlush;
        return new TheoryStatus(snap.version(), snap.itemCount(), patterns, snap.sccCount(), isUpToDate(),
                ENGINE.getActiveCount() > 0, pendingPatternChanges.size(), ENGINE.getQueue().size(),
                st != null ? st.nanos() : 0, st != null && st.rebuilt(), st != null ? System.currentTimeMillis() - lastFlushAt : 0,
                theoryReport(snap));
    }

    /** Free black-hole capacity, any size; an unlimited host gives an amount no plan reaches. */
    private Amount freeCapacity() {
        if (getHost() == null)
            return Amount.ZERO;
        BigInteger cap = getCapacity();
        if (cap == null)
            return UNLIMITED_CAPACITY;
        return hub.freeCapacity(Amount.of(cap));
    }

    /**
     * Stands for "unlimited" where an amount is needed: 2^(2^16) ≈ 10^19728, beyond any order
     * (at most {@link com.lumina.corelib.util.AmountParser#MAX_EXTERNAL}) times what its intermediates multiply it by.
     */
    private static final Amount UNLIMITED_CAPACITY = Amount.of(BigInteger.ONE.shiftLeft(1 << 16));

    /** A job is done: the player who ordered it (when online) gets a notice at the top left of the screen. */
    private void tellDone(Job job) {
        if (job.player == null)
            return;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        net.minecraft.server.level.ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(job.player);
        AEKey key = index.keyOf(job.plan.target);
        if (p == null || key == null)
            return;
        long seconds = Math.max(0, (System.nanoTime() - job.startNanos()) / 1_000_000_000L);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(p,
                new com.lumina.twinstar.order.JobDonePacket(key, job.plan.amount.toString(), seconds));
    }

    /** A forced order gave up waiting for its materials: the player who ordered it (when online) is told. */
    private void tellTimedOut(Job job) {
        if (job.player == null)
            return;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        net.minecraft.server.level.ServerPlayer p = server == null ? null : server.getPlayerList().getPlayer(job.player);
        AEKey key = index.keyOf(job.plan.target);
        if (p != null && key != null)
            p.sendSystemMessage(Component.translatable("message.twinstar.forced_timeout", key.getDisplayName()));
    }

    // ───────────────────────────── submit / cancel ─────────────────────────────

    /** Only plans this engine made can run here: they contain hub patterns other CPUs cannot run. */
    public boolean isOurPlan(ICraftingPlan plan) {
        return plan instanceof PlanWrapper;
    }

    /**
     * Pulls everything into a new job (all or nothing). If the stock changed since planning, plans
     * once more with fresh stock; if that still fails, reports what is missing.
     */
    public ICraftingSubmitResult submit(ICraftingPlan plan, @Nullable ICraftingRequester requester, IActionSource source) {
        if (!(plan instanceof PlanWrapper pw))
            return wrongEngine(source, false);
        if (pw.simulation())
            return CraftingSubmitResult.INCOMPLETE_PLAN;
        if (pw.usage().compareTo(freeCapacity()) > 0)
            return CraftingSubmitResult.CPU_TOO_SMALL;

        ExecutionEnv env = env();
        Job job = hub.submit(pw.plan(), pw.snapshot(), env);
        if (job == null) {
            PlanWrapper again;
            try {
                GenericStack out = pw.finalOutput();     // its amount saturates at long: use the plan's exact one
                again = (PlanWrapper) beginCalculation(out.what(), pw.plan().amount, CalculationStrategy.REPORT_MISSING_ITEMS)
                        .get(REPLAN_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return CraftingSubmitResult.INCOMPLETE_PLAN;
            }
            if (again.simulation() || again.usage().compareTo(freeCapacity()) > 0)
                return firstMissing(again);
            job = hub.submit(again.plan(), again.snapshot(), env);
            if (job == null)
                return firstMissing(again);
        }

        return startJob(job, requester, source);
    }

    /**
     * 強制下單: starts the plan although it lacks materials (or machines). What the network has is taken now and what can be
     * made is made; what is lacking is taken from network storage as it appears and the job waits for it. It gives up
     * (cancelled, everything returned) after a long time without progress, or when the player cancels it. Refused as
     * CPU_BUSY when too many forced orders already wait.
     */
    public ICraftingSubmitResult submitForced(ICraftingPlan plan, @Nullable ICraftingRequester requester, IActionSource source) {
        if (!(plan instanceof PlanWrapper pw))
            return wrongEngine(source, false);
        if (pw.usage().compareTo(freeCapacity()) > 0)
            return CraftingSubmitResult.CPU_TOO_SMALL;
        if (hub.forcedWaiting() >= ExecutionSettings.maxForcedJobs())
            return CraftingSubmitResult.CPU_BUSY;
        Job job = hub.submitForced(pw.plan(), pw.snapshot(), env());
        if (job == null)
            return CraftingSubmitResult.INCOMPLETE_PLAN;
        return startJob(job, requester, source);
    }

    private ICraftingSubmitResult startJob(Job job, @Nullable ICraftingRequester requester, IActionSource source) {
        UUID craftId = UUID.randomUUID();
        if (source.player().isPresent())
            job.player = source.player().get().getUUID();
        TwinStarCpu cpu = new TwinStarCpu(this, job, null);
        CraftingLink link = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, requester == null, false), cpu);
        cpu.setLink(link);
        workingCpus.add(cpu);
        EngineLog.jobStarted(job, index);
        if (requester == null)
            return CraftingSubmitResult.successful(null);
        // finished products go to the requester first, like AE2's own CPUs
        job.setFinalSink((key, amount) -> link.insert(key, amount, Actionable.MODULATE));
        CraftingLink reqLink = new CraftingLink(CraftingCpuHelper.generateLinkData(craftId, false, true), requester);
        CraftingService service = (CraftingService) grid.getCraftingService();
        service.addLink(link);
        service.addLink(reqLink);
        return CraftingSubmitResult.successful(reqLink);
    }

    /**
     * A plan and a CPU from different engines (ours plan on an AE2 CPU, or AE2's plan on the hub):
     * refused, never crashed on. AE2's "no suitable CPU" needs its detail object, or syncing the
     * error to the client throws; the player gets the real reason in chat.
     *
     * @param ourPlan true = our plan sent to an AE2 CPU; false = AE2's plan sent to the hub
     */
    public static ICraftingSubmitResult wrongEngine(IActionSource source, boolean ourPlan) {
        source.player().ifPresent(p -> p.sendSystemMessage(Component.translatable(
                ourPlan ? "message.twinstar.plan_needs_hub" : "message.twinstar.hub_needs_own_plan")));
        return CraftingSubmitResult.noSuitableCpu(new UnsuitableCpus(0, 0, 0, 1));
    }

    private static ICraftingSubmitResult firstMissing(PlanWrapper plan) {
        var entry = plan.missingItems().getFirstEntry();
        if (entry == null)
            return CraftingSubmitResult.INCOMPLETE_PLAN;
        return CraftingSubmitResult.missingIngredient(new GenericStack(entry.getKey(), entry.getLongValue()));
    }

    /** Inventory goes back as it is, expectations are dropped, capacity is freed. */
    public void cancel(TwinStarCpu cpu) {
        Job job = cpu.getJob();
        // a CPU no longer listed (a screen still open on it): its job left with the hub's block,
        // which keeps what it holds; giving the items back here would double them
        if (job == null || !workingCpus.contains(cpu))
            return;
        hub.cancel(job, env());
        if (cpu.getLink() != null)
            cpu.getLink().cancel();
        if (job.isFinished()) {
            workingCpus.remove(cpu);
            EngineLog.jobEnded(job, index);
        }
    }

    /** CPU screen button: what the job still waits for is taken from network storage. */
    public void pullFromNetwork(TwinStarCpu cpu) {
        Job job = cpu.getJob();
        if (job != null && workingCpus.contains(cpu))
            hub.pullFromNetwork(job, env());
    }

    // ───────────────────────────── returns (AE2 hooks) ─────────────────────────────

    /** AE2's insertIntoCpus: keep what our jobs still expect; returns the amount kept. */
    public long insertIntoCpus(AEKey what, long amount, Actionable mode) {
        int id = index.find(what);
        if (id < 0 || amount <= 0)
            return 0;
        if (mode == Actionable.SIMULATE)
            return Math.min(amount, hub.requestedAmount(id).saturatedLong());
        return hub.onInsert(id, Amount.of(amount), System.nanoTime()).saturatedLong();
    }

    public long getRequestedAmount(AEKey what) {
        int id = index.find(what);
        return id < 0 ? 0 : hub.requestedAmount(id).saturatedLong();
    }

    public boolean isRequestingAny() {
        return hub.isRequestingAny();
    }

    // ───────────────────────────── queries ─────────────────────────────

    private ExecutionEnv env() {
        ITwinStarHost host = getHost();
        CraftingService crafting = (CraftingService) grid.getCraftingService();
        BigInteger p = getParallelism();
        Amount parallelism = p == null ? null : Amount.of(p.max(BigInteger.ONE));
        return new ExecutionEnv(index, store(), energy, host != null ? host.getHostEnergySource() : null,
                crafting::getProviders, allAccess(), parallelism, System.nanoTime(), TICK_BUDGET_NANOS);
    }

    /**
     * The machines (item ids) the grid's working machine access interfaces stand for: the block each
     * sits on and its ghost slot. Server thread.
     */
    private Set<Integer> machinesOnGrid() {
        Set<Integer> out = new HashSet<>();
        for (IMachineAccess a : allAccess()) {
            var level = a.getMachineLevel();
            if (level == null || !a.isAccessActive() || !level.isLoaded(a.getMachinePos()))
                continue;
            net.minecraft.world.item.Item block = level.getBlockState(a.getMachinePos()).getBlock().asItem();
            if (block != net.minecraft.world.item.Items.AIR)
                out.add(index.idOf(appeng.api.stacks.AEItemKey.of(block)));
            if (a.getMachine() != null)
                out.add(index.idOf(appeng.api.stacks.AEItemKey.of(a.getMachine())));
        }
        return out;
    }

    /**
     * Nothing can be made without the machines that are missing: the best plan as if they were
     * there, with the machine patterns it uses marked by the machine they lack (the plan screen
     * shows "缺機器"; the plan cannot be started). Kept as it was when that plan lacks no machine.
     */
    private static OrderResult bestWithMachinesMarked(Snapshot snap, StockSnapshot stock, int target, Amount amount,
                                                      OrderMode mode, Amount free, Overlay restricted, Overlay marked,
                                                      OrderResult without) {
        OrderResult r = Planner.order(snap, stock, target, amount, mode, free, marked.copy(), Deadline.startingNow());
        if (!(r instanceof OrderResult.Accepted a))
            return without;
        Map<Integer, Integer> lacking = new LinkedHashMap<>();
        for (int p : a.plan().patternTimes.keys())
            if (restricted.disabled.get(p))
                lacking.put(p, snap.pattern(p).machine);
        return lacking.isEmpty() ? without : new OrderResult.Accepted(a.plan().withMissingMachines(lacking));
    }

    /** An order may not use a machine pattern whose machine has no interface on the grid; on top of the player's marks. */
    private static Overlay withoutMissingMachines(Snapshot snap, Set<Integer> machines, Overlay marked) {
        Overlay o = marked.copy();
        for (int p = 0; p < snap.patternCount(); p++) {
            Pattern pat = snap.pattern(p);
            if (pat != null && pat.where == PatternLocation.HUB_MACHINE && !machines.contains(pat.machine))
                o.disabled.set(p);
        }
        return o;
    }

    /** What the screen of a machine access interface shows about its machine. */
    public enum MachineState {
        /** not powered or not on the grid */
        INACTIVE,
        /** no hub on the grid */
        NO_HUB,
        /** no pattern on the grid is for the block it sits on or the machine set on it */
        NO_PATTERN,
        IDLE,
        /** materials of a run going in */
        FEEDING,
        /** runs in the machine whose products are not all back */
        WORKING
    }

    /**
     * {@code output}: the main output of the run fed (or the first run in it); {@code fed} /
     * {@code total}: amounts of the run fed; {@code runsIn}: runs in the machine; {@code chance}:
     * that run's pattern has outputs that come only with a probability.
     */
    public record MachineView(MachineState state, @Nullable AEKey output, long fed, long total, long runsIn, boolean chance) {
        public static MachineView of(MachineState state) {
            return new MachineView(state, null, 0, 0, 0, false);
        }
    }

    /** The machine items the hub patterns on the grid are for, read once per change of the patterns. */
    private Map<IPatternDetails, Compiled> machinesReadFrom = Map.of();
    private Set<net.minecraft.world.item.Item> patternMachines = Set.of();

    /** Per interface its sub-machines as last looked for, and when (game time). */
    private final Map<IMachineAccess, List<net.minecraft.core.BlockPos>> subsByAccess = new java.util.WeakHashMap<>();
    private final Map<IMachineAccess, Long> subsAtByAccess = new java.util.WeakHashMap<>();

    /**
     * The sub-machines around the machine of {@code access}: of the kinds the hub patterns for that
     * machine name, within its range ({@link com.lumina.twinstar.algorithm.execution.SubMachines}). For
     * the interface to power them. Looked for again once a second. Server thread.
     */
    public List<net.minecraft.core.BlockPos> subMachinesOf(IMachineAccess access) {
        net.minecraft.server.level.ServerLevel level = access.getMachineLevel();
        if (level == null || !level.isLoaded(access.getMachinePos()))
            return List.of();
        long now = level.getGameTime();
        Long at = subsAtByAccess.get(access);
        if (at != null && now - at < 20)
            return subsByAccess.getOrDefault(access, List.of());
        net.minecraft.world.item.Item block = level.getBlockState(access.getMachinePos()).getBlock().asItem();
        List<net.minecraft.world.item.Item> kinds = new ArrayList<>();
        compiled.forEach((details, c) -> {
            if (!c.hubInternal)
                return;
            net.minecraft.world.item.Item m = com.lumina.twinstar.api.PatternMachines.of(details.getDefinition());
            if (m == null || (m != block && m != access.getMachine()))
                return;
            for (net.minecraft.world.item.Item sub : com.lumina.twinstar.api.PatternMachines.subMachinesOf(details.getDefinition()))
                if (!kinds.contains(sub))
                    kinds.add(sub);
        });
        List<net.minecraft.core.BlockPos> others = new ArrayList<>();
        for (IMachineAccess a : allAccess())
            if (a != access && a.getMachineLevel() == level)
                others.add(a.getMachinePos());
        List<net.minecraft.core.BlockPos> found = com.lumina.twinstar.algorithm.execution.SubMachines.find(
                com.lumina.twinstar.algorithm.execution.Ae2Adapters.world(level), access.getMachinePos(),
                access.getSubRange(), kinds, others);
        subsByAccess.put(access, found);
        subsAtByAccess.put(access, now);
        return found;
    }

    /** For the screen of machine access interface {@code access} (server thread). */
    public MachineView machineView(IMachineAccess access) {
        if (!access.isAccessActive())
            return MachineView.of(MachineState.INACTIVE);
        if (!hasHub())
            return MachineView.of(MachineState.NO_HUB);
        HubState.MachineStatus s = hub.machineStatus(access);
        if (s == null)
            return MachineView.of(isPatternMachine(access) ? MachineState.IDLE : MachineState.NO_PATTERN);
        Pattern p = s.job().snapshot().pattern(s.pattern());
        AEKey out = null;
        boolean chance = false;
        for (int o = 0; o < p.outputCount(); o++) {
            if (out == null && !p.isByproduct(o))
                out = index.keyOf(p.outputItem(o));
            chance |= p.isChance(o);
        }
        return new MachineView(s.feeding() ? MachineState.FEEDING : MachineState.WORKING, out, s.fed(), s.total(),
                s.runsIn().saturatedLong(), chance);
    }

    /** Some hub pattern on the grid is for the block {@code access} sits on or the machine set on it. */
    private boolean isPatternMachine(IMachineAccess access) {
        if (machinesReadFrom != compiled) {
            Set<net.minecraft.world.item.Item> items = new HashSet<>();
            compiled.forEach((details, c) -> {
                if (c.hubInternal) {
                    net.minecraft.world.item.Item m = com.lumina.twinstar.api.PatternMachines.of(details.getDefinition());
                    if (m != null)
                        items.add(m);
                }
            });
            patternMachines = items;
            machinesReadFrom = compiled;
        }
        if (access.getMachine() != null && patternMachines.contains(access.getMachine()))
            return true;
        net.minecraft.server.level.ServerLevel level = access.getMachineLevel();
        return level != null && level.isLoaded(access.getMachinePos())
                && patternMachines.contains(level.getBlockState(access.getMachinePos()).getBlock().asItem());
    }

    private IActionSource actionSource() {
        ITwinStarHost host = getHost();
        return host != null ? IActionSource.ofMachine(host) : IActionSource.empty();
    }

    /** AE2's storage plus the powered big storages on the grid, blocks and disks in drives (AE2 only mounts those too). */
    private NetworkStore store() {
        List<IBigStorage> big = new ArrayList<>();
        bigStorages.forEach((node, s) -> {
            if (node.isActive())
                big.add(s);
        });
        for (Class<?> machine : grid.getMachineClasses()) {
            if (!IChestOrDrive.class.isAssignableFrom(machine))
                continue;
            for (Object drive : grid.getActiveMachines(machine)) {
                IChestOrDrive d = (IChestOrDrive) drive;
                for (int i = 0; i < d.getCellCount(); i++)
                    if (d.getOriginalCellInventory(i) instanceof IBigStorage cell)
                        big.add(cell);
            }
        }
        return new NetworkStore(grid.getStorageService().getInventory(), big, actionSource());
    }

    @Override
    public boolean hasHub() {
        return getHost() != null;
    }

    public boolean hasCpu(TwinStarCpu cpu) {
        return cpu == idleCpu || workingCpus.stream().anyMatch(c -> c == cpu);
    }

    @Override
    public @Nullable BigInteger getParallelism() {
        ITwinStarHost host = getHost();
        return host == null ? BigInteger.ZERO : host.getHostParallelism();
    }

    @Override
    public @Nullable BigInteger getCapacity() {
        ITwinStarHost host = getHost();
        return host == null ? BigInteger.ZERO : host.getHostCapacity();
    }

    /** For AE2's CPU fields (an int): unlimited = Integer.MAX_VALUE, too big = Integer.MAX_VALUE − 1 (see CpuNumbers). */
    public int getParallelismForAe2() {
        return CpuNumbers.coProcessorsForAe2(getParallelism());
    }

    /** Capacity held by running jobs (saturates at Long.MAX_VALUE). */
    public long getUsedCapacity() {
        return hub.used().saturatedLong();
    }

    /** Free capacity for AE2's CPU fields (a long): unlimited = Long.MAX_VALUE, too big = Long.MAX_VALUE − 1. */
    public long getFreeCapacity() {
        if (getHost() != null && getCapacity() == null)
            return Long.MAX_VALUE;
        return CpuNumbers.storageForAe2(freeCapacity().bigValue());
    }

    @Nullable
    public ITwinStarHost getHost() {
        for (ITwinStarHost host : hosts.values()) {
            if (host.isHostActive()) {
                return host;
            }
        }
        return null;
    }

    public ItemIndex getIndex() {
        return index;
    }

    /** The last version of the theory layer the engine published (immutable); null before the first update. */
    public @Nullable Snapshot getLastSnapshot() {
        return lastSnapshot;
    }

    /** Server thread: the network's stock exactly as the engine reads it before planning. */
    public StockSnapshot takeStock() {
        return StockSnapshotter.take(store(), index);
    }

    public IGrid getGrid() {
        return grid;
    }

    public IEnergyService getEnergyService() {
        return energy;
    }

    public TwinStarCpu getIdleCpu() {
        return idleCpu;
    }

    public List<TwinStarCpu> getWorkingCpus() {
        return workingCpus;
    }
}
