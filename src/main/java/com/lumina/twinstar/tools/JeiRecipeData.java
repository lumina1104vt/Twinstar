package com.lumina.twinstar.tools;

import appeng.api.stacks.GenericStack;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One recipe as the player's JEI shows it, sent to the server for {@code gen_patterns} and
 * {@code dump_recipes}: per input and output slot the items or fluids JEI cycles through, with the
 * amounts JEI shows (the vanilla recipe interface has no amounts for most mod machines). {@code id}
 * is the recipe's id when JEI knows it (null for recipes JEI makes up itself).
 */
public record JeiRecipeData(@Nullable ResourceLocation id, ResourceLocation category,
                            List<List<GenericStack>> inputs, List<List<GenericStack>> outputs) {
    /** options kept per slot (big tags such as "any planks" are cut) */
    public static final int MAX_OPTIONS = 32;

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(id != null);
        if (id != null)
            buf.writeResourceLocation(id);
        buf.writeResourceLocation(category);
        writeSlots(buf, inputs);
        writeSlots(buf, outputs);
    }

    public static JeiRecipeData read(FriendlyByteBuf buf) {
        return new JeiRecipeData(buf.readBoolean() ? buf.readResourceLocation() : null, buf.readResourceLocation(),
                readSlots(buf), readSlots(buf));
    }

    private static void writeSlots(FriendlyByteBuf buf, List<List<GenericStack>> slots) {
        buf.writeVarInt(slots.size());
        for (List<GenericStack> options : slots) {
            buf.writeVarInt(options.size());
            for (GenericStack s : options)
                GenericStack.writeBuffer(s, buf);
        }
    }

    private static List<List<GenericStack>> readSlots(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<List<GenericStack>> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = buf.readVarInt();
            List<GenericStack> options = new ArrayList<>(m);
            for (int j = 0; j < m; j++)
                options.add(GenericStack.readBuffer(buf));
            slots.add(options);
        }
        return slots;
    }
}
