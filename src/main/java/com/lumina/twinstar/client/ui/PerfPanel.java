package com.lumina.twinstar.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A small panel with the memory and speed of our own data: a header (rating icon, title, rating word;
 * a click folds it, dragging it moves the panel, a double click puts it back) and one row per number with an icon where the row is rated. Every row and the
 * header have a tooltip ({@link #tooltipAt}). Drawn with plain {@code GuiGraphics}, so any screen can
 * carry one.
 */
public final class PerfPanel {
    public static final int WIDTH = 204, ROW_H = 11, HEADER_H = 14;
    private static final int BG = 0xE8101018, LINE = 0xFF4E4474, LINE_HOT = 0xFFA082F0, TEXT = 0xFFDEDEEA, DIM = 0xFF9A94B8;
    private static final int BAR_BG = 0xFF2A2740, BAR = 0xFF7C6CD0;

    private final PanelMover mover;
    private boolean open;
    private int x, y, h;
    private final List<int[]> rowRects = new ArrayList<>();       // x, y, w, h per drawn row
    private final List<PerfContent.Row> rowsDrawn = new ArrayList<>();
    private @Nullable PerfContent.Content drawn;

    /** @param id names the panel in the file that remembers where the player put it */
    public PerfPanel(boolean openAtStart, String id) {
        this(openAtStart, id, PanelPlacement.shared());
    }

    PerfPanel(boolean openAtStart, String id, PanelPlacement placement) {
        this.open = openAtStart;
        this.mover = new PanelMover(id, placement);
    }

    public boolean isOpen() {
        return open;
    }

    public void toggle() {
        open = !open;
    }

    /** Height this content needs at the panel's width (fold state included). */
    public int heightFor(PerfContent.Content c) {
        return HEADER_H + (open ? c.rows().size() * ROW_H + 3 : 0);
    }

    /** Height with the rows shown: a default place is worked out from it, so folding does not move the header. */
    public int openHeightFor(PerfContent.Content c) {
        return HEADER_H + c.rows().size() * ROW_H + 3;
    }

    /**
     * Draws the panel. Its default place has the top left at (defaultX, defaultY); where the player moved it to
     * is added, and it is held inside the window (areaW x areaH).
     */
    public void draw(GuiGraphics g, Font font, int defaultX, int defaultY, int areaW, int areaH, PerfContent.Content c, int mx, int my) {
        this.h = heightFor(c);
        mover.layout(defaultX, defaultY, WIDTH, h, areaW, areaH);
        int px = this.x = mover.x();
        int py = this.y = mover.y();
        this.drawn = c;
        rowRects.clear();
        rowsDrawn.clear();
        boolean overHeader = mx >= px && mx < px + WIDTH && my >= py && my < py + HEADER_H;
        g.fill(px, py, px + WIDTH, py + h, LINE);
        g.fill(px + 1, py + 1, px + WIDTH - 1, py + h - 1, BG);
        if (overHeader)
            g.fill(px + 1, py + 1, px + WIDTH - 1, py + HEADER_H, 0xFF2C2642);
        PerfIcons.draw(g, c.rating(), px + 4, py + 3);
        g.drawString(font, c.title(), px + 15, py + 3, TEXT, false);
        Component word = Component.translatable("gui.twinstar.perf.rating." + PerfContent.ratingKey(c.rating()));
        g.drawString(font, word, px + WIDTH - 5 - font.width(word) - 9, py + 3, PerfIcons.color(c.rating()), false);
        g.drawString(font, open ? "−" : "+", px + WIDTH - 10, py + 3, DIM, false);
        if (!open)
            return;
        int ry = py + HEADER_H + 1;
        for (PerfContent.Row r : c.rows()) {
            boolean hot = mx >= px && mx < px + WIDTH && my >= ry && my < ry + ROW_H;
            if (hot)
                g.fill(px + 1, ry, px + WIDTH - 1, ry + ROW_H, 0x802C2642);
            if (r.rating() != null)
                PerfIcons.draw(g, r.rating(), px + 4, ry + 2);
            g.drawString(font, r.label(), px + 15, ry + 1, DIM, false);
            int vw = font.width(r.value());
            g.drawString(font, r.value(), px + WIDTH - 5 - vw, ry + 1, TEXT, false);
            if (r.fraction() >= 0) {        // a thin bar under the row: this part's share
                int bw = WIDTH - 20;
                g.fill(px + 15, ry + ROW_H - 2, px + 15 + bw, ry + ROW_H - 1, BAR_BG);
                g.fill(px + 15, ry + ROW_H - 2, px + 15 + Math.max(1, Math.round(bw * Math.min(1f, r.fraction()))), ry + ROW_H - 1, BAR);
            }
            rowRects.add(new int[]{px, ry, WIDTH, ROW_H});
            rowsDrawn.add(r);
            ry += ROW_H;
        }
    }

    public boolean contains(double mx, double my) {
        return drawn != null && mx >= x && mx < x + WIDTH && my >= y && my < y + h;
    }

    private boolean onHeader(double mx, double my) {
        return contains(mx, my) && my < y + HEADER_H;
    }

    /**
     * A press on the panel: on the header it may start a move (or a click); true when the press was on the panel
     * at all, so the screen behind does not get it.
     */
    public boolean mousePressed(double mx, double my, int button) {
        if (!contains(mx, my))
            return false;
        if (button == 0 && onHeader(mx, my))
            mover.press();
        return true;
    }

    /** The mouse moved with the button down: after a few pixels the header drags the panel. True when it did. */
    public boolean mouseDragged(double dx, double dy) {
        return mover.drag(dx, dy);
    }

    /** True while the header is held (no tooltip then). */
    public boolean isPressed() {
        return mover.isPressed();
    }

    /**
     * The button came up: a move ends (and is remembered); a plain click folds or unfolds the panel; a second
     * click soon after puts the panel back in its default place. True when the press was ours.
     */
    public boolean mouseReleased(double mx, double my, int button) {
        if (button != 0)
            return false;
        switch (mover.release(System.currentTimeMillis())) {
            case CLICK -> toggle();
            case DOUBLE_CLICK -> toggle();      // the first click of the pair folded it: back again
            case NONE -> {
                return false;
            }
            default -> {
            }
        }
        return true;
    }

    /** The tooltip of what the mouse is over (header or row), or null. */
    public @Nullable List<Component> tooltipAt(double mx, double my) {
        if (!contains(mx, my) || drawn == null)
            return null;
        if (my < y + HEADER_H)
            return drawn.headerTooltip();
        for (int i = 0; i < rowRects.size(); i++) {
            int[] r = rowRects.get(i);
            if (my >= r[1] && my < r[1] + r[3])
                return rowsDrawn.get(i).tooltip();
        }
        return null;
    }
}
