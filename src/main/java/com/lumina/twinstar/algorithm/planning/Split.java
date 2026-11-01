package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Amount;

/** Route {@code pattern} takes {@code used} of an item's demand, running {@code runs} times. */
public record Split(int pattern, Amount runs, Amount used) {
}
