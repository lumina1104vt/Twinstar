package com.lumina.twinstar.algorithm.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.twinstar.algorithm.execution.Stock;
import com.lumina.bridge.key.Keys;
import appeng.api.storage.MEStorage;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.api.IBigStorage;

import java.math.BigInteger;
import java.util.List;

/**
 * The network's storage as the engine uses it (main thread): AE2's long-based storage plus the
 * {@link IBigStorage}s on the grid, which take and give any amount in one call.
 * <p>
 * The big storages are also mounted in AE2's storage (as long views), so the same contents can be
 * reached both ways; extraction drains them exactly first, so AE2 never sees them twice.
 */
public final class NetworkStore implements Stock {
    /** AE2 storage takes longs: an astronomical amount without a big storage goes in over many ticks. */
    private static final int MAX_CHUNKS_PER_CALL = 16;
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final MEStorage ae2;
    private final List<IBigStorage> big;
    private final IActionSource source;
    private final List<? extends MEStorage> endless;

    /** {@code endless}: the cells on the grid that are listed as never running out. */
    public NetworkStore(MEStorage ae2, List<IBigStorage> big, IActionSource source, List<? extends MEStorage> endless) {
        this.endless = endless;
        this.ae2 = ae2;
        this.big = big;
        this.source = source;
    }

    /**
     * Returns what went in. Amounts that fit a long go through AE2 first (its priorities and other
     * storages still apply); the rest, or all of an astronomical amount, goes exactly into a big storage.
     */
    public Amount insert(AEKey key, Amount amount) {
        if (amount.signum() <= 0)
            return Amount.ZERO;
        if (big.isEmpty())
            return ae2Insert(key, amount, MAX_CHUNKS_PER_CALL);
        Amount done = amount.isBig() ? Amount.ZERO : ae2Insert(key, amount, 1);
        for (IBigStorage s : big) {
            Amount left = amount.sub(done);
            if (left.signum() <= 0)
                break;
            done = done.add(Amount.of(s.insertBig(key, left.bigValue(), false)));
        }
        // a big storage may take nothing in: what is left still has AE2's storage
        if (done.compareTo(amount) < 0)
            done = done.add(ae2Insert(key, amount.sub(done), MAX_CHUNKS_PER_CALL));
        return done;
    }

    @Override
    public Amount insert(Key key, Amount amount) {
        return insert(Keys.toAe(key), amount);
    }

    @Override
    public Amount extract(Key key, Amount amount) {
        return extract(Keys.toAe(key), amount);
    }

    @Override
    public boolean isInfinite(Key key) {
        return isInfinite(Keys.toAe(key));
    }

    /** Takes up to {@code amount} out; returns what came out. */
    public Amount extract(AEKey key, Amount amount) {
        Amount done = Amount.ZERO;
        for (IBigStorage s : big) {
            Amount left = amount.sub(done);
            if (left.signum() <= 0)
                return done;
            done = done.add(Amount.of(s.extractBig(key, left.bigValue(), false)));
        }
        Amount left = amount.sub(done);
        for (int c = 0; c < MAX_CHUNKS_PER_CALL && left.signum() > 0; c++) {
            long chunk = left.saturatedLong();
            long got = ae2.extract(key, chunk, Actionable.MODULATE, source);
            done = done.add(Amount.of(got));
            left = left.sub(Amount.of(got));
            if (got < chunk)
                break;
        }
        return done;
    }

    /**
     * AE2 has no "infinite source" flag, and amounts cannot tell: a huge but finite storage hands out a long's
     * worth just as a creative cell does. So only the cells listed in the item tag {@code twinstar:endless_cells}
     * count, and only for what one of them offers.
     */
    public boolean isInfinite(AEKey key) {
        for (MEStorage cell : endless)
            if (cell.extract(key, 1, Actionable.SIMULATE, source) > 0)
                return true;
        for (IBigStorage s : big)
            if (s.isEndless(key))
                return true;
        return false;
    }

    /** Exact contents of the big storages. */
    public BigInteger bigTotal(AEKey key) {
        BigInteger sum = BigInteger.ZERO;
        for (IBigStorage s : big)
            sum = sum.add(s.getAmount(key));
        return sum;
    }

    /** Exact amount on the network, given what AE2 reports ({@code ae2Reported}, the big storages saturated in it). */
    public BigInteger exactStock(AEKey key, long ae2Reported) {
        long v = Math.max(0, ae2Reported);
        if (big.isEmpty())
            return BigInteger.valueOf(v);
        long others = Math.max(0, v - bigAsSeenByAe2(key));
        return BigInteger.valueOf(others).add(bigTotal(key));
    }

    /** What AE2 reports for the big storages (each saturated at a long), to subtract from its totals. */
    long bigAsSeenByAe2(AEKey key) {
        long sum = 0;
        for (IBigStorage s : big) {
            sum += s.getAmount(key).min(LONG_MAX).longValue();
            if (sum < 0)
                return Long.MAX_VALUE;
        }
        return sum;
    }

    public MEStorage ae2() {
        return ae2;
    }

    public IActionSource source() {
        return source;
    }

    public boolean hasBigStorage() {
        return !big.isEmpty();
    }

    private Amount ae2Insert(AEKey key, Amount amount, int maxChunks) {
        Amount done = Amount.ZERO;
        Amount left = amount;
        for (int c = 0; c < maxChunks && left.signum() > 0; c++) {
            long chunk = left.saturatedLong();
            long in = ae2.insert(key, chunk, Actionable.MODULATE, source);
            done = done.add(Amount.of(in));
            left = left.sub(Amount.of(in));
            if (in < chunk)
                break;
        }
        return done;
    }
}
