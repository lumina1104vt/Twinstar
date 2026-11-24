package com.lumina.twinstar.port;

/** Who takes a pattern's runs right now. */
public interface PatternSites {
    /** The sites that offer the pattern; none for something that is not a pattern of this platform. */
    Iterable<PatternSite> offering(Object pattern);
}
