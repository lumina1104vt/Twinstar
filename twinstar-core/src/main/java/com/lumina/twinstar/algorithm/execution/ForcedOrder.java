package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountMap;

import java.util.function.BiFunction;
import java.util.function.IntConsumer;

/**
 * 強制下單 bookkeeping, without the network: a forced order takes what the network has now and remembers what it lacks;
 * the lacking part is taken as it appears, and the order is done with waiting when nothing is lacking any more.
 */
final class ForcedOrder {
    private ForcedOrder() {
    }

    /**
     * Takes {@code wanted} through {@code taker} (item, amount → what it got, at most the amount). Returns what could not be
     * had: the order's shortage.
     */
    static AmountMap lacking(AmountMap wanted, BiFunction<Integer, Amount, Amount> taker) {
        AmountMap lack = new AmountMap();
        for (var e : wanted.entries()) {
            Amount got = taker.apply(e.getKey(), e.getValue()).min(e.getValue());
            if (got.compareTo(e.getValue()) < 0)
                lack.add(e.getKey(), e.getValue().sub(got.clampNonNegative()));
        }
        return lack;
    }

    /**
     * Takes what {@code shortage} still lacks through {@code taker}: what came goes into {@code inventory} and out of the
     * shortage, and {@code arrived} is told each item that got any. True when anything came.
     */
    static boolean topUp(AmountMap shortage, BiFunction<Integer, Amount, Amount> taker, AmountMap inventory, IntConsumer arrived) {
        boolean any = false;
        for (int item : shortage.keys()) {
            Amount want = shortage.get(item);
            Amount got = taker.apply(item, want).min(want);
            if (got.signum() <= 0)
                continue;
            shortage.sub(item, got);
            inventory.add(item, got);
            arrived.accept(item);
            any = true;
        }
        return any;
    }

    /** A forced order that lacks something and has made no progress for {@code limitNanos} gives up. */
    static boolean expired(boolean lacksSomething, long nowNanos, long lastProgressNanos, long limitNanos) {
        return lacksSomething && nowNanos - lastProgressNanos > limitNanos;
    }
}
