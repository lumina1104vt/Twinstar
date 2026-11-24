package com.lumina.twinstar.algorithm.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.crafting.RecipeJudge;
import com.lumina.twinstar.crafting.RecipeJudges;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/** AE2 patterns and stacks put to the {@link RecipeJudges}. */
public final class PatternJudging {
    private PatternJudging() {
    }

    /** Whether some judge handles patterns naming this machine item. */
    public static boolean isJudged(Item machine) {
        return RecipeJudges.isJudged(Keys.of(AEItemKey.of(machine)));
    }

    /** A pattern as AE2 decoded it, put to the judges. */
    public static boolean accepts(Item machine, IPatternDetails details, Object world) {
        List<RecipeJudge.In> ins = new ArrayList<>();
        for (IPatternDetails.IInput in : details.getInputs()) {
            GenericStack[] possible = in.getPossibleInputs();
            if (possible.length == 0)
                return false;
            List<Key> options = new ArrayList<>();
            for (GenericStack g : possible)
                options.add(Keys.of(g.what()));
            ins.add(new RecipeJudge.In(options, Math.multiplyExact(possible[0].amount(), in.getMultiplier())));
        }
        return accepts(machine, ins, details.getOutputs(), world);
    }

    /** A pattern as typed into the terminal (inputs with one option each), put to the judges. */
    public static boolean acceptsTyped(Item machine, List<GenericStack> inputs, List<GenericStack> outputs, Object world) {
        List<RecipeJudge.In> ins = new ArrayList<>();
        for (GenericStack in : inputs)
            ins.add(new RecipeJudge.In(List.of(Keys.of(in.what())), in.amount()));
        return accepts(machine, ins, outputs, world);
    }

    private static boolean accepts(Item machine, List<RecipeJudge.In> ins, GenericStack[] outs, Object world) {
        return accepts(machine, ins, List.of(outs), world);
    }

    private static boolean accepts(Item machine, List<RecipeJudge.In> ins, List<GenericStack> outs, Object world) {
        List<RecipeJudge.Out> outputs = new ArrayList<>();
        for (GenericStack g : outs)
            outputs.add(new RecipeJudge.Out(Keys.of(g.what()), g.amount()));
        return RecipeJudges.accepts(Keys.of(AEItemKey.of(machine)), ins, outputs, world);
    }
}
