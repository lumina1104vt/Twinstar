package com.lumina.twinstar.client;

import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.storage.TwinstarContent;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** The lib's screens (drawn with {@code ui.UiKit}: 1.20.1 has no LDLib2). */
@Mod.EventBusSubscriber(modid = Twinstar.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TwinstarClient {
    private TwinstarClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(TwinstarContent.ORDER_MENU.get(), OrderScreen::new);
            MenuScreens.register(TwinstarContent.PLAN_MENU.get(), PlanScreen::new);
        });
    }
}
