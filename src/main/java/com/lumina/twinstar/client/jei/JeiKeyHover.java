package com.lumina.twinstar.client.jei;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.hover.HoverKeys;
import com.lumina.twinstar.client.hover.KeyHit;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraftforge.fluids.FluidType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Tells JEI which material our hand-drawn screens show under the mouse, so JEI's keys (A: bookmark,
 * R and U: recipes and uses) work there as on an item in a slot. Items and fluids only: JEI knows no other kind.
 * Uses the oldest form of the handler (no ingredient factory), which every JEI 15.x calls.
 */
final class JeiKeyHover implements IGlobalGuiHandler {
    private static boolean failed;

    @Override
    public Collection<Rect2i> getGuiExtraAreas() {
        // JEI only listens to a screen's mouse when it has an area: a full-screen plain screen is all area
        Screen screen = Minecraft.getInstance().screen;
        if (HoverKeys.isPlainKeyScreen(screen))
            return List.of(new Rect2i(0, 0, screen.width, screen.height));
        return List.of();
    }

    @Override
    public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(double mouseX, double mouseY) {
        IJeiRuntime rt = JeiCollector.runtime;
        KeyHit hit = rt == null || failed ? null : HoverKeys.at(Minecraft.getInstance().screen, mouseX, mouseY);
        if (hit == null)
            return Optional.empty();
        try {
            Rect2i area = new Rect2i(hit.x(), hit.y(), hit.w(), hit.h());
            AEKey key = hit.key();
            if (key instanceof AEItemKey item)
                return rt.getIngredientManager().createClickableIngredient(VanillaTypes.ITEM_STACK, item.toStack(), area, true).map(c -> c);
            if (key instanceof AEFluidKey fluid)
                return rt.getIngredientManager().createClickableIngredient(ForgeTypes.FLUID_STACK, fluid.toStack(FluidType.BUCKET_VOLUME), area, true)
                        .map(c -> c);
        } catch (LinkageError e) {
            // a JEI without this call: say it once, and leave JEI's own input alone
            failed = true;
            Twinstar.LOGGER.warn("[雙星] 這個版本的 JEI 沒有預期的介面，自製畫面上的書籤鍵無法使用：{}", e.toString());
        }
        return Optional.empty();
    }
}
