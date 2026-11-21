package com.lumina.twinstar.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import com.lumina.twinstar.order.ItemMark;

/** A plan-tree mark as a 7x7 pixel icon: auto = hollow ring, avoid = arrow down, free = check mark, keep = padlock. */
public final class MarkIcon {
    private static final String[] AUTO = {
            "..###..",
            ".#...#.",
            "#.....#",
            "#.....#",
            "#.....#",
            ".#...#.",
            "..###.."};
    private static final String[] AVOID = {
            "...#...",
            "...#...",
            "...#...",
            "#..#..#",
            ".#.#.#.",
            "..###..",
            "...#..."};
    private static final String[] KEEP = {
            "..###..",
            ".#...#.",
            ".#...#.",
            "#######",
            "###.###",
            "###.###",
            "#######"};
    private static final String[] FREE = {
            "......#",
            ".....##",
            "#...##.",
            "##.##..",
            ".###...",
            "..#....",
            "......."};

    private MarkIcon() {
    }

    /** The mark's 7 x 7 icon at (x, y). */
    public static void draw(GuiGraphics g, ItemMark mark, int x, int y, int color) {
        String[] pixels = switch (mark) {
            case AUTO -> AUTO;
            case AVOID -> AVOID;
            case FREE -> FREE;
            case KEEP -> KEEP;
        };
        for (int row = 0; row < pixels.length; row++)
            for (int col = 0; col < pixels[row].length(); col++)
                if (pixels[row].charAt(col) == '#')
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
    }
}
