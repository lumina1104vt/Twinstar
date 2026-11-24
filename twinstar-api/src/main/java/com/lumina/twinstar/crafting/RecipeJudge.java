package com.lumina.twinstar.crafting;

import com.lumina.bridge.key.Key;

import java.util.List;

/**
 * Decides whether a hub pattern that names a certain machine is something that machine really makes. The hub
 * trusts a pattern whose machine it knows how to check only when a judge agrees; a pattern naming a machine no
 * judge handles is not judged here (the pattern compiler has its own rule for those).
 * <p>
 * Example: a pattern "1 dirt in, 1000 diamonds out" naming a crafting table is refused by the vanilla judge,
 * since no crafting recipe says so. A GregTech compat mod would add a judge for its machines that looks the
 * pattern up in that machine's recipe map.
 */
public interface RecipeJudge {
    /** One material of a pattern: the keys it may be, and how many of it. */
    record In(List<Key> options, long amount) {
    }

    /** One product of a pattern. */
    record Out(Key what, long amount) {
    }

    /** Whether this judge is the one to ask about patterns naming {@code machine}. */
    boolean handles(Key machine);

    /**
     * Whether the pattern is a real recipe of {@code machine}.
     *
     * @param world the platform's world object (a {@code Level} on 1.21.1) the recipes are read from
     */
    boolean accepts(Key machine, List<In> inputs, List<Out> outputs, Object world);
}
