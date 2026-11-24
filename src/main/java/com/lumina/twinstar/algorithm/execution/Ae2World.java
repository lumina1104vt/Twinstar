package com.lumina.twinstar.algorithm.execution;

import com.lumina.twinstar.port.MachineStorage;
import com.lumina.twinstar.port.World;
import com.lumina.twinstar.port.WorldActions;
import com.lumina.bridge.Pos;
import com.lumina.bridge.Positions;
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

    public boolean isLoaded(Pos pos) {
        return level.isLoaded(Positions.toMc(pos));
    }

    public long getGameTime() {
        return level.getGameTime();
    }

    public String blockItemId(Pos pos) {
        return BuiltInRegistries.ITEM.getKey(level.getBlockState(Positions.toMc(pos)).getBlock().asItem()).toString();
    }

    public String blockId(Pos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(Positions.toMc(pos)).getBlock()).toString();
    }

    public GlobalPos globalPos(Pos pos) {
        return GlobalPos.of(level.dimension(), Positions.toMc(pos));
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
