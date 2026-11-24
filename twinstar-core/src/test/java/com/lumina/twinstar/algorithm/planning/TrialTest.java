package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.TestGraphs;
import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Deadline;
import com.lumina.twinstar.algorithm.model.InputKind;
import com.lumina.twinstar.algorithm.model.Overlay;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C1 trials force their route for the first demand only. Found with a real pack: vanilla's
 * "white dye + any wool → white wool" lists white wool itself first among the wools, so a trial
 * forcing that recipe asked for one more white wool per run, forever (until the safety net).
 */
class TrialTest {
    static final int WHITE = 1, RED = 2, DYE = 3, STRING = 4;

    @Test
    void demandComingBackUsesTheForbiddenList() {
        TestGraphs g = new TestGraphs();
        int dye = g.pattern(b -> b.input(DYE, Amount.ONE, InputKind.CONSUME, 0, new int[0])
                .input(WHITE, Amount.ONE, InputKind.CONSUME, 0, new int[]{RED}).allowSubst(true).output(WHITE, 1));
        g.pattern(b -> b.input(STRING, 4).output(WHITE, 1));
        g.stock(DYE, 1000).stock(STRING, 400);
        Snapshot s = g.flush();

        DemandPass pass = DemandPass.start(s, g.stockSnapshot(), new Overlay(), Deadline.NONE, Map.of());
        // dye route for 10: the first run's wool comes from string, each run gives the wool back
        assertTrue(pass.trial(WHITE, Amount.of(10), dye), "the dye route can make 10 white wool");
    }
}
