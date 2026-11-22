package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.metrics.Rating;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The rating icons. Each level has its own SHAPE (a circle with a check, a triangle with an
 * exclamation mark, an octagon with a cross) as well as its own colour (blue, amber, pink), so they
 * read the same for someone who cannot tell red from green.
 */
public final class PerfIcons {
    public static final int SIZE = 7;
    public static final int BLUE = 0xFF63B3FF, AMBER = 0xFFFFB02E, PINK = 0xFFFF5C8A;

    private static final String[] NORMAL = {
            "..###..",
            ".#...#.",
            "#....##",
            "#.#.#.#",
            "#..#..#",
            ".#...#.",
            "..###.."};
    private static final String[] HIGH = {
            "...#...",
            "..#.#..",
            "..#.#..",
            ".#.#.#.",
            ".#...#.",
            "#..#..#",
            "#######"};
    private static final String[] VERY_HIGH = {
            ".#####.",
            "##...##",
            "#.#.#.#",
            "#..#..#",
            "#.#.#.#",
            "##...##",
            ".#####."};

    private PerfIcons() {
    }

    public static int color(Rating r) {
        return switch (r) {
            case NORMAL -> BLUE;
            case HIGH -> AMBER;
            case VERY_HIGH -> PINK;
        };
    }

    /** The symbol used in text (tooltips): the same three shapes. */
    public static String symbol(Rating r) {
        return switch (r) {
            case NORMAL -> "●";
            case HIGH -> "▲";
            case VERY_HIGH -> "■";
        };
    }

    public static void draw(GuiGraphics g, Rating r, int x, int y) {
        String[] pixels = switch (r) {
            case NORMAL -> NORMAL;
            case HIGH -> HIGH;
            case VERY_HIGH -> VERY_HIGH;
        };
        int color = color(r);
        for (int row = 0; row < pixels.length; row++)
            for (int col = 0; col < pixels[row].length(); col++)
                if (pixels[row].charAt(col) == '#')
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
    }
}
