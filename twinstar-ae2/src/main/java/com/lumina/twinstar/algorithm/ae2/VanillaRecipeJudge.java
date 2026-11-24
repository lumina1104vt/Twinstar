package com.lumina.twinstar.algorithm.ae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.lumina.bridge.key.Key;
import com.lumina.twinstar.crafting.RecipeJudge;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** The judge for the crafting table, stonecutter, smithing table and the like ({@link WorkstationRecipes}). */
public final class VanillaRecipeJudge implements RecipeJudge {
    @Override
    public boolean handles(Key machine) {
        return itemOf(machine) != null && WorkstationRecipes.isWorkstation(itemOf(machine));
    }

    @Override
    public boolean accepts(Key machine, List<In> inputs, List<Out> outputs, Object world) {
        Item item = itemOf(machine);
        if (item == null || !(world instanceof Level level))
            return false;
        List<WorkstationRecipes.In> ins = new ArrayList<>();
        for (In in : inputs) {
            List<AEKey> options = new ArrayList<>();
            for (Key k : in.options()) {
                AEKey ae = k.handle(AEKey.class);
                if (ae == null)
                    return false;
                options.add(ae);
            }
            ins.add(new WorkstationRecipes.In(options, in.amount()));
        }
        List<GenericStack> outs = new ArrayList<>();
        for (Out out : outputs) {
            AEKey ae = out.what().handle(AEKey.class);
            if (ae == null)
                return false;
            outs.add(new GenericStack(ae, out.amount()));
        }
        return WorkstationRecipes.matches(item, ins, outs, level.getRecipeManager(), level.registryAccess());
    }

    private static Item itemOf(Key machine) {
        AEItemKey key = machine.handle(AEItemKey.class);
        return key == null ? null : key.getItem();
    }
}
