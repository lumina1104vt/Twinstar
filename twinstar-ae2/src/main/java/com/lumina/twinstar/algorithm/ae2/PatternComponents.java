package com.lumina.twinstar.algorithm.ae2;

/** The extra data on hub patterns (NBT on the pattern stack on 1.20.1, which has no data components). */
public final class PatternComponents {
    private PatternComponents() {
    }

    public static final PatternData<PatternChances> PATTERN_CHANCES = new PatternData<>("pattern_chances", PatternChances.CODEC);
    public static final PatternData<PatternInputFaces> PATTERN_INPUT_FACES = new PatternData<>("pattern_input_faces", PatternInputFaces.CODEC);
    public static final PatternData<PatternIgnoreNbt> PATTERN_IGNORE_NBT = new PatternData<>("pattern_ignore_nbt", PatternIgnoreNbt.CODEC);
    public static final PatternData<PatternInputTargets> PATTERN_INPUT_TARGETS = new PatternData<>("pattern_input_targets", PatternInputTargets.CODEC);
    public static final PatternData<PatternInputGuiSlots> PATTERN_INPUT_GUI_SLOTS = new PatternData<>("pattern_input_gui_slots", PatternInputGuiSlots.CODEC);
    public static final PatternData<PatternOutputGuiSlots> PATTERN_OUTPUT_GUI_SLOTS = new PatternData<>("pattern_output_gui_slots", PatternOutputGuiSlots.CODEC);
    public static final PatternData<PatternDispatchDone> PATTERN_DISPATCH_DONE = new PatternData<>("pattern_dispatch_done", PatternDispatchDone.CODEC);
    public static final PatternData<PatternInputRoles> PATTERN_INPUT_ROLES = new PatternData<>("pattern_input_roles", PatternInputRoles.CODEC);

    /** Nothing to register on this version; kept so callers read the same on every version. */
    public static void register(Object bus) {
    }
}
