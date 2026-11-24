package com.lumina.twinstar.algorithm.execution;

/** Shown on a job: item {@code item} from pattern {@code pattern} has not come back for {@code waitedNanos}. */
public record StuckMark(int item, int pattern, long waitedNanos) {
}
