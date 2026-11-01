package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Rational;

import java.util.function.DoubleUnaryOperator;

/** How many runs the energy pays for (no game classes: tested on its own). */
final class EnergyCap {
    /** a double's rounding of a share it gave: never short of a run by less than this (AE) */
    private static final Rational SLACK = Rational.of(1e-9);

    private EnergyCap() {
    }

    /**
     * Of {@code n} runs at {@code per} each, how many the energy pays for: {@code fromHub} exactly,
     * the rest from the network, which AE2 counts as a double. Whatever the network gives of what it
     * was asked is enough, compared as doubles: 1.16 AE asked and given is 1.15999… as a double,
     * which exact arithmetic would call short (it stopped every run of a chemical pattern).
     */
    static Amount poweredRuns(Rational per, Amount n, Rational fromHub, DoubleUnaryOperator network) {
        Rational want = per.mul(Rational.of(n));
        if (fromHub.compareTo(want) >= 0)
            return n;
        double rest = want.sub(fromHub).toDoubleSaturated();
        double got = network.applyAsDouble(rest);
        // also a need beyond a double's range, asked as Double.MAX_VALUE: a network that still gives
        // all of that (creative energy cell) is infinite, like infinite item sources
        if (got >= rest)
            return n;
        Rational have = fromHub.add(Rational.of(Double.isFinite(got) ? got : Double.MAX_VALUE)).add(SLACK);
        return n.min(Amount.of(have.div(per).floor()));
    }
}
