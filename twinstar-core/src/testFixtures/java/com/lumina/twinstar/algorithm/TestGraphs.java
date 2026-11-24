package com.lumina.twinstar.algorithm;

import com.lumina.twinstar.algorithm.cache.Snapshot;
import com.lumina.twinstar.algorithm.cache.TheoryLayer;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.algorithm.model.AmountArray;
import com.lumina.twinstar.algorithm.model.Pattern;
import com.lumina.twinstar.algorithm.model.StockSnapshot;

import java.util.BitSet;
import java.util.function.Consumer;

/** Small helper for building crafting graphs from int ids in tests. */
public final class TestGraphs {
    public final TheoryLayer theory = new TheoryLayer();
    private final AmountArray stock = new AmountArray(256);
    private final BitSet infinite = new BitSet();
    private int nextPattern;

    public int pattern(Consumer<Pattern.Builder> spec) {
        Pattern.Builder b = new Pattern.Builder();
        spec.accept(b);
        int id = nextPattern++;
        theory.addPattern(b.build(id));
        return id;
    }

    public TestGraphs stock(int item, long amount) {
        stock.set(item, Amount.of(amount));
        return this;
    }

    public TestGraphs stock(int item, Amount amount) {
        stock.set(item, amount);
        return this;
    }

    public TestGraphs infinite(int item) {
        infinite.set(item);
        return this;
    }

    public StockSnapshot stockSnapshot() {
        return new StockSnapshot(stock.copy(), (BitSet) infinite.clone());
    }

    public Snapshot flush() {
        theory.applyStock(stockSnapshot());
        return theory.flush();
    }
}
