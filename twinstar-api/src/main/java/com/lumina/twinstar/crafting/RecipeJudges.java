package com.lumina.twinstar.crafting;

import com.lumina.bridge.key.Key;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Every {@link RecipeJudge} there is; mods add theirs once at start-up. */
public final class RecipeJudges {
    private static final List<RecipeJudge> JUDGES = new CopyOnWriteArrayList<>();

    private RecipeJudges() {
    }

    /** Adds a judge (the same instance twice is kept once). */
    public static void register(RecipeJudge judge) {
        if (!JUDGES.contains(judge))
            JUDGES.add(judge);
    }

    /** Whether some judge handles patterns naming {@code machine}, so they must pass {@link #accepts}. */
    public static boolean isJudged(Key machine) {
        for (RecipeJudge j : JUDGES)
            if (j.handles(machine))
                return true;
        return false;
    }

    /** True when some judge that handles {@code machine} accepts the pattern; false when none does. */
    public static boolean accepts(Key machine, List<RecipeJudge.In> inputs, List<RecipeJudge.Out> outputs, Object world) {
        for (RecipeJudge j : JUDGES) {
            if (!j.handles(machine))
                continue;
            try {
                if (j.accepts(machine, inputs, outputs, world))
                    return true;
            } catch (RuntimeException e) {
                // a judge that cannot answer vouches for nothing
            }
        }
        return false;
    }
}
