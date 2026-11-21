package com.lumina.twinstar.client;

import com.lumina.twinstar.Twinstar;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * The lib's screens. On 1.21.1 they are built with LDLib2's UI; 1.20.1 has only LDLib 1, whose UI is another API, so the order and plan
 * screens (and the theory viewer) are not ported yet: until they are, the order and plan menus open AE2's own screens.
 */
@Mod.EventBusSubscriber(modid = Twinstar.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class TwinstarClient {
    private TwinstarClient() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // TODO(1.20.1 port): MenuScreens.register(ORDER_MENU / PLAN_MENU, ...) once the screens are rebuilt
    }
}
