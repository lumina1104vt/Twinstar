package com.lumina.twinstar.api;

import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * A machine access interface as the Minecraft side sees it: {@link IMachine} plus what needs Minecraft's own
 * classes. The engine knows only {@link IMachine}.
 */
public interface IMachineAccess extends IMachine {
    /** Null while unloaded. */
    @Nullable ServerLevel getMachineLevel();


    /**
     * The machines this interface stands for, each as an access of its own (same settings, its own place):
     * an interface bound to several machines from afar. The same objects until the binding changes.
     */
    default java.util.List<IMachineAccess> accessPoints() {
        return java.util.List.of(this);
    }


    /** The name the player gave the interface, or null. */
    default @Nullable net.minecraft.network.chat.Component interfaceName() {
        return null;
    }
}
