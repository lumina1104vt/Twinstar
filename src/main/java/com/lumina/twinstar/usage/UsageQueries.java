package com.lumina.twinstar.usage;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.api.IMachineAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the usage list of one network from the server's ledger: the machines of the interfaces that are on it. Server thread. */
public final class UsageQueries {
    private UsageQueries() {
    }

    private record IfaceKey(GlobalPos place, int side) {
    }

    /** @param interfaces the machine access interfaces on the network */
    public static UsageSnapshot build(MinecraftServer server, Collection<IMachineAccess> interfaces) {
        UsageLedger<UsageStore.Source, AEKey> ledger = UsageStore.ledger(server);
        int now = server.getTickCount();
        ledger.prune(now);
        int[] wb = new int[UsageSnapshot.WINDOWS];
        long[] spans = new long[UsageSnapshot.WINDOWS];
        for (int w = 0; w < wb.length; w++) {
            wb[w] = ledger.bucketsFor(UsageSnapshot.WINDOW_TICKS[w]);
            spans[w] = ledger.spanTicks(now, wb[w]);
        }
        Map<IfaceKey, IMachineAccess> byIface = new HashMap<>();
        for (IMachineAccess a : interfaces)
            if (a.interfacePlace() != null)
                byIface.put(new IfaceKey(com.lumina.bridge.Positions.toMc(a.interfacePlace()), a.interfaceSide()), a);
        // every machine of every interface of the network: the ones that used something, and the ones that did not
        Map<UsageStore.Source, IMachineAccess> sources = new LinkedHashMap<>();
        for (IMachineAccess a : byIface.values())
            for (com.lumina.bridge.Place place : a.machinePlaces())
                sources.putIfAbsent(new UsageStore.Source(com.lumina.bridge.Positions.toMc(place), com.lumina.bridge.Positions.toMc(a.interfacePlace()), a.interfaceSide()), a);
        for (UsageStore.Source s : ledger.sources())
            if (s.iface() != null) {
                IMachineAccess a = byIface.get(new IfaceKey(s.iface(), s.side()));
                if (a != null)
                    sources.putIfAbsent(s, a);
            }

        List<UsageSnapshot.Machine> out = new ArrayList<>();
        for (Map.Entry<UsageStore.Source, IMachineAccess> e : sources.entrySet()) {
            if (out.size() >= UsageSnapshot.MAX_MACHINES)
                break;
            UsageStore.Source s = e.getKey();
            UsageLedger.Usage<AEKey> u = ledger.usage(s, now, wb);
            ServerLevel level = server.getLevel(s.machine().dimension());
            boolean loaded = level != null && level.isLoaded(s.machine().pos());
            String label = u == null ? null : u.label();
            if (loaded) {
                String id = UsageStore.blockId(level, s.machine().pos());
                if (id != null && !id.equals(label)) {
                    label = id;
                    ledger.setLabel(s, id);
                }
            }
            List<UsageSnapshot.Row> rows = new ArrayList<>();
            long[] other = new long[UsageSnapshot.WINDOWS];
            int kinds = 0;
            if (u != null) {
                kinds = u.kinds();
                System.arraycopy(u.other(), 0, other, 0, other.length);
                for (int i = 0; i < u.keys().size() && rows.size() < UsageSnapshot.MAX_ROWS; i++)
                    rows.add(new UsageSnapshot.Row(u.keys().get(i), u.of(i)));
            }
            out.add(new UsageSnapshot.Machine(s.machine().dimension().location().toString(), s.machine().pos().asLong(),
                    s.iface().dimension().location().toString(), s.iface().pos().asLong(), s.side(),
                    nameOf(loaded ? level : null, s.machine().pos(), label), e.getValue().interfaceName(), loaded, kinds, rows, other));
        }
        return new UsageSnapshot(true, spans, ledger.droppedSources(), ledger.memoryBytes(), out);
    }

    /** The name the player gave the block, else the block's own name (from what was seen before when it is not loaded). */
    private static Component nameOf(@Nullable ServerLevel level, BlockPos pos, @Nullable String blockId) {
        if (level != null) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null) {
                Component custom = be.components().get(DataComponents.CUSTOM_NAME);
                if (custom != null)
                    return custom;
            }
        }
        if (blockId != null) {
            ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id != null && BuiltInRegistries.BLOCK.containsKey(id))
                return BuiltInRegistries.BLOCK.get(id).getName();
        }
        return Component.translatable("gui.twinstar.usage.unknown_machine");
    }
}
