package com.lumina.twinstar.client.emi;

import com.lumina.twinstar.client.hover.HoverKeys;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHover;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.EmiStackInteraction;
import dev.emi.emi.api.widget.Bounds;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Tells EMI which material our hand-drawn screens show under the mouse, so EMI's keys (A: favourite,
 * R and U: recipes and uses) work there as on an item in a slot. Items and fluids only: EMI knows no other kind.
 * Loaded by EMI only.
 */
@EmiEntrypoint
public final class TwinstarEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addGenericStackProvider(TwinstarEmiPlugin::stackAt);
        registry.addGenericScreenBoundsProvider(TwinstarEmiPlugin::boundsOf);
    }

    private static EmiStackInteraction stackAt(Screen screen, int x, int y) {
        KeyHit hit = HoverKeys.at(screen, x, y);
        EmiStack stack = hit == null ? null : EmiBookmarks.stack(hit.key());
        return stack == null ? EmiStackInteraction.EMPTY : new EmiStackInteraction(stack);
    }

    /**
     * EMI only reads a screen's mouse when the screen has an area. A plain screen of ours is all area;
     * a container screen with no slots (the plan screen) has none by itself, so it gets its panel.
     * Everything else is left to EMI.
     */
    private static Bounds boundsOf(Screen screen) {
        if (HoverKeys.isPlainKeyScreen(screen))
            return new Bounds(0, 0, screen.width, screen.height);
        if (screen instanceof AbstractContainerScreen<?> c && screen instanceof KeyHover && c.getMenu().slots.isEmpty())
            return new Bounds(c.getGuiLeft(), c.getGuiTop(), c.getXSize(), c.getYSize());
        return Bounds.EMPTY;
    }
}
