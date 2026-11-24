package com.lumina.twinstar.algorithm.ae2;

import appeng.api.crafting.IPatternDetails;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.PatternLocation;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Translates another mod's special patterns (scaled, closed-loop, virtually completed, returning
 * things their list does not mention, ...) into internal patterns. Patterns no adapter handles are
 * used exactly as their declared lists say.
 * <p>
 * Internal for now; frozen into a public API once two real adapters (OmniSequence, Thunderbolt)
 * have proven its shape. How special patterns are dispatched and completed is still open (【待定】):
 * the executor currently pushes {@link Pattern#source()} like any AE2 pattern.
 */
public interface PatternAdapter {
    boolean handles(IPatternDetails details);

    /**
     * One AE2 pattern may become several internal patterns. Each needs a fresh id from {@code ids}
     * and {@code details} as its source.
     */
    List<Pattern> translate(IPatternDetails details, ItemIndex index, PatternLocation where, int priority, IntSupplier ids);
}
