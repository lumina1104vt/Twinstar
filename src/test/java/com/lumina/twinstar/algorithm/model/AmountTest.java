package com.lumina.twinstar.algorithm.model;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmountTest {
    @Test
    void overflowPromotesInsteadOfWrapping() {
        Amount max = Amount.of(Long.MAX_VALUE);
        Amount sum = max.add(Amount.ONE);
        assertTrue(sum.isBig());
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), sum.bigValue());
        Amount prod = max.mul(Amount.of(3));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3)), prod.bigValue());
        assertThrows(ArithmeticException.class, prod::longValue);
    }

    @Test
    void normalisedBackToLong() {
        Amount big = Amount.of(Long.MAX_VALUE).add(Amount.of(10));
        Amount back = big.sub(Amount.of(20));
        assertFalse(back.isBig());
        assertEquals(Long.MAX_VALUE - 10, back.longValue());
        assertEquals(Amount.of(5), Amount.of(BigInteger.valueOf(5)));
    }

    @Test
    void divisionRounding() {
        assertEquals(Amount.of(4), Amount.of(7).ceilDiv(Amount.of(2)));
        assertEquals(Amount.of(3), Amount.of(7).div(Amount.of(2)));
        Amount huge = Amount.of(BigInteger.TEN.pow(40).add(BigInteger.ONE));
        assertEquals(Amount.of(BigInteger.TEN.pow(39).add(BigInteger.ONE)), huge.ceilDiv(Amount.of(10)));
    }

    @Test
    void logOfHugeValues() {
        Amount a = Amount.of(BigInteger.TEN.pow(1000));
        assertEquals(1000.0, a.log10(), 1e-9);
        assertEquals(Amount.of(1000), Amount.floorOfLog10(3.0000001));
    }

    @Test
    void amountArrayKeepsOverflowedSlots() {
        AmountArray arr = new AmountArray(3);
        arr.set(1, Amount.of(Long.MAX_VALUE));
        arr.add(1, Amount.of(Long.MAX_VALUE));
        assertTrue(arr.isBig(1));
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1), arr.get(1).bigValue());
        arr.sub(1, Amount.of(Long.MAX_VALUE));
        assertFalse(arr.isBig(1));
        assertEquals(Amount.of(Long.MAX_VALUE), arr.get(1));
    }
}
