package com.lumina.twinstar.client.ui;

/**
 * The moving of a small panel, apart from drawing it: where it is (its default place plus the player's
 * offset, held inside the window), a press that becomes a drag after a few pixels, and the click or double
 * click of a press that did not move. Needs no game ({@code PanelMoverTest}).
 */
public final class PanelMover {
    public enum Result { NONE, MOVED, CLICK, DOUBLE_CLICK }

    static final long DOUBLE_CLICK_MS = 350;
    private static final int DRAG_START = 3;

    private final String id;
    private final PanelPlacement placement;
    private int defX, defY, w, h, areaW, areaH;
    private int offX, offY;
    private boolean placed, pressing, dragging;
    private int dragged;
    private long lastClickMs = Long.MIN_VALUE / 2;

    public PanelMover(String id, PanelPlacement placement) {
        this.id = id;
        this.placement = placement;
    }

    /** Where the panel is now: sets its default place and size, and keeps it inside the window. Call every frame. */
    public void layout(int defaultX, int defaultY, int width, int height, int areaW, int areaH) {
        if (!placed) {
            int[] o = placement.get(id);
            offX = o[0];
            offY = o[1];
            placed = true;
        }
        this.defX = defaultX;
        this.defY = defaultY;
        this.w = width;
        this.h = height;
        this.areaW = areaW;
        this.areaH = areaH;
        offX = PanelPlacement.clamp(offX, defX, w, areaW);
        offY = PanelPlacement.clamp(offY, defY, h, areaH);
    }

    public int x() {
        return defX + offX;
    }

    public int y() {
        return defY + offY;
    }

    public int[] offset() {
        return new int[]{offX, offY};
    }

    public boolean isPressed() {
        return pressing;
    }

    /** A press on the part that moves the panel (its header). */
    public void press() {
        pressing = true;
        dragging = false;
        dragged = 0;
    }

    /** The mouse moved with the button down; true when this press is ours. */
    public boolean drag(double dx, double dy) {
        if (!pressing)
            return false;
        dragged += (int) Math.abs(dx) + (int) Math.abs(dy);
        if (dragged > DRAG_START)
            dragging = true;
        if (dragging) {
            offX = PanelPlacement.clamp(offX + (int) Math.round(dx), defX, w, areaW);
            offY = PanelPlacement.clamp(offY + (int) Math.round(dy), defY, h, areaH);
        }
        return true;
    }

    /**
     * The button came up. MOVED: a drag ended (its place is saved); CLICK: a press without moving; DOUBLE_CLICK:
     * the second click soon after one (the panel goes back to its default place); NONE: not our press.
     */
    public Result release(long nowMs) {
        if (!pressing)
            return Result.NONE;
        pressing = false;
        if (dragging) {
            dragging = false;
            placement.set(id, offX, offY);
            return Result.MOVED;
        }
        if (nowMs - lastClickMs <= DOUBLE_CLICK_MS) {
            lastClickMs = Long.MIN_VALUE / 2;
            reset();
            return Result.DOUBLE_CLICK;
        }
        lastClickMs = nowMs;
        return Result.CLICK;
    }

    public void reset() {
        offX = 0;
        offY = 0;
        placement.set(id, 0, 0);
    }
}
