package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.metrics.HubReport;
import com.lumina.twinstar.metrics.TickMeter;
import org.jetbrains.annotations.Nullable;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sums the memory of the running jobs into the numbers a CPU screen shows (plain Java, so tests can run it). */
final class JobMemory {
    private JobMemory() {
    }

    /**
     * @param selected      the job of the CPU entry looked at, null for the idle entry
     * @param newest        the newest theory version (jobs on older ones keep them alive)
     * @param newestBytes   its estimated size (0 = unknown)
     * @param sharedBytes   what the hub holds beside its jobs
     * @param idleResidual  what the hub held when its last job ended (−1 = none ended yet)
     */
    static HubReport report(List<Job> jobs, @Nullable Job selected, @Nullable Snapshot newest, long newestBytes,
                            long sharedBytes, long idleResidual, TickMeter.Window tick) {
        BitSet tracked = new BitSet();
        long bytes = 0;
        Set<Long> oldVersions = new HashSet<>();
        for (Job j : jobs) {
            bytes += j.memoryBytes();
            j.markTrackedItems(tracked);
            if (newest != null && j.snap != newest && j.snap.version() >= 0 && j.snap.version() != newest.version())
                oldVersions.add(j.snap.version());
        }
        int jobItems = 0, jobTasks = 0;
        long jobBytes = -1;
        if (selected != null) {
            BitSet mine = new BitSet();
            selected.markTrackedItems(mine);
            jobItems = mine.cardinality();
            jobTasks = selected.tasks.size();
            jobBytes = selected.memoryBytes();
        }
        return new HubReport(jobs.size(), tracked.cardinality(), bytes + sharedBytes, jobBytes, jobItems, jobTasks, sharedBytes,
                idleResidual, oldVersions.size(), oldVersions.size() * newestBytes, tick.avgNanos(), tick.maxNanos(), tick.allocPerSecond());
    }
}
