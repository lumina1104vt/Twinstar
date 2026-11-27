package com.lumina.twinstar.algorithm.execution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowBatchTest {
    /** A place with room for 100 runs is offered 500: the offers come down to what it takes. */
    @Test
    void aRefusedBatchShrinksUntilItFits() {
        Window w = new Window();
        long offer = 500;
        int refused = 0;
        while (offer > 100) {
            w.onBatchResult(offer, false);
            refused++;
            offer = Math.min(500, w.batchLimit);
        }
        assertEquals(62, offer);
        assertEquals(3, refused);
        w.onBatchResult(offer, true);
        assertEquals(124, w.batchLimit);    // and grows again once it takes them
    }

    @Test
    void aBusyPlaceTeachesNothing() {
        Window w = new Window();
        w.onBatchResult(4, false);
        w.onBatchResult(2, false);
        w.onBatchResult(1, false);
        assertEquals(Long.MAX_VALUE, w.batchLimit);
        w.onBatchResult(Long.MAX_VALUE, true);
        assertTrue(w.batchLimit == Long.MAX_VALUE);
    }
}
