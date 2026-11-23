package com.lumina.twinstar.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static com.lumina.twinstar.client.ui.PanelMover.Result.CLICK;
import static com.lumina.twinstar.client.ui.PanelMover.Result.DOUBLE_CLICK;
import static com.lumina.twinstar.client.ui.PanelMover.Result.MOVED;
import static com.lumina.twinstar.client.ui.PanelMover.Result.NONE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The memory panel's click, drag and double click (no game window needed). */
class PanelMoverTest {
    private static PanelMover mover(Path dir) {
        PanelMover m = new PanelMover("t", new PanelPlacement(dir.resolve("p.properties")));
        m.layout(10, 100, 200, 80, 400, 300);
        return m;
    }

    @Test
    void aDragMovesItAndIsRemembered(@TempDir Path dir) {
        PanelMover m = mover(dir);
        m.press();
        assertTrue(m.drag(6, 4));
        assertTrue(m.drag(20, -30));
        assertEquals(MOVED, m.release(1000));
        assertArrayEquals(new int[]{26, -26}, m.offset());
        assertEquals(36, m.x());
        assertEquals(74, m.y());
        assertArrayEquals(new int[]{26, -26}, new PanelPlacement(dir.resolve("p.properties")).get("t"));
        // a new session starts where it was left
        PanelMover again = mover(dir);
        assertEquals(36, again.x());
    }

    @Test
    void itStaysInsideTheWindow(@TempDir Path dir) {
        PanelMover m = mover(dir);
        m.press();
        m.drag(-500, -500);
        assertArrayEquals(new int[]{-10, -100}, m.offset());
        m.drag(5000, 5000);
        assertArrayEquals(new int[]{400 - 200 - 10, 300 - 80 - 100}, m.offset());
    }

    @Test
    void aSmallerWindowPullsItBackIn(@TempDir Path dir) {
        PanelMover m = mover(dir);
        m.press();
        m.drag(150, 100);
        m.release(0);
        m.layout(10, 100, 200, 80, 300, 250);
        assertEquals(100, m.x());
        assertEquals(170, m.y());
    }

    @Test
    void aClickThenADoubleClickGoesHome(@TempDir Path dir) {
        PanelMover m = mover(dir);
        m.press();
        m.drag(30, 30);
        assertEquals(MOVED, m.release(0));
        m.press();
        assertEquals(CLICK, m.release(10_000));
        m.press();
        assertEquals(DOUBLE_CLICK, m.release(10_200));
        assertArrayEquals(new int[]{0, 0}, m.offset());
        m.press();
        assertEquals(CLICK, m.release(10_300), "a third click starts a new pair");
    }

    @Test
    void aSmallWobbleIsStillAClickAndNoPressMeansNothing(@TempDir Path dir) {
        PanelMover m = mover(dir);
        assertFalse(m.drag(5, 5));
        assertEquals(NONE, m.release(0));
        m.press();
        m.drag(1, 1);
        assertEquals(CLICK, m.release(10_000));
        assertArrayEquals(new int[]{0, 0}, m.offset());
    }
}
