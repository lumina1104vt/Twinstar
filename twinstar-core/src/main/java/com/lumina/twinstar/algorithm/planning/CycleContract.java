package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Amount;

/**
 * A firing order for a policy cycle that the plain executor rules ("run what can run, protect the
 * seeds") would get stuck on. Found by the budgeted A* search; the executor follows it step by step.
 */
public record CycleContract(int scc, int[] patterns, Amount[] batches) {
}
