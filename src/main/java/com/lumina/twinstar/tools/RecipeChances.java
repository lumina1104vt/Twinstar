package com.lumina.twinstar.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.crafting.Recipe;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which outputs of a recipe only come with some probability. JEI has no such notion (mods show it
 * in tooltips only), but most mods write it next to the output in the recipe itself: the recipe is
 * written out with its own serializer (the same JSON a datapack holds) and searched for fields
 * named like {@code chance} or {@code probability}.
 * <ul>
 *   <li>{@code {"id": "minecraft:flint", "chance": 0.1}}: the object is the output, the chance is its own;</li>
 *   <li>{@code {"secondaryOutput": {...}, "secondaryChance": 0.1}}: a prefixed field belongs to the
 *   sibling fields with the same prefix.</li>
 * </ul>
 * Only values strictly between 0 and 1 count (a field of 1 or more is no probability). An unusual
 * field name is not found: that output is taken as always coming.
 */
public final class RecipeChances {
    private static final Set<String> NAMES = Set.of("chance", "probability");
    /** fields that name the item or fluid of an output */
    private static final Set<String> ID_FIELDS = Set.of("id", "item", "fluid");

    private RecipeChances() {
    }

    /** item or fluid id → probability, for the outputs found with one */
    public static Map<String, Double> of(Recipe<?> recipe, RegistryAccess registries) {
        Map<String, Double> out = new HashMap<>();
        JsonElement json;
        try {
            json = Recipe.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), recipe).result().orElse(null);
        } catch (RuntimeException e) {
            json = null;    // a serializer that cannot write what it reads
        }
        if (json != null)
            walk(json, out);
        return out;
    }

    private static void walk(JsonElement e, Map<String, Double> out) {
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray())
                walk(c, out);
            return;
        }
        if (!e.isJsonObject())
            return;
        JsonObject o = e.getAsJsonObject();
        for (Map.Entry<String, JsonElement> f : o.entrySet()) {
            Double chance = probability(f.getValue());
            if (chance == null)
                continue;
            String name = f.getKey().toLowerCase(Locale.ROOT);
            if (NAMES.contains(name)) {
                ids(o, chance, out);
                continue;
            }
            for (String n : NAMES)
                if (name.endsWith(n) && name.length() > n.length()) {
                    String prefix = name.substring(0, name.length() - n.length());
                    for (Map.Entry<String, JsonElement> sibling : o.entrySet()) {
                        String s = sibling.getKey().toLowerCase(Locale.ROOT);
                        if (!s.equals(name) && s.startsWith(prefix))
                            ids(sibling.getValue(), chance, out);
                    }
                }
        }
        for (Map.Entry<String, JsonElement> f : o.entrySet())
            walk(f.getValue(), out);
    }

    private static Double probability(JsonElement v) {
        if (!(v instanceof JsonPrimitive p) || !p.isNumber())
            return null;
        double d = p.getAsDouble();
        return d > 0 && d < 1 ? d : null;
    }

    /** Every item / fluid id named in {@code e} gets {@code chance}. */
    private static void ids(JsonElement e, double chance, Map<String, Double> out) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            out.put(e.getAsString(), chance);
            return;
        }
        if (e.isJsonArray()) {
            for (JsonElement c : e.getAsJsonArray())
                ids(c, chance, out);
            return;
        }
        if (!e.isJsonObject())
            return;
        for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet())
            if (ID_FIELDS.contains(f.getKey()) && f.getValue().isJsonPrimitive())
                out.put(f.getValue().getAsString(), chance);
            else if (f.getValue().isJsonObject() || f.getValue().isJsonArray())
                ids(f.getValue(), chance, out);
    }
}
