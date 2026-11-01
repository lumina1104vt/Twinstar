package com.lumina.twinstar.client.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import net.minecraft.network.chat.Component;

/**
 * An on/off switch with a label: a 22x12 track whose knob slides right (violet) when on. A click
 * anywhere on the switch or its label flips it.
 */
public class PixelToggle extends UIElement {
    private static final int EDGE = 0xFF4E4474, HOVER_EDGE = 0xFFA082F0;
    private static final int OFF_FILL = 0xFF100E18, OFF_KNOB = 0xFF5A5478;
    private static final int ON_FILL = 0xFF5C3CA0, ON_EDGE = 0xFF6E5AAA, ON_KNOB = 0xFFECE6FF;

    private boolean on;
    private final Runnable onChange;

    public PixelToggle(Component label, boolean on, Runnable onChange) {
        this.on = on;
        this.onChange = onChange;
        UIElement track = new UIElement() {
            @Override
            public void drawBackgroundAdditional(GUIContext context) {
                drawTrack(context, (int) getPositionX(), (int) getPositionY());
            }
        };
        track.layout(l -> l.width(22).height(12).flexShrink(0));
        PixelLabel text = new PixelLabel(label);
        text.layout(l -> l.marginLeft(4));
        addChildren(track, text);
        layout(l -> l.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).height(12));
        addEventListener(UIEvents.MOUSE_DOWN, e -> {
            if (e.button == 0) {
                this.on = !this.on;
                onChange.run();
            }
        });
    }

    public boolean isOn() {
        return on;
    }

    private void drawTrack(GUIContext context, int x, int y) {
        var g = context.graphics;
        g.fill(x, y, x + 22, y + 12, isHover() ? HOVER_EDGE : on ? ON_EDGE : EDGE);
        g.fill(x + 1, y + 1, x + 21, y + 11, on ? ON_FILL : OFF_FILL);
        int knob = on ? x + 12 : x + 2;
        g.fill(knob, y + 2, knob + 8, y + 10, on ? ON_KNOB : OFF_KNOB);
    }
}
