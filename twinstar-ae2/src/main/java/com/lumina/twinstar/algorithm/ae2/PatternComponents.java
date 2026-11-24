package com.lumina.twinstar.algorithm.ae2;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The extra data on hub patterns (the data components); registered under the twinstar namespace as always. */
public final class PatternComponents {
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "twinstar");

    /** On an encoded pattern: outputs that only come with some probability (written by gen_patterns). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternChances>> PATTERN_CHANCES =
            COMPONENTS.registerComponentType("pattern_chances",
                    b -> b.persistent(PatternChances.CODEC).networkSynchronized(PatternChances.STREAM_CODEC));
    /** On a hub pattern: the machine faces each material goes in through ("specified" interfaces). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputFaces>> PATTERN_INPUT_FACES =
            COMPONENTS.registerComponentType("pattern_input_faces",
                    b -> b.persistent(PatternInputFaces.CODEC).networkSynchronized(PatternInputFaces.STREAM_CODEC));

    /** The materials and products of a hub pattern whose NBT does not matter. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternIgnoreNbt>> PATTERN_IGNORE_NBT =
            COMPONENTS.registerComponentType("pattern_ignore_nbt",
                    b -> b.persistent(PatternIgnoreNbt.CODEC).networkSynchronized(PatternIgnoreNbt.STREAM_CODEC));

    /** Where each material of a hub pattern goes when not into the machine: its sub-machines, or in front of it. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputTargets>> PATTERN_INPUT_TARGETS =
            COMPONENTS.registerComponentType("pattern_input_targets",
                    b -> b.persistent(PatternInputTargets.CODEC).networkSynchronized(PatternInputTargets.STREAM_CODEC));

    /** The GUI slot each material of a hub pattern goes into, for machines that take items only in a GUI. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputGuiSlots>> PATTERN_INPUT_GUI_SLOTS =
            COMPONENTS.registerComponentType("pattern_input_gui_slots",
                    b -> b.persistent(PatternInputGuiSlots.CODEC).networkSynchronized(PatternInputGuiSlots.STREAM_CODEC));

    /** The GUI slot each product of a hub pattern is taken out of, for machines that give them only in a GUI. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternOutputGuiSlots>> PATTERN_OUTPUT_GUI_SLOTS =
            COMPONENTS.registerComponentType("pattern_output_gui_slots",
                    b -> b.persistent(PatternOutputGuiSlots.CODEC).networkSynchronized(PatternOutputGuiSlots.STREAM_CODEC));

    /** The products of a hub pattern that count as made once the run's materials are all in the machine. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternDispatchDone>> PATTERN_DISPATCH_DONE =
            COMPONENTS.registerComponentType("pattern_dispatch_done",
                    b -> b.persistent(PatternDispatchDone.CODEC).networkSynchronized(PatternDispatchDone.STREAM_CODEC));

    /** What each material of a hub pattern is besides used up: catalyst, catalyst kept for the batch, tool to right-click with. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<PatternInputRoles>> PATTERN_INPUT_ROLES =
            COMPONENTS.registerComponentType("pattern_input_roles",
                    b -> b.persistent(PatternInputRoles.CODEC).networkSynchronized(PatternInputRoles.STREAM_CODEC));

    private PatternComponents() {
    }

    public static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }
}
