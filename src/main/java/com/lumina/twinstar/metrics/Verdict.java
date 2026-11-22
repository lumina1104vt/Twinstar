package com.lumina.twinstar.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * A rating and why: one {@link Reason} per check that was not normal, worst first, plus every check
 * made (for the tooltip: "what was looked at").
 */
public record Verdict(Rating rating, List<Reason> reasons) {
    /**
     * One check.
     *
     * @param key    which check (a language key suffix)
     * @param rating its own rating
     * @param value  what was measured
     * @param high   the limit for {@link Rating#HIGH}
     * @param very   the limit for {@link Rating#VERY_HIGH}
     */
    public record Reason(String key, Rating rating, double value, double high, double very) {
    }

    public static Verdict of(List<Reason> checks) {
        Rating r = Rating.NORMAL;
        for (Reason c : checks)
            r = r.worst(c.rating());
        List<Reason> sorted = new ArrayList<>(checks);
        sorted.sort((a, b) -> b.rating().compareTo(a.rating()));
        return new Verdict(r, List.copyOf(sorted));
    }
}
