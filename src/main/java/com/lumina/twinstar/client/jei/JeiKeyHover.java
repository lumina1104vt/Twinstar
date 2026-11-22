package com.lumina.twinstar.client.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.client.hover.HoverKeys;
import com.lumina.twinstar.client.hover.KeyHit;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Tells JEI which material our hand-drawn screens show under the mouse, so JEI's keys (A: bookmark,
 * R and U: recipes and uses) work there as on an item in a slot. Items and fluids only: JEI knows no other kind.
 */
final class JeiKeyHover implements IGlobalGuiHandler {
    @Override
    public Collection<Rect2i> getGuiExtraAreas() {
        // JEI only listens to a screen's mouse when it has an area: a full-screen plain screen is all area
        Screen screen = Minecraft.getInstance().screen;
        if (HoverKeys.isPlainKeyScreen(screen))
            return List.of(new Rect2i(0, 0, screen.width, screen.height));
        return List.of();
    }

    @Override
    public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory,
                                                                                         double mouseX, double mouseY) {
        KeyHit hit = HoverKeys.at(Minecraft.getInstance().screen, mouseX, mouseY);
        if (hit == null)
            return Optional.empty();
        Rect2i area = new Rect2i(hit.x(), hit.y(), hit.w(), hit.h());
        AEKey key = hit.key();
        if (key instanceof AEItemKey item)
            return factory.createBuilder(VanillaTypes.ITEM_STACK, item.toStack()).buildWithArea(area);
        if (key instanceof AEFluidKey fluid)
            return factory.createBuilder(NeoForgeTypes.FLUID_STACK, fluid.toStack(FluidType.BUCKET_VOLUME)).buildWithArea(area);
        return Optional.empty();
    }
}
