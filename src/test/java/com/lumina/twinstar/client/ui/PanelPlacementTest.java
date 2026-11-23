package com.lumina.twinstar.client.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Where a moved panel stays: kept inside the window, remembered in a file, forgotten at (0, 0). */
class PanelPlacementTest {
    @Test
    void clampKeepsThePanelInsideTheWindow() {
        assertEquals(0, PanelPlacementclamp(0, 10, 50, 200));
        assertEquals(-10, PanelPlacementclamp(-500, 10, 50, 200));     // pos 0
        assertEquals(140, PanelPlacementclamp(500, 10, 50, 200));      // pos 150 = 200 - 50
        assertEquals(30, PanelPlacementclamp(30, 10, 50, 200));
        assertEquals(-10, PanelPlacementclamp(5, 10, 300, 200));       // wider than the window: pinned to 0
    }

    private static int PanelPlacementclamp(int off, int def, int size, int area) {
        return PanelPlacement.clamp(off, def, size, area);
    }

    @Test
    void offsetsSurviveAReload(@TempDir Path dir) {
        Path f = dir.resolve("config").resolve("panels.properties");
        PanelPlacement a = new PanelPlacement(f);
        assertArrayEquals(new int[]{0, 0}, a.get("cpu"));
        a.set("cpu", 12, -7);
        a.set("theory", -3, 40);
        PanelPlacement b = new PanelPlacement(f);
        assertArrayEquals(new int[]{12, -7}, b.get("cpu"));
        assertArrayEquals(new int[]{-3, 40}, b.get("theory"));
    }

    @Test
    void resetForgetsThePlace(@TempDir Path dir) {
        Path f = dir.resolve("p.properties");
        PanelPlacement a = new PanelPlacement(f);
        a.set("cpu", 5, 5);
        a.set("cpu", 0, 0);
        assertArrayEquals(new int[]{0, 0}, new PanelPlacement(f).get("cpu"));
    }

    @Test
    void aBrokenFileIsIgnored(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("p.properties");
        Files.writeString(f, "cpu=abc\ntheory=1,2\nodd=1,2,3\n");
        PanelPlacement p = new PanelPlacement(f);
        assertArrayEquals(new int[]{0, 0}, p.get("cpu"));
        assertArrayEquals(new int[]{1, 2}, p.get("theory"));
        assertFalse(p.get("odd")[0] != 0);
    }
}
