package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.port.MachineStorage;
import com.lumina.twinstar.port.World;
import com.lumina.twinstar.port.WorldActions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** The NeoForge 1.21.1 side of {@link World}: one {@link ServerLevel}. */
public final class Ae2World implements World {
    private final ServerLevel level;
    private final MachineStorage storage;
    private final WorldActions actions;

    Ae2World(ServerLevel level) {
        this.level = level;
        this.storage = Ae2Adapters.storage(level);
        this.actions = Ae2Adapters.actions(level);
    }

    public MachineStorage storage() {
        return storage;
    }

    public WorldActions actions() {
        return actions;
    }

    public boolean isLoaded(BlockPos pos) {
        return level.isLoaded(pos);
    }

    public long getGameTime() {
        return level.getGameTime();
    }

    public String blockItemId(BlockPos pos) {
        return BuiltInRegistries.ITEM.getKey(level.getBlockState(pos).getBlock().asItem()).toString();
    }

    public String blockId(BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
    }

    public GlobalPos globalPos(BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Ae2World w && w.level == level;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(System.identityHashCode(level));
    }
}
