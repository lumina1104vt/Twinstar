package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lumina.twinstar.order.ItemMark;

/** A plan-tree mark as a 7x7 pixel icon: auto = hollow ring, avoid = arrow down, free = check mark, keep = padlock. */
public class MarkIcon extends UIElement {
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

    private final String[] pixels;
    private final int color;

    public MarkIcon(ItemMark mark, int color) {
        this.pixels = switch (mark) {
            case AUTO -> AUTO;
            case AVOID -> AVOID;
            case FREE -> FREE;
            case KEEP -> KEEP;
        };
        this.color = color;
        layout(layout -> layout.width(7).height(7).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        int x = (int) getPositionX(), y = (int) getPositionY();
        for (int row = 0; row < pixels.length; row++)
            for (int col = 0; col < pixels[row].length(); col++)
                if (pixels[row].charAt(col) == '#')
                    context.graphics.fill(x + col, y + row, x + col + 1, y + row + 1, color);
    }
}
