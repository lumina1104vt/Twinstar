package com.lumina.twinstar.api;

import com.lumina.bridge.key.Key;
import com.lumina.twinstar.port.World;
import com.lumina.bridge.Pos;
import com.lumina.bridge.Face;
import org.jetbrains.annotations.Nullable;

/**
 * A machine access interface: sits on a machine and lets the hub use it for the hub patterns of
 * that machine (the block it sits on, or the machine the player set on it). Picked up by the engine as a grid node owner, like {@code ITwinStarHost}. The engine
 * does the work (puts materials in, takes products out, through the machine's capabilities); the
 * interface only says where the machine is and how the player set it up.
 */
public interface IMachine {
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
    @Nullable Key getMachine();

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

    /** The world the machine stands in; null while it is not loaded. */
    @Nullable World getMachineWorld();

    Pos getMachinePos();

    /** The machine's face the interface sits on. */
    Face getMachineFace();

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
    default java.util.Set<com.lumina.bridge.key.Key> getStockedKeys() {
        return java.util.Set.of();
    }

    /**
     * Takes out what the machine pushed into the interface (machines that put their products out
     * themselves): each kind and amount to {@code taken}, which counts them as the machine's products.
     */
    default void takeReceived(java.util.function.BiConsumer<com.lumina.bridge.key.Key, Long> taken) {
    }

    /** What a block bound to an interface is, next to the machine it belongs to. */
    enum PortRole {
        /** a machine of its own (the default) */
        MACHINE,
        /** an item input of the machine before it, by its slot number (an assembly line's input buses) */
        ITEM_IN,
        /** a fluid input of the machine before it, by its slot number */
        FLUID_IN,
        /** takes the products out of this block */
        OUTPUT,
        /** gets the FE the machine needs */
        ENERGY
    }

    /**
     * A block that belongs to this machine: where it is, what it is for and, for the inputs, its slot number (1 and up, the
     * number a pattern's material slot names; each kind of input is numbered apart).
     */
    record Port(Pos pos, PortRole role, int slot) {
    }

    /**
     * The blocks bound to the interface after this machine, until the next machine: a multiblock's hatches. A material whose pattern
     * slot is N goes into the hatch with slot N; with none, as before (the machine's own GUI slot N).
     */
    default java.util.List<Port> ports() {
        return java.util.List.of();
    }

    /** Powered and on the grid. */
    boolean isAccessActive();

    /**
     * Where this interface itself is (the block it stands in: a cable part gives the cable's block), for the list of what
     * machines use up; null when it does not say.
     */
    default @Nullable com.lumina.bridge.Place interfacePlace() {
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
    default java.util.List<com.lumina.bridge.Place> machinePlaces() {
        return java.util.List.of();
    }

}
