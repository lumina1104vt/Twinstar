package com.lumina.twinstar.util;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * What a block gives for a capability at a side (null side: no side), as 1.21's {@code level.getCapability(cap, pos, side)}
 * does it; 1.20.1 asks the block entity, which answers with a LazyOptional.
 */
public final class Caps {
    private Caps() {
    }

    public static @Nullable IItemHandler item(Level level, BlockPos pos, @Nullable Direction side) {
        return of(level, pos, side, ForgeCapabilities.ITEM_HANDLER);
    }

    public static @Nullable IFluidHandler fluid(Level level, BlockPos pos, @Nullable Direction side) {
        return of(level, pos, side, ForgeCapabilities.FLUID_HANDLER);
    }

    public static @Nullable IEnergyStorage energy(Level level, BlockPos pos, @Nullable Direction side) {
        return of(level, pos, side, ForgeCapabilities.ENERGY);
    }

    public static <T> @Nullable T of(Level level, BlockPos pos, @Nullable Direction side, Capability<T> cap) {
        BlockEntity be = level.getBlockEntity(pos);
        return be == null ? null : be.getCapability(cap, side).resolve().orElse(null);
    }
}
