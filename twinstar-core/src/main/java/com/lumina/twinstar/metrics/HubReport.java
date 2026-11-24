package com.lumina.twinstar.metrics;

/**
 * What a CPU screen shows about the hub's own Java memory and speed.
 *
 * @param jobs              running jobs
 * @param trackedItems      kinds of items the jobs track now (inventory, expected returns, seeds, loops)
 * @param totalBytes        all of the hub's data: every job, the return pool and the machine windows
 * @param jobBytes          the job this CPU entry stands for (−1 for the idle entry)
 * @param jobItems          kinds of items that job tracks
 * @param jobTasks          patterns (tasks) that job still has
 * @param poolBytes         returns waiting in the hub, and the machine windows with their timing samples
 * @param idleResidualBytes what the hub still held when its last job ended (−1 = no job has ended yet)
 * @param oldSnapshots      jobs that run on an older theory version than the newest one (each keeps that version alive)
 * @param oldSnapshotBytes  what those older versions cost in all (estimated by the newest version's size)
 * @param tickAvgNanos      average time the executor took per tick, over the last second with jobs
 * @param tickMaxNanos      slowest tick of that second
 * @param allocPerSecond    bytes the executor allocated per second then (−1 unknown)
 */
public record HubReport(int jobs, int trackedItems, long totalBytes, long jobBytes, int jobItems, int jobTasks,
                        long poolBytes, long idleResidualBytes, int oldSnapshots, long oldSnapshotBytes,
                        long tickAvgNanos, long tickMaxNanos, long allocPerSecond) {
    public static final HubReport NONE = new HubReport(0, 0, 0, -1, 0, 0, 0, -1, 0, 0, 0, 0, -1);

    /** Average bytes per tracked kind of item (0 when none). */
    public long bytesPerItem() {
        return trackedItems <= 0 ? 0 : (totalBytes - poolBytes) / trackedItems;
    }
}
