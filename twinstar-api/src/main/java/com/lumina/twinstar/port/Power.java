package com.lumina.twinstar.port;

/** The network's energy, in the platform's own unit. */
public interface Power {
    /** Takes up to {@code amount}; returns what came out. {@code simulate}: only asks. */
    double extract(double amount, boolean simulate);
}
