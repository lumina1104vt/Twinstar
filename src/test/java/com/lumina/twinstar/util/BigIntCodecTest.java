package com.lumina.twinstar.util;

import com.lumina.corelib.util.AmountParser;
import org.junit.jupiter.api.Test;

class BigIntCodecTest {
    @Test
    void bigIntegersFitTheWireLimit() {
        java.math.BigInteger max = AmountParser.MAX_EXTERNAL;
        org.junit.jupiter.api.Assertions.assertTrue(max.toByteArray().length <= BigIntCodec.MAX_BYTES);
    }
}
