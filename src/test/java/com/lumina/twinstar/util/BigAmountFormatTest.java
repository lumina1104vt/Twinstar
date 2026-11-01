package com.lumina.twinstar.util;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BigAmountFormatTest {
    private static String f(String v) {
        return BigAmountFormat.unitForm(new BigInteger(v));
    }

    @Test
    void units() {
        assertEquals("0", f("0"));
        assertEquals("999", f("999"));
        assertEquals("1K", f("1000"));
        assertEquals("1.23K", f("1234"));
        assertEquals("12.3K", f("12345"));
        assertEquals("123K", f("123456"));
        assertEquals("9.22E", f("9223372036854775807"));
        assertEquals("1Z", f("1" + "0".repeat(21)));
        assertEquals("1Y", f("1" + "0".repeat(24)));
        assertEquals("1R", f("1" + "0".repeat(27)));
        assertEquals("1Q", f("1" + "0".repeat(30)));
        assertEquals("999Q", f("999" + "0".repeat(30)));
    }

    @Test
    void fullFormOfHugeNumbers() {
        assertEquals("1×10^10000", BigAmountFormat.fullForm(BigInteger.TEN.pow(10000), 1, ""));
        assertEquals("1.23×10^99", BigAmountFormat.fullForm(BigInteger.valueOf(123).multiply(BigInteger.TEN.pow(97)), 1, ""));
        String full = BigAmountFormat.fullForm(BigInteger.TEN.pow(40), 1, "");
        assertEquals(41, full.replaceAll("[^0-9]", "").length());     // still every digit below 64 digits
    }

    @Test
    void beyondQuetta() {
        assertEquals("1.00e33", f("1" + "0".repeat(33)));
        assertEquals("1.00e40", f("1" + "0".repeat(40)));
    }

    @Test
    void fluidsInBuckets() {
        assertEquals("1.5KB", BigAmountFormat.shortForm(BigInteger.valueOf(1_500_000), 1000, "B"));
    }

    @Test
    void parserReadsTheSameUnits() {
        assertEquals(new java.math.BigDecimal("2" + "0".repeat(21)), AmountParser.parse("2Z"));
        assertEquals(new java.math.BigDecimal("1" + "0".repeat(30)), AmountParser.parse("1Q"));
        assertEquals(new java.math.BigDecimal("5" + "0".repeat(27)), AmountParser.parse("5r"));
        assertEquals(new java.math.BigDecimal("3" + "0".repeat(24)), AmountParser.parse("3Y"));
    }
}
