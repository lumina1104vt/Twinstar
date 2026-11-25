package com.lumina.twinstar.usage;

import com.lumina.bridge.Place;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where a machine of another mod stands, for the monitor, without knowing that mod: it is a block entity, or an object with
 * {@code getPos()} and {@code getLevel()} (GregTech's machines are such), or one that holds such an object
 * ({@code getMachine()}, a trait of a machine). Nothing here throws; an object that says nothing gives null.
 */
public final class HostPlaces {
    private HostPlaces() {
    }

    private record Getters(@Nullable Method pos, @Nullable Method level, @Nullable Method machine) {
    }

    private static final Getters NONE = new Getters(null, null, null);
    private static final ConcurrentHashMap<Class<?>, Getters> CACHE = new ConcurrentHashMap<>();

    /** True for what AE2 (and the mods that build on its blocks) put in a world: those are hooked where they send. */
    public static boolean isBlockEntityHost(@Nullable Object host) {
        return host instanceof BlockEntity || host instanceof appeng.parts.AEBasePart;
    }

    /** A machine that takes material from the network (by its class names: input, stocking, a pattern buffer). */
    public static boolean isInputSide(Object host) {
        for (Class<?> c = host.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.contains("Input") || n.contains("Stocking") || n.contains("PatternBuffer"))
                return true;
        }
        return false;
    }

    public static @Nullable Place placeOf(@Nullable Object host) {
        Place place = placeOf(host, 0);
        // once for each kind of host: what the monitor could and could not place (so a block that does not show can be told why)
        if (host != null && !isBlockEntityHost(host) && SEEN.add(host.getClass().getName() + (place == null ? "!" : "")))
            LOGGER.info("[monitor] {} of {}: {}", place == null ? "cannot place" : "places", host.getClass().getName(),
                    place == null ? "no getPos/getLevel/getMachine that answers" : place.pos());
        return place;
    }

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final java.util.Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private static @Nullable Place placeOf(@Nullable Object host, int depth) {
        if (host == null || depth > 2)
            return null;
        if (host instanceof BlockEntity be)
            return TransferSites.placeOf(be);
        if (host instanceof appeng.parts.AEBasePart part)
            return TransferSites.placeOf(part.getBlockEntity());
        Getters g = CACHE.computeIfAbsent(host.getClass(), HostPlaces::lookup);
        try {
            if (g.pos() != null && g.level() != null && g.pos().invoke(host) instanceof BlockPos pos
                    && g.level().invoke(host) instanceof Level level)
                return TransferSites.placeOf(level, pos);
            if (g.machine() != null)
                return placeOf(g.machine().invoke(host), depth + 1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // not a host we can place
        }
        return null;
    }

    private static Getters lookup(Class<?> type) {
        Method pos = find(type, "getPos", BlockPos.class);
        Method level = find(type, "getLevel", Level.class);
        Method machine = find(type, "getMachine", null);
        if (pos == null && level == null && machine == null)
            return NONE;
        return new Getters(pos, level, machine);
    }

    private static @Nullable Method find(Class<?> type, String name, @Nullable Class<?> returns) {
        try {
            Method m = type.getMethod(name);
            if (returns != null && !returns.isAssignableFrom(m.getReturnType()))
                return null;
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
