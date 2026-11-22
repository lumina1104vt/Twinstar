package com.lumina.twinstar.service;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.lumina.twinstar.Twinstar;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What {@code ProviderStateMixin} does, on AE2's private registry (found by reflection once; if AE2 is not built the
 * way we expect, it logs once and AE2 keeps listing the first output only). AE2's own listing of a pattern has three
 * parts: the craftable list (amounts per item), the item → patterns map, and pattern → providers. Only the first two
 * depend on the output, so only those are repeated for the other outputs.
 */
public final class SecondaryOutputs {
    private static boolean ready;
    private static boolean broken;
    private static Field craftableList;     // NetworkCraftingProviders.craftableItemsList
    private static Field craftableItems;    // NetworkCraftingProviders.craftableItems
    private static Field infos;             // PatternsForKey.patterns
    private static Field needsSorting;      // PatternsForKey.needsSorting
    private static Constructor<?> newKeyEntry;      // PatternsForKey()
    private static Constructor<?> newInfo;          // PatternInfo(IPatternDetails, ProviderState)

    private SecondaryOutputs() {
    }

    private static boolean init(Object state) {
        if (ready) {
            return true;
        }
        if (broken) {
            return false;
        }
        try {
            Class<?> providers = NetworkCraftingProviders.class;
            craftableList = providers.getDeclaredField("craftableItemsList");
            craftableItems = providers.getDeclaredField("craftableItems");
            Class<?> forKey = Class.forName(providers.getName() + "$PatternsForKey", false, providers.getClassLoader());
            Class<?> info = Class.forName(providers.getName() + "$PatternInfo", false, providers.getClassLoader());
            infos = forKey.getDeclaredField("patterns");
            needsSorting = forKey.getDeclaredField("needsSorting");
            newKeyEntry = forKey.getDeclaredConstructor();
            newInfo = info.getDeclaredConstructor(IPatternDetails.class, state.getClass());
            for (var m : new java.lang.reflect.AccessibleObject[] {craftableList, craftableItems, infos, needsSorting, newKeyEntry, newInfo}) {
                m.setAccessible(true);
            }
            ready = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            Twinstar.LOGGER.warn("Patterns are listed under their first output only: AE2's pattern registry is not as expected", e);
            return false;
        }
    }

    /** The outputs after the first, each once, and not the first's own item. */
    private static Set<AEKey> others(IPatternDetails pattern, Map<AEKey, Long> amounts) {
        Set<AEKey> keys = new HashSet<>();
        GenericStack primary = pattern.getPrimaryOutput();
        AEKey first = primary == null ? null : primary.what();
        for (GenericStack out : pattern.getOutputs()) {
            if (out != null && !out.what().equals(first) && keys.add(out.what())) {
                amounts.put(out.what(), out.amount());
            }
        }
        return keys;
    }

    public static void mount(NetworkCraftingProviders owner, Object state, List<IPatternDetails> patterns) {
        if (!init(state)) {
            return;
        }
        try {
            KeyCounter list = (KeyCounter) craftableList.get(owner);
            @SuppressWarnings("unchecked")
            Map<AEKey, Object> items = (Map<AEKey, Object>) craftableItems.get(owner);
            for (IPatternDetails pattern : patterns) {
                Map<AEKey, Long> amounts = new java.util.HashMap<>();
                for (AEKey key : others(pattern, amounts)) {
                    list.add(key, amounts.get(key));
                    Object forKey = items.get(key);
                    if (forKey == null) {
                        forKey = newKeyEntry.newInstance();
                        items.put(key, forKey);
                    }
                    @SuppressWarnings("unchecked")
                    Set<Object> set = (Set<Object>) infos.get(forKey);
                    set.add(newInfo.newInstance(pattern, state));
                    needsSorting.setBoolean(forKey, true);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            Twinstar.LOGGER.warn("Listing the patterns' other outputs failed", e);
        }
    }

    public static void unmount(NetworkCraftingProviders owner, Object state, List<IPatternDetails> patterns) {
        if (!init(state)) {
            return;
        }
        try {
            KeyCounter list = (KeyCounter) craftableList.get(owner);
            @SuppressWarnings("unchecked")
            Map<AEKey, Object> items = (Map<AEKey, Object>) craftableItems.get(owner);
            for (IPatternDetails pattern : patterns) {
                Map<AEKey, Long> amounts = new java.util.HashMap<>();
                for (AEKey key : others(pattern, amounts)) {
                    list.remove(key, amounts.get(key));
                    Object forKey = items.get(key);
                    if (forKey == null) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Set<Object> set = (Set<Object>) infos.get(forKey);
                    set.remove(newInfo.newInstance(pattern, state));
                    needsSorting.setBoolean(forKey, true);
                    if (set.isEmpty()) {
                        items.remove(key);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            Twinstar.LOGGER.warn("Unlisting the patterns' other outputs failed", e);
        }
    }
}
