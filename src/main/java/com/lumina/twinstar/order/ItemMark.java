package com.lumina.twinstar.order;

/**
 * A player's mark on one item of an order (plan screen), for that order only: how the planner should
 * treat the network's stock of it.
 */
public enum ItemMark {
    /** the planner's own prices */
    AUTO,
    /** 盡量不用: take routes that do not use it; still used when there is no other way */
    AVOID,
    /** 隨便用: treat it as free, use it first */
    FREE,
    /**
     * 保留: the network's stock of it is not touched, all of it or a set amount; what is still
     * needed is crafted, or missing when it cannot be
     */
    KEEP;

    public ItemMark next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
