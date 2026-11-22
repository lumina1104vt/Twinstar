package com.lumina.twinstar.client;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.emi.EmiBookmarks;
import com.lumina.twinstar.client.jei.JeiBookmarks;
import net.minecraftforge.fml.ModList;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Puts materials into the player's JEI bookmarks and EMI favourites (what the A key does for the item under the mouse).
 * Both mods have a way to add one from code; either may be missing, or a version may change it, so
 * a failure only turns that mod's side off (one line in the log) and the button is hidden.
 */
public final class Bookmarks {
    private static boolean jeiBroken, emiBroken;

    private Bookmarks() {
    }

    private static boolean jeiReady() {
        return !jeiBroken && ModList.get().isLoaded("jei") && JeiBookmarks.ready();
    }

    private static boolean emiReady() {
        return !emiBroken && ModList.get().isLoaded("emi") && EmiBookmarks.ready();
    }

    /** True when at least one of JEI / EMI is loaded and able to take a bookmark. */
    public static boolean available() {
        try {
            return jeiReady() || emiReady();
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    /**
     * Adds the materials (each once). Returns how many of them were new in at least one of the two
     * lists; 0 when there was nothing to add or nothing could take them.
     */
    public static int add(Collection<? extends AEKey> keys) {
        Set<AEKey> distinct = new LinkedHashSet<>(keys);
        Set<AEKey> added = new LinkedHashSet<>();
        if (jeiReady()) {
            try {
                added.addAll(JeiBookmarks.add(distinct));
            } catch (LinkageError | RuntimeException e) {
                jeiBroken = true;
                Twinstar.LOGGER.warn("[雙星] 無法加入 JEI 書籤（這個版本的 JEI 沒有預期的書籤介面）：{}", e.toString());
            }
        }
        if (emiReady()) {
            try {
                added.addAll(EmiBookmarks.add(distinct));
            } catch (LinkageError | RuntimeException e) {
                emiBroken = true;
                Twinstar.LOGGER.warn("[雙星] 無法加入 EMI 最愛（這個版本的 EMI 沒有預期的介面）：{}", e.toString());
            }
        }
        return added.size();
    }
}
