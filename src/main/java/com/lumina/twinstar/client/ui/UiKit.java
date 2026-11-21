package com.lumina.twinstar.client.ui;

import com.lumina.twinstar.Twinstar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * What the twin-star screens are drawn with (1.20.1 has no LDLib2): the black panel, the violet accents, Minecraft's pixel font,
 * and the three widgets they need - a button, an on/off switch and a scrolling place. Everything is positioned by the screen.
 */
public final class UiKit {
    public static final int TEXT = 0xFFDEDEEA, DIM = 0xFF8C8CA2, TITLE = 0xFFD6CCFF, GOOD = 0xFF78E296, BAD = 0xFFFF7474;
    public static final int EDGE = 0xFF4E4474, HOVER_EDGE = 0xFFA082F0;

    public static final ResourceLocation INSET = tex("inset"), INSET_MISSING = tex("inset_missing"), INSET_TRACK = tex("inset_track");
    public static final ResourceLocation ORDER_PANEL = tex("order_ui"), PLAN_PANEL = tex("plan_ui");

    private UiKit() {
    }

    private static ResourceLocation tex(String name) {
        return new ResourceLocation(Twinstar.MODID, "textures/gui/" + name + ".png");
    }

    /** A 16 x 16 texture stretched to a box with its corners (of {@code slice} pixels) kept as they are. */
    public static void nineSlice(GuiGraphics g, ResourceLocation t, int x, int y, int w, int h, int slice) {
        int s = slice, mid = 16 - 2 * s;
        g.blit(t, x, y, s, s, 0, 0, s, s, 16, 16);
        g.blit(t, x + w - s, y, s, s, 16 - s, 0, s, s, 16, 16);
        g.blit(t, x, y + h - s, s, s, 0, 16 - s, s, s, 16, 16);
        g.blit(t, x + w - s, y + h - s, s, s, 16 - s, 16 - s, s, s, 16, 16);
        g.blit(t, x + s, y, w - 2 * s, s, s, 0, mid, s, 16, 16);
        g.blit(t, x + s, y + h - s, w - 2 * s, s, s, 16 - s, mid, s, 16, 16);
        g.blit(t, x, y + s, s, h - 2 * s, 0, s, s, mid, 16, 16);
        g.blit(t, x + w - s, y + s, s, h - 2 * s, 16 - s, s, s, mid, 16, 16);
        g.blit(t, x + s, y + s, w - 2 * s, h - 2 * s, s, s, mid, mid, 16, 16);
    }

    /** A box with a one pixel edge. */
    public static void box(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        g.fill(x, y, x + w, y + h, edge);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, fill);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Text at 7/9 of the size (the plan's small lines), cut at {@code maxWidth} pixels. */
    public static void small(GuiGraphics g, Component text, int x, int y, int color, int maxWidth) {
        Font font = Minecraft.getInstance().font;
        float scale = 7f / 9f;
        g.enableScissor(x, y - 1, x + maxWidth, y + 9);
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
        g.disableScissor();
    }

    public static int smallWidth(Component text) {
        return (int) Math.ceil(Minecraft.getInstance().font.width(text) * 7f / 9f);
    }

    /** What a button draws on top of its edge and fill, besides its caption. */
    public interface Painter {
        void paint(GuiGraphics g, int x, int y, int w, int h, boolean hot);
    }

    /** The screens' button: dark with a violet edge that lights up under the mouse; a caption and/or a painted icon. */
    public static class PixelButton extends Button {
        private final Painter painter;

        public PixelButton(int x, int y, int w, int h, Component caption, Painter painter, Runnable onPress) {
            super(x, y, w, h, caption, b -> onPress.run(), DEFAULT_NARRATION);
            this.painter = painter;
        }

        public PixelButton(int x, int y, int w, int h, Component caption, Runnable onPress) {
            this(x, y, w, h, caption, null, onPress);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hot = active && isHoveredOrFocused();
            int edge = !active ? 0xFF282634 : hot ? HOVER_EDGE : EDGE;
            int fill = !active ? 0xFF101016 : hot ? 0xFF2C2642 : 0xFF1E1C2C;
            // lifted above the AE2 toolbar's background where a screen puts one behind
            box(g, getX(), getY(), width, height, fill, edge);
            if (painter != null)
                painter.paint(g, getX(), getY(), width, height, hot);
            Component caption = getMessage();
            if (caption != null && !caption.getString().isEmpty()) {
                Font font = Minecraft.getInstance().font;
                g.drawString(font, caption, getX() + (width - font.width(caption)) / 2, getY() + (height - 8) / 2,
                        active ? TEXT : 0xFF64647A, false);
            }
        }
    }

    /** An on/off switch with a label: a 22 x 12 track whose knob slides right (violet) when on; a click anywhere on it flips it. */
    public static final class PixelSwitch extends Button {
        private static final int OFF_FILL = 0xFF100E18, OFF_KNOB = 0xFF5A5478;
        private static final int ON_FILL = 0xFF5C3CA0, ON_EDGE = 0xFF6E5AAA, ON_KNOB = 0xFFECE6FF;
        private boolean on;
        private final Runnable onChange;

        public PixelSwitch(int x, int y, Component label, boolean on, Runnable onChange) {
            super(x, y, 26 + Minecraft.getInstance().font.width(label), 12, label, b -> {
            }, DEFAULT_NARRATION);
            this.on = on;
            this.onChange = onChange;
        }

        public boolean isOn() {
            return on;
        }

        @Override
        public void onPress() {
            on = !on;
            onChange.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean hot = isHoveredOrFocused();
            g.fill(getX(), getY(), getX() + 22, getY() + 12, hot ? HOVER_EDGE : on ? ON_EDGE : EDGE);
            g.fill(getX() + 1, getY() + 1, getX() + 21, getY() + 11, on ? ON_FILL : OFF_FILL);
            int knob = on ? getX() + 12 : getX() + 2;
            g.fill(knob, getY() + 2, knob + 8, getY() + 10, on ? ON_KNOB : OFF_KNOB);
            g.drawString(Minecraft.getInstance().font, getMessage(), getX() + 26, getY() + 2, TEXT, false);
        }
    }

    /** A scrolling place's offset and its bar (the wheel moves it; the bar shows where it is). */
    public static final class Scroller {
        public int offset;

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
            g.fill(x, y, x + 5, y + h, 0xFF040406);
            if (content <= view)
                return;
            int thumb = Math.max(8, h * view / content);
            int top = y + (h - thumb) * offset / Math.max(1, content - view);
            g.fill(x, top, x + 5, top + thumb, 0xFF7864B4);
            g.fill(x + 1, top + 1, x + 4, top + thumb - 1, 0xFF463E68);
        }
    }
}
