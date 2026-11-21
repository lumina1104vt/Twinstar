package com.lumina.twinstar.storage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;
import appeng.api.storage.MEStorage;
import appeng.blockentity.grid.AENetworkBlockEntity;
import appeng.util.SettingsFrom;
import com.lumina.twinstar.api.IBigStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Test storage block: any number of item types, any amount each (BigInteger), no capacity limit.
 * AE2 mounts it as ordinary network storage (terminals see it, amounts above a long show as the
 * long maximum); the twin-star engine inserts and extracts exact amounts through {@link IBigStorage}.
 * Breaking it keeps the contents in the dropped item; placing it restores them.
 */
public class BigStorageBlockEntity extends AENetworkBlockEntity implements IStorageProvider, IBigStorage {
    private static final String CONTENTS = "twinstar_contents";
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

    private final LinkedHashMap<AEKey, BigInteger> amounts = new LinkedHashMap<>();
    private final Storage storage = new Storage();

    public BigStorageBlockEntity(BlockPos pos, BlockState state) {
        super(TwinstarContent.BIG_STORAGE_BE.get(), pos, state);
        getMainNode().addService(IStorageProvider.class, this);
    }

    // ── AE2 ──

    @Override
    public void mountInventories(IStorageMounts mounts) {
        mounts.mount(storage);
    }

    /** AE2's long-based view of the same contents. */
    private final class Storage implements MEStorage {
        @Override
        public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (amount <= 0)
                return 0;
            return add(what, BigInteger.valueOf(amount), mode == Actionable.SIMULATE).longValue();
        }

        @Override
        public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
            if (amount <= 0)
                return 0;
            return take(what, BigInteger.valueOf(amount), mode == Actionable.SIMULATE).longValue();
        }

        @Override
        public void getAvailableStacks(KeyCounter out) {
            // several storages add into the same counter: saturate instead of wrapping to negative
            amounts.forEach((key, v) -> {
                long add = v.min(LONG_MAX).longValue();
                long cur = out.get(key);
                long sum = cur + add;
                out.set(key, ((cur ^ sum) & (add ^ sum)) < 0 ? Long.MAX_VALUE : sum);
            });
        }

        @Override
        public Component getDescription() {
            return Component.translatable("block.twinstar.big_storage");
        }
    }

    // ── exact amounts ──

    @Override
    public BigInteger getAmount(AEKey key) {
        return amounts.getOrDefault(key, BigInteger.ZERO);
    }

    @Override
    public BigInteger insertBig(AEKey key, BigInteger amount, boolean simulate) {
        BigInteger in = add(key, amount, simulate);
        if (!simulate && in.signum() > 0)
            contentsChangedOutsideAe2();
        return in;
    }

    @Override
    public BigInteger extractBig(AEKey key, BigInteger amount, boolean simulate) {
        BigInteger out = take(key, amount, simulate);
        if (!simulate && out.signum() > 0)
            contentsChangedOutsideAe2();
        return out;
    }

    private BigInteger add(AEKey key, BigInteger amount, boolean simulate) {
        if (amount.signum() <= 0)
            return BigInteger.ZERO;
        if (!simulate) {
            amounts.merge(key, amount, BigInteger::add);
            saveChanges();
        }
        return amount;      // no limit
    }

    private BigInteger take(AEKey key, BigInteger amount, boolean simulate) {
        BigInteger have = getAmount(key);
        BigInteger take = have.min(amount.max(BigInteger.ZERO));
        if (!simulate && take.signum() > 0) {
            BigInteger left = have.subtract(take);
            if (left.signum() == 0)
                amounts.remove(key);
            else
                amounts.put(key, left);
            saveChanges();
        }
        return take;
    }

    /** AE2 caches the network's contents; writes that did not go through it must say so. */
    private void contentsChangedOutsideAe2() {
        var grid = getMainNode().getGrid();
        if (grid != null)
            grid.getStorageService().invalidateCache();
    }

    @Override
    public void forEachStored(BiConsumer<AEKey, BigInteger> action) {
        amounts.forEach(action);
    }

    // ── saving ──

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        data.put(CONTENTS, writeContents());
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        readContents(data.getList(CONTENTS, Tag.TAG_COMPOUND));
    }

    private ListTag writeContents() {
        ListTag list = new ListTag();
        for (Map.Entry<AEKey, BigInteger> e : amounts.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.put("key", e.getKey().toTagGeneric());
            entry.putString("amount", e.getValue().toString());
            list.add(entry);
        }
        return list;
    }

    private void readContents(ListTag list) {
        amounts.clear();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            AEKey key = AEKey.fromTagGeneric(entry.getCompound("key"));
            if (key == null)
                continue;
            try {
                BigInteger v = new BigInteger(entry.getString("amount"));
                if (v.signum() > 0)
                    amounts.put(key, v);
            } catch (NumberFormatException ignored) {
                // a broken entry is dropped rather than failing the whole block
            }
        }
    }

    /** The dropped item carries the contents (AE2 calls this for the block's own item in the drops). */
    @Override
    public void exportSettings(SettingsFrom mode, CompoundTag output, @Nullable Player player) {
        super.exportSettings(mode, output, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && !amounts.isEmpty() && level != null)
            output.put(CONTENTS, writeContents());
    }

    /** Placing the item restores them. */
    @Override
    public void importSettings(SettingsFrom mode, CompoundTag input, @Nullable Player player) {
        super.importSettings(mode, input, player);
        if (mode == SettingsFrom.DISMANTLE_ITEM && level != null && input.contains(CONTENTS)) {
            readContents(input.getList(CONTENTS, Tag.TAG_COMPOUND));
            saveChanges();
        }
    }
}
