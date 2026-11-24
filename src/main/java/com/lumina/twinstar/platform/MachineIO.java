package com.lumina.twinstar.platform;

import com.lumina.twinstar.algorithm.execution.*;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.behaviors.ExternalStorageStrategy;
import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.parts.automation.StackWorldBehaviors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/**
 * What the hub does to a machine behind a machine access interface, through the machine's own
 * item and fluid capabilities (every face and no face; one handler seen from several faces counts
 * once). There is no slot role in a capability, so, by pure queries: a slot or tank that does not
 * accept what is in it ({@code isItemValid} / {@code isFluidValid} false) is an output; so is an item
 * slot with room that refuses one more of what is in it (a simulated insert: Mekanism's output slots
 * accept anything but take nothing from outside). Anything else is an input.
 * <p>
 * Other kinds of stacks (Mekanism's chemicals, through Applied Mekanistics) go through AE2's
 * external storage strategies of their key type on every face: what can be taken out from
 * outside is an output, but only what the runs in the machine make is taken (some packs make FE
 * a key type too: a battery's charge can be taken from outside).
 * Server thread only.
 */
final class MachineIO {
    /** every face, then no face */
    private static final @Nullable Direction[] SIDES = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH,
            Direction.WEST, Direction.EAST, null};

    private MachineIO() {
    }

    static List<IItemHandler> itemHandlers(ServerLevel level, BlockPos pos) {
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IItemHandler> out = new ArrayList<>();
        for (Direction d : SIDES) {
            IItemHandler h = com.lumina.twinstar.util.Caps.item(level, pos, d);
            if (h != null && seen.add(h))
                out.add(h);
        }
        return out;
    }

    static List<IFluidHandler> fluidHandlers(ServerLevel level, BlockPos pos) {
        Set<IFluidHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<IFluidHandler> out = new ArrayList<>();
        for (Direction d : SIDES) {
            IFluidHandler h = com.lumina.twinstar.util.Caps.fluid(level, pos, d);
            if (h != null && seen.add(h))
                out.add(h);
        }
        return out;
    }

    /** Slot {@code s} holds {@code in} (not empty) as an output. */
    private static boolean isOutput(IItemHandler h, int s, ItemStack in) {
        if (!h.isItemValid(s, in))
            return true;
        return room(h, s, in) > 0 && h.insertItem(s, in.copyWithCount(1), true).getCount() == 1;
    }

    /**
     * Room left in slot {@code s} for more of {@code in}: up to a stack, or up to the slot's limit
     * when it already holds more than a stack (Mekanism's slots do). A full stack in an ordinary
     * slot has no room, so refusing one more says nothing about it.
     */
    private static int room(IItemHandler h, int s, ItemStack in) {
        int cap = in.getCount() > in.getMaxStackSize() ? h.getSlotLimit(s) : Math.min(h.getSlotLimit(s), in.getMaxStackSize());
        return cap - in.getCount();
    }

    /**
     * AE2's external storage strategies of the key types other than items and fluids, per machine and
     * face. Made once: each holds a capability cache the level keeps a listener for.
     */
    private static final Map<ServerLevel, Map<BlockPos, List<List<ExternalStorageStrategy>>>> STRATEGIES = new WeakHashMap<>();

    private static List<List<ExternalStorageStrategy>> strategies(ServerLevel level, BlockPos pos) {
        return STRATEGIES.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(pos.immutable(), p -> {
            List<List<ExternalStorageStrategy>> byFace = new ArrayList<>();
            for (Direction d : Direction.values()) {
                List<ExternalStorageStrategy> list = new ArrayList<>();
                StackWorldBehaviors.createExternalStorageStrategies(level, p, d).forEach((type, strategy) -> {
                    if (type != AEKeyType.items() && type != AEKeyType.fluids())
                        list.add(strategy);
                });
                byFace.add(list);
            }
            return byFace;
        });
    }

    /** The machine at {@code pos} is no longer used (its interface is gone). */
    static void forget(ServerLevel level, BlockPos pos) {
        Map<BlockPos, List<List<ExternalStorageStrategy>>> m = STRATEGIES.get(level);
        if (m != null)
            m.remove(pos);
    }

    /** Storages of those other key types (Mekanism's chemicals) on every face, or only on {@code only}. */
    private static List<MEStorage> otherStorages(ServerLevel level, BlockPos pos, boolean extractableOnly, @Nullable Direction only) {
        List<MEStorage> out = new ArrayList<>();
        List<List<ExternalStorageStrategy>> byFace = strategies(level, pos);
        for (Direction d : Direction.values()) {
            if (only != null && d != only)
                continue;
            for (ExternalStorageStrategy strategy : byFace.get(d.ordinal())) {
                MEStorage storage = strategy.createWrapper(extractableOnly, () -> {
                });
                if (storage != null)
                    out.add(storage);
            }
        }
        return out;
    }

    /**
     * Takes everything out of the output slots and tanks, and of other key types what can be taken
     * from outside among {@code wantOther} only (an energy key type would otherwise drain a battery);
     * each amount taken goes to {@code taken}. Items of {@code keep} (materials of the runs in the machine)
     * are left where they are.
     */
    static void extractOutputs(ServerLevel level, BlockPos pos, Set<AEKey> wantOther, Set<AEKey> keep, Set<AEKey> products, BiConsumer<AEKey, Long> taken) {
        for (IItemHandler h : itemHandlers(level, pos))
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack in = h.getStackInSlot(s);
                if (in.isEmpty())
                    continue;
                AEItemKey inKey = AEItemKey.of(in);
                // what a run's materials look like in a slot that refuses more (a single-item chamber) is not a product
                if (!keep.isEmpty() && keep.contains(inKey))
                    continue;
                // a full stack that the slot would take more of looks like a material, but one of the products the runs
                // in the machine are making is a product (a machine that makes a whole stack at once)
                if (!isOutput(h, s, in) && !(products.contains(inKey) && h.isItemValid(s, in) && room(h, s, in) <= 0))
                    continue;
                // a slot may hold more than one stack's worth (a big buffer slot): one extract hands over at most one stack
                for (int round = 0; round < 4096; round++) {
                    ItemStack now = h.getStackInSlot(s);
                    if (now.isEmpty() || !AEItemKey.of(now).equals(inKey))
                        break;
                    ItemStack got = h.extractItem(s, Math.max(now.getCount(), now.getMaxStackSize()), false);
                    if (got.isEmpty())
                        break;
                    taken.accept(inKey, (long) got.getCount());
                }
            }
        for (IFluidHandler h : fluidHandlers(level, pos))
            for (int t = 0; t < h.getTanks(); t++) {
                FluidStack in = h.getFluidInTank(t);
                if (in.isEmpty() || h.isFluidValid(t, in))
                    continue;
                FluidStack got = h.drain(in.copy(), IFluidHandler.FluidAction.EXECUTE);
                if (!got.isEmpty())
                    taken.accept(AEFluidKey.of(got), (long) got.getAmount());
            }
        if (wantOther.isEmpty())
            return;
        for (MEStorage storage : otherStorages(level, pos, true, null))
            for (var e : storage.getAvailableStacks()) {
                if (!wantOther.contains(e.getKey()))
                    continue;
                long got = storage.extract(e.getKey(), e.getLongValue(), Actionable.MODULATE, IActionSource.empty());
                if (got > 0)
                    taken.accept(e.getKey(), got);
            }
    }

    /**
     * Takes up to {@code amount} of {@code key} back out of the machine, from any slot (an input slot too: a catalyst
     * that stayed in); each piece taken goes to {@code taken}. Returns how much came out. A machine's own rules may
     * refuse to give a slot's stack up: then it stays.
     */
    static long retrieve(ServerLevel level, BlockPos pos, AEItemKey key, long amount, BiConsumer<AEKey, Long> taken) {
        long left = amount;
        for (IItemHandler h : itemHandlers(level, pos))
            for (int s = 0; s < h.getSlots() && left > 0; s++) {
                ItemStack in = h.getStackInSlot(s);
                if (in.isEmpty() || !key.matches(in))
                    continue;
                ItemStack got = h.extractItem(s, (int) Math.min(left, in.getCount()), false);
                if (!got.isEmpty()) {
                    left -= got.getCount();
                    taken.accept(AEItemKey.of(got), (long) got.getCount());
                }
            }
        return amount - left;
    }

    /** Some input slot or tank holds one of {@code keys}. */
    static boolean holdsAny(ServerLevel level, BlockPos pos, Set<AEKey> keys) {
        if (keys.isEmpty())
            return false;
        for (IItemHandler h : itemHandlers(level, pos))
            for (int s = 0; s < h.getSlots(); s++) {
                ItemStack in = h.getStackInSlot(s);
                if (!in.isEmpty() && keys.contains(AEItemKey.of(in)) && !isOutput(h, s, in))
                    return true;
            }
        for (IFluidHandler h : fluidHandlers(level, pos))
            for (int t = 0; t < h.getTanks(); t++) {
                FluidStack in = h.getFluidInTank(t);
                if (!in.isEmpty() && h.isFluidValid(t, in) && keys.contains(AEFluidKey.of(in)))
                    return true;
            }
        boolean others = false;
        for (AEKey k : keys)
            others |= !(k instanceof AEItemKey) && !(k instanceof AEFluidKey);
        if (others)
            for (MEStorage storage : otherStorages(level, pos, false, null)) {
                KeyCounter all = storage.getAvailableStacks();
                for (AEKey k : keys)
                    if (all.get(k) > 0)
                        return true;
            }
        return false;
    }

    /**
     * Puts up to {@code amount} of {@code key} in through {@code faces} (a null face: no face; for
     * other key types, any face), the first face first; returns how much went in.
     */
    static long insert(ServerLevel level, BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
        long left = amount;
        for (Direction face : faces) {
            if (left <= 0)
                break;
            if (key instanceof AEItemKey item) {
                IItemHandler h = com.lumina.twinstar.util.Caps.item(level, pos, face);
                if (h == null)
                    continue;
                int max = Math.max(1, item.getMaxStackSize());
                while (left > 0) {
                    int n = (int) Math.min(max, left);
                    ItemStack rest = ItemHandlerHelper.insertItemStacked(h, item.toStack(n), false);
                    int in = n - rest.getCount();
                    left -= in;
                    if (in < n)
                        break;      // full: next face
                }
            } else if (key instanceof AEFluidKey fluid) {
                IFluidHandler h = com.lumina.twinstar.util.Caps.fluid(level, pos, face);
                if (h == null)
                    continue;
                while (left > 0) {
                    int n = (int) Math.min(Integer.MAX_VALUE, left);
                    int in = h.fill(fluid.toStack(n), IFluidHandler.FluidAction.EXECUTE);
                    left -= in;
                    if (in < n)
                        break;
                }
            } else {
                // no face: any face that takes it
                for (MEStorage storage : otherStorages(level, pos, false, face)) {
                    left -= storage.insert(key, left, Actionable.MODULATE, IActionSource.empty());
                    if (left <= 0)
                        break;
                }
            }
        }
        return amount - left;
    }

    // ───────────────────────────── debug log ─────────────────────────────

    private static String faceName(@Nullable Direction d) {
        return d == null ? "不指定面" : d.getName();
    }

    private static String name(AEKey key) {
        return key.getDisplayName().getString();
    }

    /**
     * For the debug log: what the hub sees in the machine, face by face (no merging of handlers
     * seen from several faces), and how it reads each slot, tank and other stack.
     */
    static String describe(ServerLevel level, BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        for (Direction d : SIDES) {
            IItemHandler h = com.lumina.twinstar.util.Caps.item(level, pos, d);
            if (h != null)
                for (int s = 0; s < h.getSlots(); s++) {
                    ItemStack in = h.getStackInSlot(s);
                    if (in.isEmpty())
                        continue;
                    int room = room(h, s, in);
                    boolean refused = room > 0 && h.insertItem(s, in.copyWithCount(1), true).getCount() == 1;
                    int canTake = h.extractItem(s, in.getCount(), true).getCount();
                    sb.append("\n    物品 ").append(faceName(d)).append(" 格").append(s).append("：")
                            .append(in.getHoverName().getString()).append("×").append(in.getCount())
                            .append(" 接受=").append(h.isItemValid(s, in)).append(" 空間=").append(room)
                            .append(" 模擬放入被拒=").append(refused).append(" → ")
                            .append(isOutput(h, s, in) ? "輸出" : "輸入").append("，模擬抽出=").append(canTake);
                }
            IFluidHandler f = com.lumina.twinstar.util.Caps.fluid(level, pos, d);
            if (f != null)
                for (int t = 0; t < f.getTanks(); t++) {
                    FluidStack in = f.getFluidInTank(t);
                    if (in.isEmpty())
                        continue;
                    sb.append("\n    流體 ").append(faceName(d)).append(" 槽").append(t).append("：")
                            .append(in.getDisplayName().getString()).append("×").append(in.getAmount())
                            .append(" 接受=").append(f.isFluidValid(t, in)).append(" → ")
                            .append(f.isFluidValid(t, in) ? "輸入" : "輸出").append("，模擬抽出=")
                            .append(f.drain(in.copy(), IFluidHandler.FluidAction.SIMULATE).getAmount());
                }
        }
        for (Direction d : Direction.values()) {
            List<MEStorage> all = otherStorages(level, pos, false, d);
            List<MEStorage> out = otherStorages(level, pos, true, d);
            KeyCounter seen = new KeyCounter();
            all.forEach(st -> st.getAvailableStacks(seen));
            KeyCounter takeable = new KeyCounter();
            out.forEach(st -> st.getAvailableStacks(takeable));
            for (var e : seen)
                sb.append("\n    其他 ").append(d.getName()).append("：").append(name(e.getKey())).append("×").append(e.getLongValue())
                        .append(" 可從外抽=").append(takeable.get(e.getKey()));
            if (all.isEmpty())
                continue;
            if (seen.isEmpty())
                sb.append("\n    其他 ").append(d.getName()).append("：介面 ").append(all.size()).append(" 個，空的");
        }
        return sb.isEmpty() ? "（什麼都沒看到）" : sb.toString();
    }

    /** For the debug log: when a face has no interface, what block it is and which faces do have one. */
    private static String whatIsThere(ServerLevel level, BlockPos pos, boolean items) {
        StringBuilder has = new StringBuilder();
        for (Direction d : Direction.values()) {
            boolean there = items ? com.lumina.twinstar.util.Caps.item(level, pos, d) != null : com.lumina.twinstar.util.Caps.fluid(level, pos, d) != null;
            if (there)
                has.append(has.isEmpty() ? "" : "、").append(d.getName());
        }
        boolean none = items ? com.lumina.twinstar.util.Caps.item(level, pos, null) == null : com.lumina.twinstar.util.Caps.fluid(level, pos, null) == null;
        var be = level.getBlockEntity(pos);
        return "（方塊 " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + "，方塊實體 "
                + (be == null ? "無" : be.getClass().getSimpleName()) + "；有介面的面：" + (has.isEmpty() ? "沒有" : has) + "；不指定面：" + (none ? "也沒有" : "有") + "）";
    }

    /** For the debug log: what the item slots of the block at {@code pos} hold now. */
    static String contentsAt(ServerLevel level, BlockPos pos) {
        IItemHandler h = com.lumina.twinstar.util.Caps.item(level, pos, null);
        return h == null ? "（沒有物品介面）" : contentsOf(h);
    }

    /** For the debug log: what the slots of a handler hold (ids, so the log reads in any encoding). */
    private static String contentsOf(IItemHandler h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < h.getSlots(); i++) {
            ItemStack stack = h.getStackInSlot(i);
            if (!stack.isEmpty())
                sb.append(sb.isEmpty() ? "" : "、").append("格").append(i + 1).append("=").append(stack.getCount()).append("×")
                        .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        return sb.isEmpty() ? "空的" : sb.toString();
    }

    /** For the debug log: how much of {@code key} each face would take now (simulated). */
    static String describeInsert(ServerLevel level, BlockPos pos, List<@Nullable Direction> faces, AEKey key, long amount) {
        StringBuilder sb = new StringBuilder();
        for (Direction face : faces) {
            sb.append(sb.isEmpty() ? "" : "、").append(faceName(face)).append("：");
            if (key instanceof AEItemKey item) {
                IItemHandler h = com.lumina.twinstar.util.Caps.item(level, pos, face);
                if (h == null) {
                    sb.append("沒有物品介面").append(whatIsThere(level, pos, true));
                    continue;
                }
                int n = (int) Math.min(item.getMaxStackSize(), amount);
                ItemStack rest = ItemHandlerHelper.insertItemStacked(h, item.toStack(n), true);
                sb.append("模擬放入 ").append(n - rest.getCount()).append("／").append(n).append("（").append(h.getSlots()).append(" 格）");
                if (face == faces.get(0))
                    sb.append("，裡面現有：").append(contentsOf(h));
            } else if (key instanceof AEFluidKey fluid) {
                IFluidHandler h = com.lumina.twinstar.util.Caps.fluid(level, pos, face);
                if (h == null) {
                    sb.append("沒有流體介面").append(whatIsThere(level, pos, false));
                    continue;
                }
                int n = (int) Math.min(Integer.MAX_VALUE, amount);
                sb.append("模擬放入 ").append(h.fill(fluid.toStack(n), IFluidHandler.FluidAction.SIMULATE)).append("／").append(n);
            } else {
                List<MEStorage> storages = otherStorages(level, pos, false, face);
                if (storages.isEmpty()) {
                    sb.append("沒有 ").append(key.getType().getDescription().getString()).append(" 介面");
                    continue;
                }
                long took = 0;
                for (MEStorage st : storages)
                    took += st.insert(key, amount, Actionable.SIMULATE, IActionSource.empty());
                sb.append("模擬放入 ").append(took).append("／").append(amount).append("（介面 ").append(storages.size()).append(" 個）");
            }
        }
        return sb.toString();
    }
}
