package com.lumina.twinstar.port;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.Item;

/**
 * One loaded world as the engine sees it: what it asks about blocks and time, and the two ways it works on machines in
 * it ({@link #storage()}, {@link #actions()}). The version's adapter fills it in. Equal worlds are the same world.
 * Server thread only.
 */
public interface World {
    /** The machines' own containers in this world. */
    MachineStorage storage();

    /** What a player would do in this world (right click, GUI, placing). */
    WorldActions actions();

    boolean isLoaded(BlockPos pos);

    long getGameTime();

    /** The item of the block at the position (air when there is none): what kind of machine stands there. */
    Item blockItemAt(BlockPos pos);

    /** The registry name of the block at the position, for the debug log. */
    String blockId(BlockPos pos);

    /** The position together with this world, to keep for later. */
    GlobalPos globalPos(BlockPos pos);
}
