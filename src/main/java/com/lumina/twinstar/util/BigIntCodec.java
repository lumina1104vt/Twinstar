package com.lumina.twinstar.util;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;

import java.math.BigInteger;

/**
 * Exact amounts on the wire: two's-complement bytes, about 0.42 bytes per decimal digit (a decimal
 * string would take 1). Bounded by {@link AmountParser#MAX_EXTERNAL} so a bad packet cannot make the
 * other side allocate megabytes.
 */
public final class BigIntCodec {
    /** bytes of {@link AmountParser#MAX_EXTERNAL} (log2(10) / 8 ≈ 0.4153 per digit), plus sign room */
    public static final int MAX_BYTES = (int) ((AmountParser.MAX_EXPONENT + 4) * 0.4153) + 16;

    /**
     * Screen data from the server: materials and capacity can exceed the order (1e10000 × the
     * amount per item). Plans stop below the unlimited-capacity stand-in (2^65536 = 8 KiB).
     */
    private static final int SCREEN_MAX_BYTES = 16 * 1024;

    private BigIntCodec() {
    }

    /** Orders sent by the client: at most an order's size. */
    public static void writeOrder(ByteBuf buf, BigInteger v) {
        new FriendlyByteBuf(buf).writeByteArray(v.toByteArray());
    }

    public static BigInteger readOrder(ByteBuf buf) {
        return new BigInteger(new FriendlyByteBuf(buf).readByteArray(MAX_BYTES));
    }

    /** Screen data (server → client). */
    public static void write(ByteBuf buf, BigInteger v) {
        new FriendlyByteBuf(buf).writeByteArray(v.toByteArray());
    }

    public static BigInteger read(ByteBuf buf) {
        return new BigInteger(new FriendlyByteBuf(buf).readByteArray(SCREEN_MAX_BYTES));
    }
}
