package com.lumina.twinstar.client;

import com.lumina.twinstar.Twinstar;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * The rotating twin star in the top-left corner of the twin-star screens: a black and a white hole
 * on one orbit over a pixel-lensed star field, a strip of {@link #FRAMES} frames
 * (textures/gui/twin_star.png, made by tools/twinstar_gui_star.py).
 */
public final class TwinStarBadge {
    public static final int SIZE = 32;
    private static final int FRAMES = 48;
    private static final int MILLIS_PER_FRAME = 80;
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "textures/gui/twin_star.png");

    private TwinStarBadge() {
    }

    public static void draw(GuiGraphics g, int x, int y) {
        int frame = (int) (Util.getMillis() / MILLIS_PER_FRAME % FRAMES);
        g.blit(TEXTURE, x, y, 0, frame * SIZE, SIZE, SIZE, SIZE, SIZE * FRAMES);
    }
}
