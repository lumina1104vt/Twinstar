package com.lumina.twinstar.client.hover;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The squares a hand-drawn screen reports to JEI / EMI (keys are left null: AE2's key types need a running game). */
class KeyHitsTest {
    @Test
    void emptyFindsNothing() {
        assertNull(new KeyHits().at(5, 5));
    }

    @Test
    void edgesAreLeftAndTopInclusiveRightAndBottomExclusive() {
        KeyHits hits = new KeyHits();
        hits.add(null, 10, 20, 16, 16);
        assertEquals(10, hits.at(10, 20).x());
        assertEquals(20, hits.at(25.9, 35.9).y());
        assertNull(hits.at(9.9, 20));
        assertNull(hits.at(10, 19.9));
        assertNull(hits.at(26, 30));
        assertNull(hits.at(15, 36));
    }

    @Test
    void whatWasAddedLastLiesOnTop() {
        KeyHits hits = new KeyHits();
        hits.add(null, 0, 0, 100, 100);     // a whole panel
        hits.add(null, 10, 10, 16, 16);     // an icon in it
        assertEquals(16, hits.at(12, 12).w());
        assertEquals(100, hits.at(50, 50).w());
    }

    @Test
    void emptySquaresAreIgnoredAndClearForgetsAll() {
        KeyHits hits = new KeyHits();
        hits.add(null, 5, 5, 0, 16);
        hits.add(null, 5, 5, 16, -1);
        assertEquals(0, hits.size());
        hits.add(null, 5, 5, 16, 16);
        assertEquals(1, hits.size());
        hits.clear();
        assertNull(hits.at(6, 6));
        assertEquals(0, hits.size());
    }

    @Test
    void theSameHitObjectIsReturned() {
        KeyHits hits = new KeyHits();
        hits.add(null, 1, 1, 4, 4);
        assertSame(hits.at(2, 2), hits.at(3, 3));
    }
}
