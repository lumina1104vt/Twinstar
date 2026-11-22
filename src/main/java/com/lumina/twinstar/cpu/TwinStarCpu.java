package com.lumina.twinstar.cpu;

import appeng.api.config.CpuSelectionMode;
import appeng.api.networking.crafting.CraftingJobStatus;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.CraftingLink;
import appeng.menu.me.crafting.CraftingStatus;
import appeng.menu.me.crafting.CraftingStatusEntry;
import com.lumina.twinstar.algorithm.ae2.ItemIndex;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.execution.Job;
import com.lumina.twinstar.algorithm.execution.Task;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.menu.BigStatusAmounts;
import com.lumina.twinstar.menu.CpuNumbers;
import com.lumina.twinstar.menu.BigStatusEntry;
import com.lumina.twinstar.service.TwinStarService;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/**
 * How the hub shows up in AE2's CPU list. AE2 assumes one job per CPU, so the hub is one fixed idle
 * entry plus one entry per running job; entries are never re-created while their job runs, or the
 * CPU selected in AE2's screens would jump.
 */
public final class TwinStarCpu implements ICraftingCPU {
    private final TwinStarService service;
    private final @Nullable Job job;      // null = the idle entry
    private @Nullable CraftingLink link;

    public TwinStarCpu(TwinStarService service, @Nullable Job job, @Nullable CraftingLink link) {
        this.service = service;
        this.job = job;
        this.link = link;
    }

    /** What the CPU screen shows about the hub's Java memory and speed (server thread). */
    public com.lumina.twinstar.metrics.HubReport report() {
        return service.hubReport(job);
    }

    public @Nullable Job getJob() {
        return job;
    }

    public @Nullable CraftingLink getLink() {
        return link;
    }

    public void setLink(CraftingLink link) {
        this.link = link;
    }

    @Override
    public Component getName() {
        return Component.translatable("gui.twinstar.hub_name");
    }

    @Override
    public boolean isBusy() {
        return job != null;
    }

    @Override
    public @Nullable CraftingJobStatus getJobStatus() {
        if (job == null)
            return null;
        AEKey key = service.getIndex().keyOf(job.plan.target);
        if (key == null)
            return null;
        return new CraftingJobStatus(new GenericStack(key, job.plan.amount.saturatedLong()),
                job.plan.amount.saturatedLong(), job.delivered().saturatedLong(),
                System.nanoTime() - job.startNanos());
    }

    @Override
    public void cancelJob() {
        if (job != null)
            service.cancel(this);
    }

    /** Idle entry: the free black-hole capacity. Job entry: the capacity that job still holds. */
    @Override
    public long getAvailableStorage() {
        if (job == null)
            return service.getFreeCapacity();
        return CpuNumbers.storageForAe2(job.occupancy().bigValue());
    }

    @Override
    public int getCoProcessors() {
        return service.getParallelismForAe2();
    }

    @Override
    public CpuSelectionMode getSelectionMode() {
        var host = service.getHost();
        if (host != null)
            return host.getHostWorkerMode();
        return CpuSelectionMode.ANY;
    }

    /** CPU screen pause button. */
    public void toggleSuspended() {
        if (job != null)
            job.setSuspended(!job.isSuspended());
    }

    /** CPU screen button: what the job waits for may be in network storage after the hub was down. */
    public void pullFromNetwork() {
        if (job != null)
            service.pullFromNetwork(this);
    }

    /**
     * The CPU detail screen, built straight from the job: stored = in the job's inventory, active =
     * sent to machines and not back yet, pending = still to be made by the remaining runs.
     */
    public CraftingStatus createStatus(ToLongFunction<AEKey> serialOf) {
        if (job == null)
            return CraftingStatus.EMPTY;
        ItemIndex index = service.getIndex();
        Snapshot snap = job.snapshot();
        AmountMap stored = job.inventory();
        AmountMap active = job.outstanding();
        AmountMap later = job.drawLater();
        AmountMap chanceShort = job.chanceShortages();
        // runs lost with a machine taken away: shown on the item the pattern makes
        AmountMap lost = new AmountMap();
        job.machineLosses().forEach((p, runs) -> {
            Pattern pat = snap.pattern(p);
            for (int o = 0; o < pat.outputCount(); o++)
                if (!pat.isByproduct(o)) {
                    lost.add(pat.outputItem(o), runs);
                    break;
                }
        });
        // patterns nobody offers now: shown on the item they make
        java.util.Set<Integer> waiting = new java.util.HashSet<>();
        for (int p : job.waitingPatterns()) {
            Pattern pat = snap.pattern(p);
            for (int o = 0; o < pat.outputCount(); o++)
                if (!pat.isByproduct(o))
                    waiting.add(pat.outputItem(o));
        }
        AmountMap pending = new AmountMap();
        Amount remaining = Amount.ZERO;
        for (Task t : job.tasks()) {
            if (t.remaining().signum() <= 0)
                continue;
            Pattern p = snap.pattern(t.pattern);
            for (int o = 0; o < p.outputCount(); o++) {
                if (p.isChance(o))
                    continue;   // comes with a probability: how much is not known before it runs
                Amount a = p.outputAmount(o).mul(t.remaining());
                pending.add(p.outputItem(o), a);
                remaining = remaining.add(a);
            }
        }
        Amount start = Amount.ZERO;
        for (var e : job.plan.patternTimes.entries()) {
            Pattern p = snap.pattern(e.getKey());
            for (int o = 0; o < p.outputCount(); o++)
                if (!p.isChance(o))
                    start = start.add(p.outputAmount(o).mul(e.getValue()));
        }

        TreeSet<Integer> items = new TreeSet<>();
        for (int i : stored.keys())
            items.add(i);
        for (int i : active.keys())
            items.add(i);
        for (int i : pending.keys())
            items.add(i);
        for (int i : later.keys())
            items.add(i);
        for (int i : lost.keys())
            items.add(i);
        var entries = new ArrayList<CraftingStatusEntry>();
        for (int id : items) {
            AEKey key = index.keyOf(id);
            if (key == null)
                continue;
            CraftingStatusEntry entry = new CraftingStatusEntry(serialOf.applyAsLong(key), key,
                    stored.get(id).saturatedLong(), active.get(id).saturatedLong(), pending.get(id).saturatedLong());
            // exact values beside AE2's longs; infinite = more of it is still drawn from an infinite source
            ((BigStatusEntry) entry).twinstar$setBig(new BigStatusAmounts(stored.get(id).bigValue(),
                    active.get(id).bigValue(), pending.get(id).bigValue(), later.get(id).signum() > 0,
                    chanceShort.get(id).bigValue(), lost.get(id).bigValue(), waiting.contains(id),
                    job.wasUnloaded() && active.get(id).signum() > 0));
            entries.add(entry);
        }
        return new CraftingStatus(true, System.nanoTime() - job.startNanos(), remaining.saturatedLong(),
                start.saturatedLong(), entries, job.isSuspended());
    }
}
