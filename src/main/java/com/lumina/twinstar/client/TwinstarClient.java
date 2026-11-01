package com.lumina.twinstar.client;

import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.storage.TwinstarContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** The lib's screens. */
@EventBusSubscriber(modid = Twinstar.MODID, value = Dist.CLIENT)
public final class TwinstarClient {
    private TwinstarClient() {
    }

    @SubscribeEvent
    static void registerScreens(RegisterMenuScreensEvent event) {
        // built with LDLib2, not AE2's screen styles
        event.register(TwinstarContent.ORDER_MENU.get(), OrderScreen::new);
        event.register(TwinstarContent.PLAN_MENU.get(), PlanScreen::new);
    }
}
