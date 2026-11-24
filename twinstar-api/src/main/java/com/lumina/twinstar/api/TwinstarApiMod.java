package com.lumina.twinstar.api;

import net.minecraftforge.fml.common.Mod;

/**
 * Forge's JavaFML wants an {@code @Mod} class for every mod id it loads; this lib mod has no start-up code of its
 * own (NeoForge 1.21.1 does not ask for one).
 */
@Mod("twinstar_api")
public final class TwinstarApiMod {
    public TwinstarApiMod() {
    }
}
