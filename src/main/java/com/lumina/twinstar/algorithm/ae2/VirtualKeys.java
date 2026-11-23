package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEKey;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Keys that are only a mark and no real item (a mod registers its dispatch marker here): a product of one is done on
 * dispatch and lives in the job's ledger only, never in the network, a machine or the requester's hands.
 */
public final class VirtualKeys {
    private static final CopyOnWriteArrayList<Predicate<AEKey>> TESTS = new CopyOnWriteArrayList<>();

    private VirtualKeys() {
    }

    public static void register(Predicate<AEKey> isMark) {
        TESTS.add(isMark);
    }

    public static boolean isVirtual(AEKey key) {
        for (Predicate<AEKey> t : TESTS)
            if (t.test(key))
                return true;
        return false;
    }
}
