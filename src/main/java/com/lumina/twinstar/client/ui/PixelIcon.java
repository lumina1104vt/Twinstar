package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;

/** A small one-colour icon drawn from a pixel pattern ('#' = set), as {@link MarkIcon}. */
public class PixelIcon extends UIElement {
    /** the crafting graph button: a node with two branches */
    public static final String[] TREE = {
            "...###...",
            "...#.#...",
            "...###...",
            "....#....",
            ".#######.",
            ".#.....#.",
            "###...###",
            "#.#...#.#",
            "###...###"};
    /** a loop of the crafting graph: a ring with an arrow head */
    public static final String[] LOOP = {
            "..###.#",
            ".#...##",
            "#...###",
            "#......",
            "#.....#",
            ".#...#.",
            "..###.."};

    /** "missing" on the crafting graph (drawn dark on an orange tab) */
    public static final String[] EXCLAIM = {
            ".##.",
            ".##.",
            ".##.",
            ".##.",
            ".##.",
            "....",
            ".##.",
            ".##."};
    /** 強制下單: a bolt (start now, whatever is lacking) */
    public static final String[] FORCE = {
            "...###.",
            "..###..",
            ".###...",
            "#######",
            "...###.",
            "..###..",
            ".##...."};
    /** an infinite source */
    public static final String[] INFINITE = {
            ".##...##.",
            "#..#.#..#",
            "#...#...#",
            "#..#.#..#",
            ".##...##."};

    /** open / close / still loading (the theory viewer's items) */
    public static final String[] PLUS = {
            "..#..",
            "..#..",
            "#####",
            "..#..",
            "..#.."};
    /** the theory viewer button: a magnifying glass */
    public static final String[] LENS = {
            ".###....",
            "#...#...",
            "#...#...",
            "#...#...",
            ".####...",
            "....##..",
            ".....##.",
            "......##"};
    public static final String[] MINUS = {
            ".....",
            ".....",
            "#####",
            ".....",
            "....."};
    public static final String[] DOTS = {
            ".....",
            ".....",
            "#.#.#",
            ".....",
            "....."};
    /** the engine's own choice of route */
    public static final String[] STAR = {
            "...#...",
            "...#...",
            "#######",
            ".#####.",
            "..###..",
            ".##.##.",
            "#.....#"};

    private final String[] pixels;
    private final int color;

    public PixelIcon(String[] pixels, int color) {
        this.pixels = pixels;
        this.color = color;
        layout(layout -> layout.width(pixels[0].length()).height(pixels.length).flexShrink(0));
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        draw(context.graphics, pixels, (int) getPositionX(), (int) getPositionY(), color);
    }

    /** The pattern at (x, y), outside LDLib2. */
    public static void draw(GuiGraphics g, String[] pixels, int x, int y, int color) {
        for (int row = 0; row < pixels.length; row++)
            for (int col = 0; col < pixels[row].length(); col++)
                if (pixels[row].charAt(col) == '#')
                    g.fill(x + col, y + row, x + col + 1, y + row + 1, color);
    }
}
