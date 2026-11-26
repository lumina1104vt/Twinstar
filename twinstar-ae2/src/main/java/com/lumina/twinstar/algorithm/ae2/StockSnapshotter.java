package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.StockSnapshot;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.BitSet;

/** Main thread only: the planner runs in the background and must never read AE2's storage itself. */
public final class StockSnapshotter {
    /**
     * Stacks reported at least this large are asked about being infinite. AE2's creative storage cell
     * reports Integer.MAX_VALUE per item; other mods' infinity cells report that or more.
     */
    private static final long PROBE_FROM = Integer.MAX_VALUE;

    private StockSnapshotter() {
    }

    /**
     * Exact amounts: AE2's totals (longs, where the big storages appear saturated) with the big
     * storages' share replaced by their exact BigInteger contents. Infinite: see {@link NetworkStore#isInfinite}.
     */
    public static StockSnapshot take(NetworkStore store, ItemIndex index) {
        KeyCounter all = store.ae2().getAvailableStacks();
        for (Object2LongMap.Entry<AEKey> e : all)
            index.idOf(e.getKey());
        AmountArray stock = new AmountArray(index.size());
        BitSet infinite = new BitSet();
        for (Object2LongMap.Entry<AEKey> e : all) {
            AEKey key = e.getKey();
            int id = index.idOf(key);
            long v = Math.max(0, e.getLongValue());
            if (v >= PROBE_FROM && store.isInfinite(key))
                infinite.set(id);
            if (!store.hasBigStorage()) {
                stock.set(id, Amount.of(v));
                continue;
            }
            long others = Math.max(0, v - store.bigAsSeenByAe2(key));
            stock.set(id, Amount.of(others).add(Amount.of(store.bigTotal(key))));
        }
        return new StockSnapshot(stock, infinite);
    }
}
