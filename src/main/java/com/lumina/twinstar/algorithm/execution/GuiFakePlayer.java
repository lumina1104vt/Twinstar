package com.lumina.twinstar.algorithm.execution;

import java.util.Map;
import java.util.OptionalInt;
import java.util.WeakHashMap;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

/**
 * The fake player that works GUI machines. NeoForge's own fake player is given no GUIs ({@code openMenu} does nothing), so a block
 * that opens its GUI on a click (Immersive Engineering's multiblocks) opened nothing; this one makes the menu the block asks it to
 * open and keeps it as its open container, which is all the machine's slots are read and filled through.
 */
final class GuiFakePlayer extends FakePlayer {
    private static final Map<ServerLevel, GuiFakePlayer> PLAYERS = new WeakHashMap<>();

    private GuiFakePlayer(ServerLevel level, GameProfile profile) {
        super(level, profile);
    }

    static synchronized GuiFakePlayer get(ServerLevel level, GameProfile profile) {
        return PLAYERS.computeIfAbsent(level, l -> new GuiFakePlayer(l, profile));
    }

    @Override
    public OptionalInt openMenu(@Nullable MenuProvider provider) {
        if (provider == null)
            return OptionalInt.empty();
        if (containerMenu != inventoryMenu)
            doCloseContainer();
        AbstractContainerMenu menu = provider.createMenu(1, getInventory(), this);
        if (menu == null)
            return OptionalInt.empty();
        containerMenu = menu;
        return OptionalInt.of(1);
    }
}
