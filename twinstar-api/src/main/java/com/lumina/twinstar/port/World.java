package com.lumina.twinstar.port;

import com.lumina.bridge.Pos;
import net.minecraft.core.GlobalPos;

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

    boolean isLoaded(Pos pos);

    long getGameTime();

    /** The registry name of the item of the block at the position ("minecraft:air" when there is none): what kind of machine stands there. */
    String blockItemId(Pos pos);

    /** The registry name of the block at the position, for the debug log. */
    String blockId(Pos pos);

    /** The position together with this world, to keep for later. */
    GlobalPos globalPos(Pos pos);
}
