package com.lumina.twinstar.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** A scrolling place's offset and its bar (the wheel moves it, the bar shows where it is and can be dragged). */
public final class Scroller {
    public int offset;
    /** the bar as it was drawn last: where a press lands */
    private int barX, barY, barW = 5, barH, barContent, barView;
    private boolean drawn;
    /** where in the thumb the mouse holds it while dragging, -1 when it is not dragged */
    private int grab = -1;

    public void clamp(int content, int view) {
        offset = Math.max(0, Math.min(offset, Math.max(0, content - view)));
    }

    public boolean wheel(double delta, int content, int view, int step) {
        if (content <= view)
            return false;
        offset -= (int) Math.signum(delta) * step;
        clamp(content, view);
        return true;
    }

    public void reset() {
        offset = 0;
    }

    /** The 5 pixel bar at (x, y) of height {@code h}. */
    public void drawBar(GuiGraphics g, int x, int y, int h, int content, int view) {
        drawBar(g, x, y, 5, h, content, view);
    }

    public void drawBar(GuiGraphics g, int x, int y, int w, int h, int content, int view) {
        barX = x;
        barY = y;
        barW = w;
        barH = h;
        barContent = content;
        barView = view;
        drawn = true;
        g.fill(x, y, x + w, y + h, 0xFF040406);
        if (content <= view)
            return;
        int thumb = thumb();
        int top = y + (h - thumb) * offset / Math.max(1, content - view);
        g.fill(x, top, x + w, top + thumb, 0xFF7864B4);
        if (w >= 5)
            g.fill(x + 1, top + 1, x + w - 1, top + thumb - 1, 0xFF463E68);
    }

    private int thumb() {
        return Math.max(8, barH * barView / Math.max(1, barContent));
    }

    /** A press on the bar: on the thumb it takes hold of it, on the track the thumb jumps there. True when it was on the bar. */
    public boolean press(double mx, double my) {
        if (!drawn || barContent <= barView || mx < barX - 3 || mx >= barX + barW + 3 || my < barY || my >= barY + barH)
            return false;
        int thumb = thumb();
        int top = barY + (barH - thumb) * offset / Math.max(1, barContent - barView);
        grab = my >= top && my < top + thumb ? (int) my - top : thumb / 2;
        drag(my);
        return true;
    }

    /** The mouse moved with the button down: the thumb follows. True while it is held. */
    public boolean drag(double my) {
        if (grab < 0)
            return false;
        int range = Math.max(1, barH - thumb());
        double t = Math.max(0, Math.min(1, (my - grab - barY) / range));
        offset = (int) Math.round(t * (barContent - barView));
        clamp(barContent, barView);
        return true;
    }

    public boolean release() {
        boolean was = grab >= 0;
        grab = -1;
        return was;
    }
}
