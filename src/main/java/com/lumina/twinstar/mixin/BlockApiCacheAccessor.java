package com.lumina.twinstar.mixin;

import appeng.util.BlockApiCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where an AE2 15 export bus looks from (its own block), for the monitor. */
@Mixin(value = BlockApiCache.class, remap = false)
public interface BlockApiCacheAccessor {
    @Accessor("level")
    ServerLevel twinstar$level();

    @Accessor("fromPos")
    BlockPos twinstar$fromPos();
}
