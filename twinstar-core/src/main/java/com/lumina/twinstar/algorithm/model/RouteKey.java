package com.lumina.twinstar.algorithm.model;

import java.util.Comparator;

/**
 * Route order for one item: provider priority → uses a "保留" material? → material cost →
 * location (hub-internal → external crafting → external processing) → pattern id.
 */
public record RouteKey(int negPriority, int usesReserved, double logUnitCost, int rank, int pattern) {
    public static final Comparator<RouteKey> ORDER = Comparator
            .comparingInt(RouteKey::negPriority)
            .thenComparingInt(RouteKey::usesReserved)
            .thenComparingDouble(RouteKey::logUnitCost)   // log is monotone: same order as the plain cost
            .thenComparingInt(RouteKey::rank)
            .thenComparingInt(RouteKey::pattern);
}
