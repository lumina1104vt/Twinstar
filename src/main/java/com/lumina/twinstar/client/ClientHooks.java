package com.lumina.twinstar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Client-only lookups for the common packet classes, which must not name client classes themselves. */
public final class ClientHooks {
    private ClientHooks() {
    }

    public static @Nullable Player player() {
        return Minecraft.getInstance().player;
    }
}
