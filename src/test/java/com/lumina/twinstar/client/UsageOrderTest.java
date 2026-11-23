package com.lumina.twinstar.client;

import com.lumina.twinstar.client.UsageOrder.Sort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Filtering, sorting and grouping of the usage list, with plain test machines (no game classes). */
class UsageOrderTest {
    /** one material row: a name and its amounts in the 1, 5 and 30 minute windows; fluid rows count in thousands */
    record Row(String item, long m1, long m5, long m30, double unit) {
        long[] totals() {
            return new long[]{m1, m5, m30};
        }
    }

    record Mach(String name, int x, String iface, List<Row> rows) {
        String id() {
            return name + "@" + x + "|" + iface;
        }
    }

    private static final UsageOrder.Source<Mach, Row> SRC = new UsageOrder.Source<>() {
        public String id(Mach m) { return m.id(); }
        public String name(Mach m) { return m.name(); }
        public String ifaceId(Mach m) { return "if:" + m.iface(); }
        public String ifaceName(Mach m) { return m.iface(); }
        public String place(Mach m) { return m.x() + ", 64, 0"; }
        public List<Row> rows(Mach m) { return m.rows(); }
        public long[] totals(Row r) { return r.totals(); }
        public String itemName(Row r) { return r.item(); }
        public double unit(Row r) { return r.unit(); }
    };

    private static Row row(String item, long m1, long m5, long m30) {
        return new Row(item, m1, m5, m30, 1);
    }

    private static Mach mach(String name, int x, String iface, Row... rows) {
        return new Mach(name, x, iface, List.of(rows));
    }

    private static UsageOrder<Mach, Row> build(List<Mach> ms, int w, boolean group, Sort sort, boolean asc, String q) {
        return UsageOrder.build(ms, SRC, w, group, sort, asc, q);
    }

    private static List<String> names(UsageOrder<Mach, Row> o) {
        List<String> out = new ArrayList<>();
        for (var l : o.lines())
            out.add(l.machine() == null ? "#" : l.machine().machine().name());
        return out;
    }

    @Test
    void sortsByWhatTheChosenWindowUsedBiggestFirst() {
        var ms = List.of(mach("Small", 1, "A", row("x", 1, 5, 10)), mach("Big", 2, "A", row("x", 1, 50, 100)),
                mach("Mid", 3, "A", row("x", 1, 20, 20)));
        assertEquals(List.of("Big", "Mid", "Small"), names(build(ms, 1, false, Sort.AMOUNT, false, "")));
        assertEquals(List.of("Small", "Mid", "Big"), names(build(ms, 1, false, Sort.AMOUNT, true, "")));
    }

    @Test
    void theWindowChangesTheOrder() {
        var ms = List.of(mach("Burst", 1, "A", row("x", 0, 5, 500)), mach("Steady", 2, "A", row("x", 10, 40, 100)));
        assertEquals(List.of("Burst", "Steady"), names(build(ms, 2, false, Sort.AMOUNT, false, "")));
        assertEquals(List.of("Steady", "Burst"), names(build(ms, 0, false, Sort.AMOUNT, false, "")));
    }

    @Test
    void rateSortsByTheLastMinuteWhateverWindowIsShown() {
        var ms = List.of(mach("Burst", 1, "A", row("x", 0, 5, 500)), mach("Steady", 2, "A", row("x", 10, 40, 100)));
        assertEquals(List.of("Burst", "Steady"), names(build(ms, 2, false, Sort.AMOUNT, false, "")));
        assertEquals(List.of("Steady", "Burst"), names(build(ms, 2, false, Sort.RATE, false, "")));
    }

    @Test
    void nameSortsAlphabeticallyIgnoringCase() {
        var ms = List.of(mach("beta", 1, "A"), mach("Alpha", 2, "A"), mach("gamma", 3, "A"));
        assertEquals(List.of("Alpha", "beta", "gamma"), names(build(ms, 1, false, Sort.NAME, true, "")));
        assertEquals(List.of("gamma", "beta", "Alpha"), names(build(ms, 1, false, Sort.NAME, false, "")));
    }

    @Test
    void fluidsAndItemsWeighAlikeInUnits() {
        // 64 items against 2000 mB (2 buckets)
        var ms = List.of(mach("Items", 1, "A", row("iron", 0, 64, 64)), mach("Fluid", 2, "A", new Row("water", 0, 2000, 2000, 1000)));
        assertEquals(List.of("Items", "Fluid"), names(build(ms, 1, false, Sort.AMOUNT, false, "")));
        var big = List.of(mach("Items", 1, "A", row("iron", 0, 64, 64)), mach("Fluid", 2, "A", new Row("water", 0, 200_000, 200_000, 1000)));
        assertEquals(List.of("Fluid", "Items"), names(build(big, 1, false, Sort.AMOUNT, false, "")));
    }

    @Test
    void searchByMachineNameKeepsAllItsMaterials() {
        var ms = List.of(mach("Furnace", 1, "A", row("coal", 1, 1, 1), row("iron", 2, 2, 2)), mach("Press", 2, "A", row("gold", 3, 3, 3)));
        var o = build(ms, 1, false, Sort.NAME, true, "furn");
        assertEquals(List.of("Furnace"), names(o));
        assertEquals(2, o.lines().get(0).machine().rows().size());
    }

    @Test
    void searchByItemKeepsOnlyMachinesThatUsedItAndOnlyItsRows() {
        var ms = List.of(mach("A", 1, "I", row("iron ingot", 1, 1, 1), row("coal", 2, 2, 2)), mach("B", 2, "I", row("gold", 3, 3, 3)),
                mach("C", 3, "I", row("iron ore", 5, 5, 5)));
        var o = build(ms, 1, false, Sort.AMOUNT, false, "IRON");
        assertEquals(List.of("C", "A"), names(o), "B used no iron; the order counts the iron only");
        assertEquals(1, o.find(ms.get(0).id()).rows().size());
        assertEquals(2, o.find(ms.get(0).id()).allRows().size(), "the detail still shows everything");
        assertNull(o.find(ms.get(1).id()));
        assertTrue(build(ms, 1, false, Sort.NAME, true, "copper").lines().isEmpty());
    }

    @Test
    void searchByPlaceFindsAMachineByItsCoordinates() {
        var ms = List.of(mach("A", 11, "I"), mach("B", 22, "I"));
        assertEquals(List.of("B"), names(build(ms, 1, false, Sort.NAME, true, "22, 64")));
    }

    @Test
    void searchByInterfaceNameFindsItsMachines() {
        var ms = List.of(mach("A", 1, "North wing"), mach("B", 2, "South wing"));
        assertEquals(List.of("B"), names(build(ms, 1, false, Sort.NAME, true, "south")));
    }

    @Test
    void groupingPutsMachinesUnderTheirInterfaceInOrder() {
        var ms = List.of(mach("m1", 1, "North", row("x", 0, 1, 1)), mach("m2", 2, "South", row("x", 0, 9, 9)),
                mach("m3", 3, "North", row("x", 0, 5, 5)));
        var o = build(ms, 1, true, Sort.AMOUNT, false, "");
        // South holds the biggest machine, so it comes first; inside North the bigger m3 comes before m1
        assertEquals(List.of("#", "m2", "#", "m3", "m1"), names(o));
        assertTrue(o.lines().get(1).indent());
        assertTrue(!o.lines().get(0).indent());
        assertEquals(1, o.lines().get(0).count());
        assertEquals(2, o.lines().get(2).count());
        var byName = build(ms, 1, true, Sort.NAME, true, "");
        assertEquals(List.of("#", "m1", "m3", "#", "m2"), names(byName), "interfaces by their name: North before South");
    }

    @Test
    void theSameMachineThroughTwoInterfacesIsTwoEntries() {
        var ms = List.of(mach("m", 1, "A", row("x", 0, 1, 1)), mach("m", 1, "B", row("x", 0, 2, 2)));
        var o = build(ms, 1, false, Sort.AMOUNT, false, "");
        assertEquals(2, o.lines().size());
        assertNotNull(o.find(ms.get(0).id()));
        assertNotNull(o.find(ms.get(1).id()));
        assertNull(o.find("nothing"));
    }

    @Test
    void allRowsAreSortedByTheChosenWindow() {
        var ms = List.of(mach("m", 1, "A", row("a", 1, 1, 90), row("b", 9, 9, 10), row("c", 5, 5, 50)));
        var rows = build(ms, 0, false, Sort.AMOUNT, false, "").lines().get(0).machine().allRows();
        assertEquals(List.of("b", "c", "a"), rows.stream().map(Row::item).toList());
        var long30 = build(ms, 2, false, Sort.AMOUNT, false, "").lines().get(0).machine().allRows();
        assertEquals(List.of("a", "c", "b"), long30.stream().map(Row::item).toList());
    }

    @Test
    void machinesThatUsedNothingAreStillListed() {
        var ms = List.of(mach("Idle", 1, "A"), mach("Busy", 2, "A", row("x", 1, 1, 1)));
        assertEquals(List.of("Busy", "Idle"), names(build(ms, 1, false, Sort.AMOUNT, false, "")));
    }

    @Test
    void ratePerMinuteFollowsTheSpan() {
        assertEquals(384, UsageOrder.perMinute(1920, 6000));        // 1920 in 5 minutes
        assertEquals(64, UsageOrder.perMinute(64, 1200));
        assertEquals(768, UsageOrder.perMinute(1920, 3000), "a shorter span (the ledger is younger) makes the rate higher");
        assertEquals(64 * 6, UsageOrder.perMinute(64, 200), "ten seconds is the shortest span used");
        assertEquals(64 * 6, UsageOrder.perMinute(64, 5), "so the first moments do not give a huge rate");
        assertEquals(Long.MAX_VALUE, UsageOrder.perMinute(Long.MAX_VALUE, 200));
    }
}
