package com.lumina.twinstar.api;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * A machine access interface: sits on a machine and lets the hub use it for the hub patterns of
 * that machine (the block it sits on, or the machine the player set on it). Picked up by the engine as a grid node owner, like {@code ITwinStarHost}. The engine
 * does the work (puts materials in, takes products out, through the machine's capabilities); the
 * interface only says where the machine is and how the player set it up.
 */
public interface IMachineAccess {
    /** How materials go into the machine. */
    enum InsertMode {
        /** through the face the interface sits on */
        DEFAULT,
        /** through the faces the pattern gives each material (the rest as DEFAULT) */
        SPECIFIED,
        /** no face: the machine decides where it goes */
        ANY,
        /** nothing goes in here: it only takes products out (a multiblock's output hatch) */
        OUTPUT
    }

    /**
     * A machine this interface also stands for, besides the block it sits on (its ghost slot: a
     * multiblock's port, another tier of the same machine); null when not set.
     */
    @Nullable Item getMachine();

    /** Wait until the previous pattern's materials are gone before putting in another pattern's. */
    boolean isBlocking();

    InsertMode getInsertMode();

    /** Null while unloaded. */
    @Nullable ServerLevel getMachineLevel();

    BlockPos getMachinePos();

    /** The machine's face the interface sits on. */
    Direction getMachineFace();

    /**
     * Interfaces of one machine that is spread over several blocks (a multiblock's input and output
     * hatches): those standing for the same machine with the same number above 0 count as one, so what
     * was fed through one is collected through another. 0: on its own.
     */
    default int getGroup() {
        return 0;
    }

    /** Powered and on the grid. */
    boolean isAccessActive();
}
