package com.lumina.twinstar.theory;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.service.TwinStarService;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client: what the theory viewer has heard from the server. The status is asked every second; a new
 * theory version brings the whole map again and makes the item details stale (the shown ones are
 * asked again), so the viewer follows the engine live.
 */
public final class TheoryCache {
    private static TwinStarService.TheoryStatus status = TwinStarService.TheoryStatus.NONE;
    private static @Nullable TheoryMap map;
    /** the map coming in, part by part */
    private static long incomingVersion = Long.MIN_VALUE;
    private static TheoryMap.Item @Nullable [] incomingItems;
    private static TheoryMap.Pattern @Nullable [] incomingPatterns;
    private static int partsIn, partsTotal;
    /** the version the map was last asked for (not asked again while it comes) */
    private static long mapAsked = Long.MIN_VALUE;

    private static final Map<AEKey, TheoryItem> items = new HashMap<>();
    /** items asked for and not answered yet */
    private static final Set<AEKey> asked = new HashSet<>();
    /** bumped on every answer: the viewer looks again when it changes */
    private static int changes;

    private TheoryCache() {
    }

    /** Forgets everything (a new viewer may be on another grid). */
    public static void clear() {
        status = TwinStarService.TheoryStatus.NONE;
        map = null;
        incomingVersion = Long.MIN_VALUE;
        incomingItems = null;
        incomingPatterns = null;
        partsIn = partsTotal = 0;
        mapAsked = Long.MIN_VALUE;
        items.clear();
        asked.clear();
        changes++;
    }

    public static void askStatus() {
        com.lumina.twinstar.TwinNet.toServer(new TheoryPackets.Request(TheoryPackets.Request.STATUS, null));
    }

    /** The item's details, asking the server if they are not here yet (null until they come). */
    public static @Nullable TheoryItem item(AEKey key) {
        TheoryItem item = items.get(key);
        if (item == null && asked.add(key))
            com.lumina.twinstar.TwinNet.toServer(new TheoryPackets.Request(TheoryPackets.Request.ITEM, key));
        return item;
    }

    public static TwinStarService.TheoryStatus status() {
        return status;
    }

    /** The whole layer; null until the first one has fully come. */
    public static @Nullable TheoryMap map() {
        return map;
    }

    /** Parts of the map come so far and in all (0/0 when none is coming). */
    public static int partsIn() {
        return partsIn;
    }

    public static int partsTotal() {
        return partsTotal;
    }

    public static int changes() {
        return changes;
    }

    static void status(TwinStarService.TheoryStatus s) {
        boolean newVersion = s.version() != status.version();
        status = s;
        if (newVersion) {
            // ask again for the details shown; the old ones stay on screen until the new ones come
            asked.clear();
            for (AEKey key : Set.copyOf(items.keySet()))
                if (asked.add(key))
                    com.lumina.twinstar.TwinNet.toServer(new TheoryPackets.Request(TheoryPackets.Request.ITEM, key));
        }
        if (s.version() >= 0 && s.version() != mapAsked && (map == null || map.version() != s.version())) {
            mapAsked = s.version();
            com.lumina.twinstar.TwinNet.toServer(new TheoryPackets.Request(TheoryPackets.Request.MAP, null));
        }
        changes++;
    }

    static void mapPart(TheoryMap.Part part) {
        if (part.version() != incomingVersion || incomingItems == null) {
            incomingVersion = part.version();
            incomingItems = new TheoryMap.Item[part.itemTotal()];
            incomingPatterns = new TheoryMap.Pattern[part.patternTotal()];
            partsIn = 0;
        }
        partsTotal = part.count();
        for (int i = 0; i < part.items().size(); i++)
            incomingItems[part.itemStart() + i] = part.items().get(i);
        for (int i = 0; i < part.patterns().size(); i++)
            incomingPatterns[part.patternStart() + i] = part.patterns().get(i);
        if (++partsIn >= part.count()) {
            map = new TheoryMap(part.version(), List.copyOf(Arrays.asList(incomingItems)), List.copyOf(Arrays.asList(incomingPatterns)));
            incomingItems = null;
            incomingPatterns = null;
            incomingVersion = Long.MIN_VALUE;
            partsIn = partsTotal = 0;
        }
        changes++;
    }

    static void item(TheoryItem item) {
        items.put(item.key(), item);
        asked.remove(item.key());
        changes++;
    }
}
