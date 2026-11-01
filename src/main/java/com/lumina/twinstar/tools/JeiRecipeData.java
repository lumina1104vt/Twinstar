package com.lumina.twinstar.tools;

import appeng.api.stacks.GenericStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
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

    public static final StreamCodec<RegistryFriendlyByteBuf, JeiRecipeData> STREAM_CODEC = StreamCodec.of(
            (buf, r) -> {
                buf.writeBoolean(r.id != null);
                if (r.id != null)
                    buf.writeResourceLocation(r.id);
                buf.writeResourceLocation(r.category);
                writeSlots(buf, r.inputs);
                writeSlots(buf, r.outputs);
            },
            buf -> new JeiRecipeData(buf.readBoolean() ? buf.readResourceLocation() : null, buf.readResourceLocation(),
                    readSlots(buf), readSlots(buf)));

    private static void writeSlots(RegistryFriendlyByteBuf buf, List<List<GenericStack>> slots) {
        buf.writeVarInt(slots.size());
        for (List<GenericStack> options : slots) {
            buf.writeVarInt(options.size());
            for (GenericStack s : options)
                GenericStack.STREAM_CODEC.encode(buf, s);
        }
    }

    private static List<List<GenericStack>> readSlots(RegistryFriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<List<GenericStack>> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int m = buf.readVarInt();
            List<GenericStack> options = new ArrayList<>(m);
            for (int j = 0; j < m; j++)
                options.add(GenericStack.STREAM_CODEC.decode(buf));
            slots.add(options);
        }
        return slots;
    }
}
