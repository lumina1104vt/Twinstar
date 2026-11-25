package com.lumina.twinstar.usage;

import appeng.api.stacks.AEKey;
import com.lumina.twinstar.api.IMachineAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * What the machines of every network used up lately: one {@link UsageLedger} for the whole server, kept in memory only (a restart
 * empties it; nothing is saved, so there is nothing that could fail to load). It is not tied to a grid, so a network that is split
 * or joined (the grid object is made again) keeps its history; a list for one network picks the sources whose interface is on it.
 * Server thread only.
 */
public final class UsageStore {
    /** a hub run's material was put into the machine */
    public static final int HUB = 1;
    /** an ME interface stocked the machine */
    public static final int STOCK = 2;

    /** The monitor's entry ({@link com.lumina.twinstar.monitor.Transfers}): a block sent {@code amount} of {@code key} on. */
    public static void transfer(com.lumina.bridge.Place from, @Nullable com.lumina.bridge.Place to, com.lumina.bridge.key.Key key,
                                long amount, int kind) {
        MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        AEKey ae = com.lumina.bridge.key.Keys.toAe(key);
        if (server == null || ae == null || amount <= 0)
            return;
        net.minecraft.resources.ResourceLocation dimName = net.minecraft.resources.ResourceLocation.tryParse(from.dimension());
        if (dimName == null)
            return;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim =
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimName);
        ServerLevel level = server.getLevel(dim);
        if (level == null)
            return;
        BlockPos pos = com.lumina.bridge.Positions.toMc(from.pos());
        // a sender is its own source: it stands in for itself, with no interface (side -1)
        record(level, pos, GlobalPos.of(dim, pos.immutable()), TRANSFER_SIDE, ae, amount, kind);
    }

    /** {@link Source#side()} of a source that is a sender and no machine (not -1: that is an interface that is a block of its own). */
    public static final int TRANSFER_SIDE = -2;

    /** What used something: a machine, as one interface (this block, this side) stands for it. */
    public record Source(GlobalPos machine, @Nullable GlobalPos iface, int side) {
    }

    private static final int PRUNE_EVERY_TICKS = 200;

    private static @Nullable UsageLedger<Source, AEKey> ledger;
    private static @Nullable MinecraftServer owner;
    private static long prunedAt;

    private UsageStore() {
    }

    /** The ledger of {@code server} (a new one when the server is another one than last time: a world was left and another opened). */
    public static UsageLedger<Source, AEKey> ledger(MinecraftServer server) {
        if (ledger == null || owner != server) {
            owner = server;
            ledger = new UsageLedger<>(server.getTickCount());
            prunedAt = server.getTickCount();
        }
        return ledger;
    }

    /** The server stopped: nothing is kept. */
    public static void clear() {
        ledger = null;
        owner = null;
    }

    /** The hub put {@code amount} of {@code key} into the machine {@code access} stands for. */
    public static void recordHub(IMachineAccess access, com.lumina.bridge.key.Key key, long amount) {
        recordHubAe(access, com.lumina.bridge.key.Keys.toAe(key), amount);
    }

    private static void recordHubAe(IMachineAccess access, AEKey key, long amount) {
        ServerLevel level = access.getMachineLevel();
        if (level != null)
            record(level, com.lumina.bridge.Positions.toMc(access.getMachinePos()), com.lumina.bridge.Positions.toMc(access.interfacePlace()), access.interfaceSide(), key, amount, HUB);
    }

    /** {@code amount} of {@code key} went into the machine at {@code machine} (in {@code level}) for the interface at {@code iface}. */
    public static void record(ServerLevel level, BlockPos machine, @Nullable GlobalPos iface, int side, AEKey key, long amount, int kind) {
        if (amount <= 0)
            return;
        MinecraftServer server = level.getServer();
        UsageLedger<Source, AEKey> l = ledger(server);
        int now = server.getTickCount();
        Source src = new Source(GlobalPos.of(level.dimension(), machine.immutable()), iface, side);
        l.add(src, key, amount, now, kind, l.has(src) ? null : blockId(level, machine));
        if (now - prunedAt >= PRUNE_EVERY_TICKS) {
            prunedAt = now;
            l.prune(now);
        }
    }

    /** The registry name of the block at {@code pos}, or null while its chunk is not loaded. */
    public static @Nullable String blockId(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos))
            return null;
        return String.valueOf(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()));
    }
}
