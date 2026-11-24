package com.lumina.twinstar;

import appeng.api.networking.GridServices;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.storage.TwinstarContent;
import com.lumina.twinstar.tools.EngineCommands;
import com.lumina.twinstar.tools.JeiRequests;
import com.lumina.twinstar.tools.NetworkDump;
import com.lumina.twinstar.tools.PatternGen;
import com.lumina.twinstar.tools.RecipeDump;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Twinstar.MODID)
public class Twinstar {
    public static final String MODID = "twinstar";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Twinstar() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        com.lumina.twinstar.crafting.RecipeJudges.register(new com.lumina.twinstar.algorithm.ae2.VanillaRecipeJudge());
        com.lumina.twinstar.monitor.Transfers.setSink(com.lumina.twinstar.usage.UsageStore::transfer);
        GridServices.register(ITwinStarService.class, TwinStarService.class);
        modEventBus.addListener(Twinstar::commonSetup);
        TwinstarContent.register(modEventBus);
        MinecraftForge.EVENT_BUS.addListener(RecipeDump::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(PatternGen::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(EngineCommands::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(NetworkDump::registerCommands);
        MinecraftForge.EVENT_BUS.addListener(PatternGen::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(PatternGen::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(RecipeDump::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(RecipeDump::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(JeiRequests::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(JeiRequests::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent e) -> com.lumina.twinstar.usage.UsageStore.clear());
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(TwinNet::register);
    }
}
