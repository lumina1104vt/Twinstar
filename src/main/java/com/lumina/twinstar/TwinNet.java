package com.lumina.twinstar;

import com.lumina.twinstar.order.JobDonePacket;
import com.lumina.twinstar.order.PlanViewPacket;
import com.lumina.twinstar.theory.TheoryPackets;
import com.lumina.twinstar.tools.JeiPackets;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

/** The lib's packets (1.20.1: one SimpleChannel; each packet writes and reads itself). */
public final class TwinNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Twinstar.MODID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private TwinNet() {
    }

    public static void toServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    public static void toPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void register() {
        int id = 0;
        Optional<NetworkDirection> toClient = Optional.of(NetworkDirection.PLAY_TO_CLIENT);
        Optional<NetworkDirection> toServer = Optional.of(NetworkDirection.PLAY_TO_SERVER);
        CHANNEL.registerMessage(id++, PlanViewPacket.class, PlanViewPacket::write, PlanViewPacket::read, PlanViewPacket::handle, toClient);
        CHANNEL.registerMessage(id++, JobDonePacket.class, JobDonePacket::write, JobDonePacket::read, JobDonePacket::handle, toClient);
        CHANNEL.registerMessage(id++, TheoryPackets.Request.class, TheoryPackets.Request::write, TheoryPackets.Request::read,
                TheoryPackets.Request::handle, toServer);
        CHANNEL.registerMessage(id++, TheoryPackets.StatusReply.class, TheoryPackets.StatusReply::write, TheoryPackets.StatusReply::read,
                TheoryPackets.StatusReply::handle, toClient);
        CHANNEL.registerMessage(id++, TheoryPackets.MapReply.class, TheoryPackets.MapReply::write, TheoryPackets.MapReply::read,
                TheoryPackets.MapReply::handle, toClient);
        CHANNEL.registerMessage(id++, TheoryPackets.ItemReply.class, TheoryPackets.ItemReply::write, TheoryPackets.ItemReply::read,
                TheoryPackets.ItemReply::handle, toClient);
        CHANNEL.registerMessage(id++, com.lumina.twinstar.cpu.CpuReportPacket.class, com.lumina.twinstar.cpu.CpuReportPacket::write,
                com.lumina.twinstar.cpu.CpuReportPacket::read, com.lumina.twinstar.cpu.CpuReportPacket::handle, toClient);
        CHANNEL.registerMessage(id++, JeiPackets.Request.class, JeiPackets.Request::write, JeiPackets.Request::read,
                JeiPackets.Request::handle, toClient);
        CHANNEL.registerMessage(id++, JeiPackets.Recipes.class, JeiPackets.Recipes::write, JeiPackets.Recipes::read,
                JeiPackets.Recipes::handle, toServer);
        CHANNEL.registerMessage(id++, com.lumina.twinstar.usage.UsagePackets.Request.class, com.lumina.twinstar.usage.UsagePackets.Request::write,
                com.lumina.twinstar.usage.UsagePackets.Request::read, com.lumina.twinstar.usage.UsagePackets.Request::handle, toServer);
        CHANNEL.registerMessage(id++, com.lumina.twinstar.usage.UsagePackets.Reply.class, com.lumina.twinstar.usage.UsagePackets.Reply::write,
                com.lumina.twinstar.usage.UsagePackets.Reply::read, com.lumina.twinstar.usage.UsagePackets.Reply::handle, toClient);
    }
}
