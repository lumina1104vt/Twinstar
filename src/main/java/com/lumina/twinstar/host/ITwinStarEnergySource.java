package com.lumina.twinstar.host;

import com.lumina.twinstar.algorithm.model.Rational;

/**
 * Energy the hub draws before AE2's network, in exact fractions of AE (AE2's energy unit), so it is
 * not limited by AE2's double-based energy (Gravity Tech's own energy storage, planned).
 * <p>
 * The executor takes energy from here first and from the AE2 network for the rest.
 */
public interface ITwinStarEnergySource {
    /** Energy available right now, in AE. */
    Rational getStoredEnergy();

    /** Takes up to {@code amount} AE; returns what was (or, simulated, would be) taken. */
    Rational extractEnergy(Rational amount, boolean simulate);
}
