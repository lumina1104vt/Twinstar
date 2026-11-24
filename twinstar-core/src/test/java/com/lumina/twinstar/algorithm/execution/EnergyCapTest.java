package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.Rational;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EnergyCapTest {
    /** 1 osmium ingot + 20 mB of a chemical (125 per operation): 1.16 AE, not exact as a double. */
    private static final Rational PER = Rational.of(BigInteger.valueOf(29), BigInteger.valueOf(25));

    @Test
    void networkGivingWhatItWasAskedIsEnough() {
        assertEquals(Amount.ONE, EnergyCap.poweredRuns(PER, Amount.ONE, Rational.ZERO, rest -> rest));
        assertEquals(Amount.of(1000), EnergyCap.poweredRuns(PER, Amount.of(1000), Rational.ZERO, rest -> rest));
    }

    @Test
    void partOfTheEnergyPaysForWholeRunsOnly() {
        // 5 runs' worth as the network counts it (5.8 as a double): 5 runs, not 4
        double five = PER.mul(Rational.of(5)).toDoubleSaturated();
        assertEquals(Amount.of(5), EnergyCap.poweredRuns(PER, Amount.of(10), Rational.ZERO, rest -> five));
        assertEquals(Amount.of(2), EnergyCap.poweredRuns(PER, Amount.of(3), Rational.ZERO, rest -> 2.4));
        assertEquals(Amount.ZERO, EnergyCap.poweredRuns(PER, Amount.of(3), Rational.ZERO, rest -> 0));
    }

    @Test
    void hubStorageCountsExactly() {
        assertEquals(Amount.of(3), EnergyCap.poweredRuns(PER, Amount.of(3), PER.mul(Rational.of(3)), rest -> 0));
        // 1 run from the hub, 1 more from the network
        assertEquals(Amount.of(2), EnergyCap.poweredRuns(PER, Amount.of(3), PER, rest -> PER.toDoubleSaturated()));
    }

    @Test
    void infiniteNetworkDoesNotCap() {
        Amount huge = Amount.of(BigInteger.TEN.pow(400));
        assertEquals(huge, EnergyCap.poweredRuns(PER, huge, Rational.ZERO, rest -> rest));
    }
}
