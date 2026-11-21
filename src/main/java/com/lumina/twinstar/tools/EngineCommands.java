package com.lumina.twinstar.tools;

import com.lumina.twinstar.service.EngineLog;
import com.lumina.twinstar.service.TwinStarService;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * What the engine is doing, outside the dev environment (op level 2):
 * <ul>
 *   <li>{@code /twinstar status}: per network with a hub, the theory layer's size, whether the last
 *   update was a full rebuild and how long it took, whether the engine is still busy (updates not
 *   finished), the last plan;</li>
 *   <li>{@code /twinstar debug <true|false>}: the detailed engine log in logs/latest.log.</li>
 * </ul>
 */
public final class EngineCommands {
    private EngineCommands() {
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("twinstar").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("debug")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                                .executes(ctx -> debug(ctx.getSource(), BoolArgumentType.getBool(ctx, "on"))))));
    }

    private static int status(CommandSourceStack source) {
        List<TwinStarService> nets = TwinStarService.withHub();
        if (nets.isEmpty()) {
            source.sendSuccess(() -> Component.literal("[雙星] 沒有任何網路有運作中的中樞"), false);
            return 0;
        }
        for (TwinStarService s : nets)
            for (String line : s.statusLines())
                source.sendSuccess(() -> Component.literal(line), false);
        return nets.size();
    }

    private static int debug(CommandSourceStack source, boolean on) {
        EngineLog.setEnabled(on);
        source.sendSuccess(() -> Component.literal("[雙星] 詳細引擎日誌已" + (on ? "開啟（寫在 logs/latest.log）" : "關閉")), true);
        return 1;
    }
}
