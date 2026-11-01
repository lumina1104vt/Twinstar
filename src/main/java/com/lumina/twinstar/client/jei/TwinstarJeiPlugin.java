package com.lumina.twinstar.client.jei;

import com.lumina.twinstar.Twinstar;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;

/** Only here for JEI's runtime, which {@link JeiCollector} reads recipes from. Loaded by JEI only. */
@JeiPlugin
public final class TwinstarJeiPlugin implements IModPlugin {
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Twinstar.MODID, "jei");
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
