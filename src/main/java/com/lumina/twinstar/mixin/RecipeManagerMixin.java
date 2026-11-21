package com.lumina.twinstar.mixin;

import com.google.gson.JsonElement;
import com.lumina.twinstar.tools.RecipeChances;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 1.20.1 recipes cannot be written back as JSON (1.21's codecs can), and the dump looks in the JSON for the chance of an output:
 * so the JSON the datapacks hold is kept when the manager reads it.
 */
@Mixin(RecipeManager.class)
public abstract class RecipeManagerMixin {
    @Inject(method = "apply(Ljava/util/Map;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V", at = @At("HEAD"))
    private void twinstar$keepJson(Map<ResourceLocation, JsonElement> json, ResourceManager manager, ProfilerFiller profiler, CallbackInfo ci) {
        RecipeChances.JSON = json;
    }
}
