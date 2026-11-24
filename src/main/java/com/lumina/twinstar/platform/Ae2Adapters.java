package com.lumina.twinstar.platform;

import com.lumina.twinstar.algorithm.execution.*;
import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.lumina.twinstar.port.PatternSite;
import com.lumina.twinstar.port.PatternSites;
import com.lumina.twinstar.port.Power;
import appeng.api.stacks.AEKey;
import com.lumina.bridge.key.Key;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.port.MachineStorage;
import com.lumina.twinstar.port.World;
import com.lumina.twinstar.port.WorldActions;
import com.lumina.bridge.Face;
import com.lumina.bridge.Pos;
import com.lumina.bridge.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The NeoForge 1.21.1 + AE2 side of {@link MachineStorage} and {@link WorldActions}: {@link MachineIO} and
 * {@link WorldIO} for one world. The engine gets them only through the interfaces.
 */
public final class Ae2Adapters {
    private static final java.util.Map<ServerLevel, Ae2World> WORLDS = new java.util.WeakHashMap<>();

    private Ae2Adapters() {
    }

    /** The world of a level (null for none). */
    public static @Nullable World world(@Nullable ServerLevel level) {
        if (level == null)
            return null;
        synchronized (WORLDS) {
            return WORLDS.computeIfAbsent(level, Ae2World::new);
        }
    }

    static MachineStorage storage(ServerLevel level) {
        return new Storage(level);
    }

    static WorldActions actions(ServerLevel level) {
        return new Actions(level);
    }

    /** The crafting providers AE2 offers a pattern to, as sites. */
    public static PatternSites sites(Function<IPatternDetails, Iterable<ICraftingProvider>> providers) {
        return pattern -> {
            if (!(pattern instanceof IPatternDetails details))
                return List.of();
            List<PatternSite> out = new java.util.ArrayList<>();
            for (ICraftingProvider p : providers.apply(details))
                out.add(new Site(p));
            return out;
        };
    }

    public static Power power(IEnergySource source) {
        return (amount, simulate) -> source.extractAEPower(amount, simulate ? Actionable.SIMULATE : Actionable.MODULATE,
                PowerMultiplier.CONFIG);
    }

    /** Equal by provider, so the engine's per-site records find it again. */
    private record Site(ICraftingProvider provider) implements PatternSite {
        public int priority() {
            return provider.getPatternPriority();
        }

        public boolean isBusy() {
            return provider.isBusy();
        }

        public boolean isHub() {
            return provider instanceof com.lumina.twinstar.host.ITwinStarHost;
        }

        public boolean push(Object pattern, List<Map<Key, Long>> inputs) {
            KeyCounter[] counters = new KeyCounter[inputs.size()];
            for (int i = 0; i < counters.length; i++) {
                Map<Key, Long> slot = inputs.get(i);
                if (slot == null)
                    continue;
                KeyCounter counter = new KeyCounter();
                for (Map.Entry<Key, Long> e : slot.entrySet())
                    counter.add(ae(e.getKey()), e.getValue());
                counters[i] = counter;
            }
            return provider.pushPattern((IPatternDetails) pattern, counters);
        }
    }

    // ---- Key <-> AE2 key ----

    private static AEKey ae(Key key) {
        return Keys.toAe(key);
    }

    private static AEItemKey aeItem(Key key) {
        return key.handle(AEItemKey.class);
    }

    private static Set<AEKey> aeSet(Set<Key> keys) {
        Set<AEKey> out = new HashSet<>();
        for (Key k : keys)
            out.add(ae(k));
        return out;
    }

    private static BiConsumer<AEKey, Long> wrap(BiConsumer<Key, Long> sink) {
        return (k, n) -> sink.accept(Keys.of(k), n);
    }

    private static BlockPos mc(Pos pos) {
        return Positions.toMc(pos);
    }

    private static Direction mc(Face face) {
        return Positions.toMc(face);
    }

    private static List<@Nullable Direction> mcFaces(List<@Nullable Face> faces) {
        List<@Nullable Direction> out = new java.util.ArrayList<>(faces.size());
        for (Face f : faces)
            out.add(f == null ? null : Positions.toMc(f));
        return out;
    }

    private record Storage(ServerLevel level) implements MachineStorage {
        public boolean hasItemStorage(Pos pos) {
            return !MachineIO.itemHandlers(level, mc(pos)).isEmpty();
        }

        public long insert(Pos pos, List<@Nullable Face> faces, Key key, long amount) {
            return MachineIO.insert(level, mc(pos), mcFaces(faces), ae(key), amount);
        }

        public long retrieve(Pos pos, Key key, long amount, BiConsumer<Key, Long> taken) {
            return MachineIO.retrieve(level, mc(pos), aeItem(key), amount, wrap(taken));
        }

        public void extractOutputs(Pos pos, Set<Key> wantOther, Set<Key> keep, Set<Key> products, BiConsumer<Key, Long> taken) {
            MachineIO.extractOutputs(level, mc(pos), aeSet(wantOther), aeSet(keep), aeSet(products), wrap(taken));
        }

        public boolean holdsAny(Pos pos, Set<Key> keys) {
            return MachineIO.holdsAny(level, mc(pos), aeSet(keys));
        }

        public void forget(Pos pos) {
            MachineIO.forget(level, mc(pos));
        }

        public String describe(Pos pos) {
            return MachineIO.describe(level, mc(pos));
        }

        public String contentsAt(Pos pos) {
            return MachineIO.contentsAt(level, mc(pos));
        }

        public String describeInsert(Pos pos, List<@Nullable Face> faces, Key key, long amount) {
            return MachineIO.describeInsert(level, mc(pos), mcFaces(faces), ae(key), amount);
        }
    }

    private record Actions(ServerLevel level) implements WorldActions {
        public long rightClick(Pos pos, Face face, Key key, long amount, BiConsumer<Key, Long> stray) {
            return WorldIO.rightClick(level, mc(pos), mc(face), aeItem(key), amount, wrap(stray));
        }

        public @Nullable Key useTool(Pos pos, Face face, Key tool, BiConsumer<Key, Long> stray) {
            AEItemKey back = WorldIO.useTool(level, mc(pos), mc(face), aeItem(tool), wrap(stray));
            return back == null ? null : Keys.of(back);
        }

        public void emptyClick(Pos pos, Face face, BiConsumer<Key, Long> stray) {
            WorldIO.emptyClick(level, mc(pos), mc(face), wrap(stray));
        }

        public long insertViaGui(Pos pos, Face face, Key key, long amount, int onlySlot,
                                 BiConsumer<Key, Long> stray) {
            return WorldIO.insertViaGui(level, mc(pos), mc(face), aeItem(key), amount, onlySlot, wrap(stray));
        }

        public long extractViaGui(Pos pos, Face face, Map<Key, Integer> products,
                                  BiConsumer<Key, Long> taken, BiConsumer<Key, Long> stray) {
            Map<AEKey, Integer> ae = new HashMap<>();
            products.forEach((k, v) -> ae.put(ae(k), v));
            return WorldIO.extractViaGui(level, mc(pos), mc(face), ae, wrap(taken), wrap(stray));
        }

        public Pos front(Pos pos, Face interfaceFace, int distance) {
            return Positions.of(WorldIO.front(level, mc(pos), mc(interfaceFace), distance));
        }

        public long place(Pos cell, Key key) {
            return WorldIO.place(level, mc(cell), aeItem(key));
        }

        public long drop(Pos cell, Key key, long amount) {
            return WorldIO.drop(level, mc(cell), aeItem(key), amount);
        }

        public boolean holdsAny(Pos cell, Set<Key> keys) {
            return WorldIO.holdsAny(level, mc(cell), aeSet(keys));
        }

        public boolean isBlockItem(Key key) {
            return aeItem(key).getItem() instanceof net.minecraft.world.item.BlockItem;
        }

        public void pickUp(Pos cell, Predicate<Key> wanted, BiConsumer<Key, Long> taken) {
            WorldIO.pickUp(level, mc(cell), k -> wanted.test(Keys.of(k)), wrap(taken));
        }

        public String lastClick() {
            return WorldIO.lastClick;
        }
    }
}
