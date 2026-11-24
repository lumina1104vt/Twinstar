package com.lumina.twinstar;

import appeng.api.networking.GridServices;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.order.PlanViewPacket;
import com.lumina.twinstar.theory.TheoryPackets;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.storage.TwinstarContent;
import com.lumina.twinstar.tools.EngineCommands;
import com.lumina.twinstar.tools.JeiPackets;
import com.lumina.twinstar.tools.JeiRequests;
import com.lumina.twinstar.tools.NetworkDump;
import com.lumina.twinstar.tools.PatternGen;
import com.lumina.twinstar.tools.RecipeDump;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

@Mod(Twinstar.MODID)
public class Twinstar {
    public static final String MODID = "twinstar";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Twinstar(IEventBus modEventBus) {
        com.lumina.twinstar.crafting.RecipeJudges.register(new com.lumina.twinstar.algorithm.ae2.VanillaRecipeJudge());
        com.lumina.twinstar.monitor.Transfers.setSink(com.lumina.twinstar.usage.UsageStore::transfer);
        GridServices.register(ITwinStarService.class, TwinStarService.class);
        modEventBus.addListener(Twinstar::registerPayloads);
        TwinstarContent.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(RecipeDump::registerCommands);
        NeoForge.EVENT_BUS.addListener(PatternGen::registerCommands);
        NeoForge.EVENT_BUS.addListener(EngineCommands::registerCommands);
        NeoForge.EVENT_BUS.addListener(NetworkDump::registerCommands);
        NeoForge.EVENT_BUS.addListener(PatternGen::onServerTick);
        NeoForge.EVENT_BUS.addListener(PatternGen::onServerStopping);
        NeoForge.EVENT_BUS.addListener(RecipeDump::onServerStarted);
        NeoForge.EVENT_BUS.addListener(RecipeDump::onServerTick);
        NeoForge.EVENT_BUS.addListener(JeiRequests::onServerTick);
        NeoForge.EVENT_BUS.addListener(JeiRequests::onServerStopping);
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent e) -> com.lumina.twinstar.usage.UsageStore.clear());
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(PlanViewPacket.TYPE, PlanViewPacket.STREAM_CODEC, PlanViewPacket::handle);
        registrar.playToClient(com.lumina.twinstar.order.JobDonePacket.TYPE, com.lumina.twinstar.order.JobDonePacket.STREAM_CODEC,
                com.lumina.twinstar.order.JobDonePacket::handle);
        registrar.playToServer(TheoryPackets.Request.TYPE, TheoryPackets.Request.STREAM_CODEC, TheoryPackets.Request::handle);
        registrar.playToClient(com.lumina.twinstar.cpu.CpuReportPacket.TYPE, com.lumina.twinstar.cpu.CpuReportPacket.STREAM_CODEC,
                com.lumina.twinstar.cpu.CpuReportPacket::handle);
        registrar.playToClient(TheoryPackets.StatusReply.TYPE, TheoryPackets.StatusReply.STREAM_CODEC, TheoryPackets.StatusReply::handle);
        registrar.playToClient(TheoryPackets.MapReply.TYPE, TheoryPackets.MapReply.STREAM_CODEC, TheoryPackets.MapReply::handle);
        registrar.playToClient(TheoryPackets.ItemReply.TYPE, TheoryPackets.ItemReply.STREAM_CODEC, TheoryPackets.ItemReply::handle);
        registrar.playToServer(com.lumina.twinstar.usage.UsagePackets.Request.TYPE, com.lumina.twinstar.usage.UsagePackets.Request.STREAM_CODEC,
                com.lumina.twinstar.usage.UsagePackets.Request::handle);
        registrar.playToClient(com.lumina.twinstar.usage.UsagePackets.Reply.TYPE, com.lumina.twinstar.usage.UsagePackets.Reply.STREAM_CODEC,
                com.lumina.twinstar.usage.UsagePackets.Reply::handle);
        registrar.playToClient(JeiPackets.Request.TYPE, JeiPackets.Request.STREAM_CODEC, JeiPackets.Request::handle);
        registrar.playToServer(JeiPackets.Recipes.TYPE, JeiPackets.Recipes.STREAM_CODEC, JeiPackets.Recipes::handle);
    }
}
