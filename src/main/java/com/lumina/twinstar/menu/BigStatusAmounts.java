package com.lumina.twinstar.menu;

import com.lumina.bridge.wire.Wire;
import com.lumina.bridge.wire.WireBuf;
import com.lumina.corelib.util.BigIntCodec;
import net.minecraft.network.FriendlyByteBuf;

import java.math.BigInteger;

/**
 * Exact amounts of one CPU-status entry (AE2's entry only has longs): in the job's inventory, sent
 * to machines and not back yet, still to be made; whether more of it comes from an infinite source;
 * what outputs that come with a probability did not give after all their runs (機率產物不足); and
 * the runs making it whose materials stayed in a machine that was taken away; and whether a
 * pattern making it waits for someone to offer it again (等樣板); and whether what it waits for may
 * have gone into network storage while the hub was down; and how much of it a forced order still lacks from the network
 * (缺少的材料).
 */
public record BigStatusAmounts(BigInteger stored, BigInteger active, BigInteger pending, boolean infinite,
                               BigInteger chanceShort, BigInteger machineLost, boolean waitingPattern,
                               boolean mayBeInNetwork, BigInteger shortage) {
    public void write(FriendlyByteBuf buf) {
        WireBuf wire = Wire.of(buf);
        BigIntCodec.write(wire, stored);
        BigIntCodec.write(wire, active);
        BigIntCodec.write(wire, pending);
        buf.writeBoolean(infinite);
        BigIntCodec.write(wire, chanceShort);
        BigIntCodec.write(wire, machineLost);
        buf.writeBoolean(waitingPattern);
        buf.writeBoolean(mayBeInNetwork);
        BigIntCodec.write(wire, shortage);
    }

    public static BigStatusAmounts read(FriendlyByteBuf buf) {
        WireBuf wire = Wire.of(buf);
        // the arguments are read left to right, in the order they were written
        return new BigStatusAmounts(BigIntCodec.read(wire), BigIntCodec.read(wire),
                BigIntCodec.read(wire), buf.readBoolean(), BigIntCodec.read(wire), BigIntCodec.read(wire), buf.readBoolean(), buf.readBoolean(),
                BigIntCodec.read(wire));
    }
}
