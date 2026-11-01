package com.lumina.twinstar.algorithm.planning;

import com.lumina.twinstar.algorithm.model.Rational;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactSimplexTest {
    private static Rational r(long v) {
        return Rational.of(v);
    }

    @Test
    void minimisesSum() {
        // min x + y, x + y ≥ 3, x − y ≥ 1
        Rational[] x = ExactSimplex.minimize(new Rational[]{r(1), r(1)},
                new Rational[][]{{r(1), r(1)}, {r(1), r(-1)}}, new Rational[]{r(3), r(1)});
        assertNotNull(x);
        assertEquals(r(3), x[0].add(x[1]));
        assertTrue(x[0].sub(x[1]).compareTo(r(1)) >= 0);
    }

    @Test
    void detectsInfeasible() {
        // x ≥ 2 and −x ≥ −1
        assertFalse(ExactSimplex.feasible(new Rational[][]{{r(1)}, {r(-1)}}, new Rational[]{r(2), r(-1)}));
    }

    @Test
    void exactFractions() {
        // min x, 3x ≥ 1 → x = 1/3 exactly
        Rational[] x = ExactSimplex.minimize(new Rational[]{r(1)}, new Rational[][]{{r(3)}}, new Rational[]{r(1)});
        assertNotNull(x);
        assertEquals(Rational.of(java.math.BigInteger.ONE, java.math.BigInteger.valueOf(3)), x[0]);
    }
}
