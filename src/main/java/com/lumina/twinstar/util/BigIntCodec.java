package com.lumina.twinstar.util;

import com.lumina.corelib.util.AmountParser;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.math.BigInteger;

/**
 * Exact amounts on the wire: two's-complement bytes, about 0.42 bytes per decimal digit (a decimal
 * string would take 1). Bounded by {@link AmountParser#MAX_EXTERNAL} so a bad packet cannot make the
 * other side allocate megabytes.
 */
public final class BigIntCodec {
    /** bytes of {@link AmountParser#MAX_EXTERNAL} (log2(10) / 8 ≈ 0.4153 per digit), plus sign room */
    public static final int MAX_BYTES = (int) ((AmountParser.MAX_EXPONENT + 4) * 0.4153) + 16;

    /** Orders sent by the client: at most an order's size. */
    public static final StreamCodec<ByteBuf, BigInteger> STREAM_CODEC =
            ByteBufCodecs.byteArray(MAX_BYTES).map(BigInteger::new, BigInteger::toByteArray);
    /**
     * Screen data from the server: materials and capacity can exceed the order (1e10000 × the
     * amount per item). Plans stop below the unlimited-capacity stand-in (2^65536 = 8 KiB).
     */
    private static final int SCREEN_MAX_BYTES = 16 * 1024;
    private static final StreamCodec<ByteBuf, BigInteger> SCREEN_CODEC =
            ByteBufCodecs.byteArray(SCREEN_MAX_BYTES).map(BigInteger::new, BigInteger::toByteArray);

    private BigIntCodec() {
    }

    /** Screen data (server → client). */
    public static void write(ByteBuf buf, BigInteger v) {
        SCREEN_CODEC.encode(buf, v);
    }

    public static BigInteger read(ByteBuf buf) {
        return SCREEN_CODEC.decode(buf);
    }
}
