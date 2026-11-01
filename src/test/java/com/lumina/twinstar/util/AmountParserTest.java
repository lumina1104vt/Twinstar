package com.lumina.twinstar.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AmountParserTest {
    private static void is(String expected, String input) {
        assertEquals(new BigDecimal(expected), AmountParser.parse(input), input);
    }

    @Test
    void suffixes() {
        is("64000", "64K");
        is("1500000", "1.5m");
        is("3000000000", "3G");
        is("2000000000000", "2T");
        is("1000000000000000", "1P");
        is("9000000000000000000", "9000P");
        is("3000000000000000000", "3E");     // AE2 shows exa as E
        is("3000000000000000000", "3e");
        is("1500000000000000000000", "1.5e3E");
        is("1000000000000000000", "1e");    // a lone trailing e is the unit, not an exponent
    }

    @Test
    void scientific() {
        is("2000000000000000000", "2e18");
        is("2000000000000000000", "2E18");
        is("1200000", "1.2e3K");
        is("0.5", "5e-1");      // the field then reports "not an integer" for items
    }

    @Test
    void hugeExponents() {
        assertEquals(java.math.BigInteger.TEN.pow(10000), AmountParser.parse("1e10000").toBigIntegerExact());
        assertEquals(java.math.BigInteger.TEN.pow(10000), AmountParser.parse("10e9999").toBigIntegerExact());
        assertEquals(java.math.BigInteger.TEN.pow(10000), AmountParser.parse("1e9997K").toBigIntegerExact());
        assertNull(AmountParser.parse("1.1e10000"));       // beyond MAX
        assertNull(AmountParser.parse("1e10001"));
        assertNull(AmountParser.parse("1e10000K"));
        assertNull(AmountParser.parse("1e999999"));
    }

    @Test
    void bigIntegersFitTheWireLimit() {
        java.math.BigInteger max = AmountParser.MAX_EXTERNAL;
        org.junit.jupiter.api.Assertions.assertTrue(max.toByteArray().length <= BigIntCodec.MAX_BYTES);
    }

    @Test
    void decorations() {
        is("2000000000000000000", "=2e18");
        is("1000000", " 1_000K ");
        is("1000", "1,000e0");
    }

    @Test
    void plainNumbers() {
        is("1", "1");
        is("64", "64");
        is("1234", "1,234");
        is("5", "=5");
        is("2.5", "2.5");
        is("123456789012345678901234567890", "123456789012345678901234567890");
    }

    @Test
    void rejectsOtherInput() {
        assertNull(AmountParser.parse("64*3"));
        assertNull(AmountParser.parse("abc"));
        assertNull(AmountParser.parse("e3"));
        assertNull(AmountParser.parse("3EE"));
        assertNull(AmountParser.parse(""));
    }
}
