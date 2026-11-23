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

    /** How a machine waits before it gets more. */
    enum BlockMode {
        /** as much as it takes */
        NONE,
        /** another pattern's materials wait until the previous pattern's are gone (AE2's blocking mode) */
        BLOCKING,
        /** one run at a time: the next waits until the last one's materials are all used up */
        FULL
    }

    /** Wait until the previous pattern's materials are gone before putting in another pattern's. */
    boolean isBlocking();

    default BlockMode getBlockMode() {
        return isBlocking() ? BlockMode.BLOCKING : BlockMode.NONE;
    }

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

    /**
     * How far from the machine its sub-machines are looked for (the blocks of the kinds a pattern
     * names, for its materials set to go there): this many blocks across, one up and down.
     */
    default int getSubRange() {
        return 3;
    }

    /**
     * A run's materials are all in (and on its sub-machines, in front of it), or some are still there a
     * second after the last pulse: an interface may send a redstone pulse.
     */
    default void onRunFed() {
    }

    /**
     * What the interface keeps in the machine on its own (a furnace's fuel, a catalyst): not a run's
     * material, so blocking does not wait for it to be used up.
     */
    default java.util.Set<appeng.api.stacks.AEKey> getStockedKeys() {
        return java.util.Set.of();
    }

    /**
     * Takes out what the machine pushed into the interface (machines that put their products out
     * themselves): each kind and amount to {@code taken}, which counts them as the machine's products.
     */
    default void takeReceived(java.util.function.BiConsumer<appeng.api.stacks.AEKey, Long> taken) {
    }

    /**
     * The machines this interface stands for, each as an access of its own (same settings, its own place):
     * an interface bound to several machines from afar. The same objects until the binding changes.
     */
    default java.util.List<IMachineAccess> accessPoints() {
        return java.util.List.of(this);
    }

    /** Powered and on the grid. */
    boolean isAccessActive();

    /**
     * Where this interface itself is (the block it stands in: a cable part gives the cable's block), for the list of what
     * machines use up; null when it does not say.
     */
    default @Nullable net.minecraft.core.GlobalPos interfacePlace() {
        return null;
    }

    /** The side of the cable block a part stands on (a direction's index), -1 for a block of its own. */
    default int interfaceSide() {
        return -1;
    }

    /**
     * Where the machines this interface works on are (the list of what machines use up shows each of them, also one that used
     * nothing); empty when it does not say.
     */
    default java.util.List<net.minecraft.core.GlobalPos> machinePlaces() {
        return java.util.List.of();
    }

    /** The name the player gave the interface, or null. */
    default @Nullable net.minecraft.network.chat.Component interfaceName() {
        return null;
    }
}
