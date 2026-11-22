package com.lumina.twinstar.client.jei;

import com.lumina.twinstar.Twinstar;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/**
 * JEI's runtime, which {@link JeiCollector} reads recipes from and {@link JeiBookmarks} adds bookmarks to,
 * and what our screens show under the mouse ({@link JeiKeyHover}). Loaded by JEI only.
 */
@JeiPlugin
public final class TwinstarJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "jei");
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGlobalGuiHandler(new JeiKeyHover());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        JeiCollector.runtime = runtime;
    }

    @Override
    public void onRuntimeUnavailable() {
        JeiCollector.runtime = null;
    }
}
