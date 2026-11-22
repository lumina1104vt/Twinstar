package com.lumina.twinstar.client.hover;

import com.lumina.twinstar.Twinstar;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

/** The one place JEI and EMI ask which material a screen of ours shows under the mouse. */
public final class HoverKeys {
    private static boolean failed;

    private HoverKeys() {
    }

    /** What {@code screen} draws at the point, or null (also when the screen is not a {@link KeyHover}). */
    public static @Nullable KeyHit at(@Nullable Screen screen, double mouseX, double mouseY) {
        if (!(screen instanceof KeyHover hover))
            return null;
        try {
            return hover.keyAt(mouseX, mouseY);
        } catch (RuntimeException e) {
            // a broken hover test must never break JEI / EMI's input: say it once and carry on
            if (!failed) {
                failed = true;
                Twinstar.LOGGER.error("[雙星] 讀取滑鼠下的材料時出錯（書籤功能暫時失效）", e);
            }
            return null;
        }
    }

    /**
     * A screen of ours that is no container screen: JEI and EMI only treat a screen as theirs to
     * look at when it has an area, so these tell them the whole screen is one.
     */
    public static boolean isPlainKeyScreen(@Nullable Screen screen) {
        return screen instanceof KeyHover && !(screen instanceof AbstractContainerScreen<?>);
    }
}
