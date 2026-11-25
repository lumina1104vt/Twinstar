package com.lumina.twinstar.usage;

import appeng.parts.AEBasePart;
import com.lumina.bridge.Place;
import com.lumina.bridge.Positions;
import com.lumina.bridge.key.Keys;
import com.lumina.twinstar.monitor.Transfers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** What the AE2 hooks (the mixins) need to tell the monitor where a sender is. Server side only; never throws. */
public final class TransferSites {
    private TransferSites() {
    }

    /** The block entity of an AE2 grid node's owner: itself, or the one a part sits in. */
    public static @Nullable BlockEntity blockOf(@Nullable Object owner) {
        if (owner instanceof BlockEntity be)
            return be;
        if (owner instanceof AEBasePart part)
            return part.getBlockEntity();
        return null;
    }

    public static @Nullable Place placeOf(@Nullable BlockEntity be) {
        Level level = be == null ? null : be.getLevel();
        if (level == null || level.isClientSide)
            return null;
        return Positions.of(GlobalPos.of(level.dimension(), be.getBlockPos().immutable()));
    }

    public static @Nullable Place placeOf(@Nullable Level level, BlockPos pos) {
        if (level == null || level.isClientSide)
            return null;
        return Positions.of(GlobalPos.of(level.dimension(), pos.immutable()));
    }

    /** Same, for a sender that is already a place (a block of another mod, found by {@link HostPlaces}). */
    public static void sentFrom(@Nullable Place at, @Nullable Place to, appeng.api.stacks.AEKey what, long amount, int kind) {
        if (at == null || amount <= 0)
            return;
        try {
            Transfers.record(at, to, Keys.of(what), amount, kind);
        } catch (RuntimeException e) {
            // a monitor must never break the game it watches
        }
    }

    /** {@code amount} of an AE2 key went out of the block {@code from}. */
    public static void sent(@Nullable BlockEntity from, @Nullable Place to, appeng.api.stacks.AEKey what, long amount, int kind) {
        Place at = placeOf(from);
        if (at == null || amount <= 0)
            return;
        try {
            Transfers.record(at, to, Keys.of(what), amount, kind);
        } catch (RuntimeException e) {
            // a monitor must never break the game it watches
        }
    }
}
