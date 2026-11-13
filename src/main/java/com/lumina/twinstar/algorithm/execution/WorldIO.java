package com.lumina.twinstar.algorithm.execution;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * What the hub does to the world around a machine, for materials that do not go in through a
 * capability: a right-click with the material on a block that has none (an altar), a block placed
 * or an item dropped in front of a machine's opening (an atomic reconstructor), and what comes of
 * it picked up again. Through a fake player of its own. Server thread only.
 */
final class WorldIO {
    private static final GameProfile PROFILE = new GameProfile(UUID.fromString("5c7a1e0e-7d1b-4c8e-9f7a-74776e737461"), "[Twinstar]");

    private WorldIO() {
    }

    private static FakePlayer player(ServerLevel level, BlockPos at) {
        FakePlayer p = FakePlayerFactory.get(level, PROFILE);
        p.setPos(Vec3.atCenterOf(at));
        return p;
    }

    /**
     * Right-clicks the block at {@code pos} from {@code face} with up to a stack of {@code key} in
     * hand; returns how much it took. Anything else the click left in the fake player's hands or
     * inventory (what the block gave back) goes to {@code stray}.
     */
    /** What the last click did, for the debug log: which of the block's handlers answered and what. */
    static volatile String lastClick = "";

    static long rightClick(ServerLevel level, BlockPos pos, Direction face, AEItemKey key, long amount, BiConsumer<AEKey, Long> stray) {
        int n = (int) Math.min(amount, Math.max(1, key.getMaxStackSize()));
        if (n <= 0)
            return 0;
        FakePlayer p = player(level, pos.relative(face));
        ItemStack hand = key.toStack(n);
        p.setItemInHand(InteractionHand.MAIN_HAND, hand);
        BlockState state = level.getBlockState(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        try {
            ItemInteractionResult r = state.useItemOn(hand, level, p, InteractionHand.MAIN_HAND, hit);
            lastClick = "useItemOn=" + r;
            if (r == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)
                lastClick += "，useWithoutItem=" + state.useWithoutItem(level, p, hit);
        } catch (RuntimeException e) {
            // a block that does not expect a fake player: nothing taken
            lastClick = "點擊時出錯：" + e;
        }
        closeMenu(p);
        ItemStack left = p.getItemInHand(InteractionHand.MAIN_HAND);
        long taken = n;
        if (!left.isEmpty() && key.matches(left))
            taken = n - left.getCount();
        else if (!left.isEmpty())
            stray.accept(AEItemKey.of(left), (long) left.getCount());
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) {
                stray.accept(AEItemKey.of(s), (long) s.getCount());
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
        return Math.max(0, taken);
    }

    /**
     * Right-clicks the block at {@code pos} from {@code face} with one {@code tool} in hand (a hammer on an altar).
     * Returns the tool as the click left it (worn), or null when it is gone (broke, or the block took it). Anything
     * else the click gave goes to {@code stray}.
     */
    static @org.jetbrains.annotations.Nullable AEItemKey useTool(ServerLevel level, BlockPos pos, Direction face, AEItemKey tool,
                                                                   BiConsumer<AEKey, Long> stray) {
        FakePlayer p = player(level, pos.relative(face));
        ItemStack hand = tool.toStack(1);
        p.setItemInHand(InteractionHand.MAIN_HAND, hand);
        clickBlock(level, pos, face, p, hand);
        closeMenu(p);
        ItemStack left = p.getItemInHand(InteractionHand.MAIN_HAND);
        AEItemKey back = left.isEmpty() ? null : AEItemKey.of(left);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        emptyInventory(p, stray);
        return back;
    }

    /** Right-clicks the block at {@code pos} with an empty hand (takes back what a block gives to a hand); what it gave goes to {@code stray}. */
    static void emptyClick(ServerLevel level, BlockPos pos, Direction face, BiConsumer<AEKey, Long> stray) {
        FakePlayer p = player(level, pos.relative(face));
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        clickBlock(level, pos, face, p, ItemStack.EMPTY);
        closeMenu(p);
        ItemStack left = p.getItemInHand(InteractionHand.MAIN_HAND);
        if (!left.isEmpty())
            stray.accept(AEItemKey.of(left), (long) left.getCount());
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        emptyInventory(p, stray);
    }

    /**
     * Takes the products out of the GUI of the block at {@code pos}: opens it as {@link #insertViaGui} does, and takes from
     * the slot the pattern names for a product ({@code products}: key to slot number, 0 = not named) or, for one not
     * named, from each slot that does not take what it holds (a result slot), when the stack is one of {@code products}
     * (nothing else is touched: the materials lie in slots that do take them). Each piece taken goes to {@code taken}.
     * Returns how many items it took.
     */
    static long extractViaGui(ServerLevel level, BlockPos pos, Direction face, java.util.Map<AEKey, Integer> products, BiConsumer<AEKey, Long> taken,
                              BiConsumer<AEKey, Long> stray) {
        FakePlayer p = player(level, pos.relative(face));
        AbstractContainerMenu menu = openGui(level, pos, face, p);
        if (menu == null)
            return 0;
        long total = 0;
        int n = 0;
        try {
            for (Slot s : menu.slots) {
                if (s.container == p.getInventory())
                    continue;
                n++;
                ItemStack in = s.getItem();
                if (in.isEmpty() || !s.mayPickup(p))
                    continue;
                Integer want = products.get(AEItemKey.of(in));
                if (want == null)
                    continue;                       // not a product of the runs in the machine
                if (want > 0 ? want != n : s.mayPlace(in))
                    continue;
                int count = in.getCount();
                ItemStack got = s.safeTake(count, count, p);
                if (!got.isEmpty()) {
                    taken.accept(AEItemKey.of(got), (long) got.getCount());
                    total += got.getCount();
                }
            }
            menu.broadcastChanges();
        } catch (RuntimeException e) {
            lastClick += "；從 GUI 取出時出錯：" + e;
        }
        closeMenu(p);
        // whatever else ended up with the fake player (a result slot's by-products)
        emptyInventory(p, stray);
        return total;
    }

    /** A GUI the fake player has open is closed (what it holds goes back to the player's inventory). */
    private static void closeMenu(FakePlayer p) {
        try {
            if (p.containerMenu != p.inventoryMenu)
                p.doCloseContainer();
        } catch (RuntimeException e) {
            lastClick += "，關 GUI 時出錯：" + e;
        }
    }

    /**
     * Puts up to {@code amount} of {@code key} into the GUI of the block at {@code pos}: opens it as the fake
     * player with an empty hand, puts the material into {@code onlySlot} (1 and up) or, when that is 0, into the slots that take it (what the block's own rules let in),
     * closes it. For blocks that keep their items inside and show them only in a GUI. Returns how much went in
     * (what the GUI gave back on closing does not count); anything else that came out goes to {@code stray}.
     */
    static long insertViaGui(ServerLevel level, BlockPos pos, Direction face, AEItemKey key, long amount, int onlySlot, BiConsumer<AEKey, Long> stray) {
        FakePlayer p = player(level, pos.relative(face));
        AbstractContainerMenu menu = openGui(level, pos, face, p);
        if (menu == null)
            return 0;
        long placed = 0;
        int guiSlots = 0;
        int max = Math.max(1, key.getMaxStackSize());
        try {
            for (Slot s : menu.slots) {
                if (s.container == p.getInventory())
                    continue;
                guiSlots++;
                // a set slot number: that slot only (1 = the first slot that is not the player's inventory)
                if (placed >= amount || onlySlot > 0 && guiSlots != onlySlot)
                    continue;
                ItemStack probe = key.toStack((int) Math.min(max, amount - placed));
                int before = probe.getCount();
                if (!s.mayPlace(probe))
                    continue;
                placed += before - s.safeInsert(probe).getCount();
            }
            menu.broadcastChanges();
        } catch (RuntimeException e) {
            lastClick += "；放進 GUI 時出錯：" + e;
        }
        closeMenu(p);
        // what a GUI hands back on closing (a crafting grid) was not put in
        long back = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty())
                continue;
            if (key.matches(s))
                back += s.getCount();
            else
                stray.accept(AEItemKey.of(s), (long) s.getCount());
            p.getInventory().setItem(i, ItemStack.EMPTY);
        }
        long in = Math.max(0, placed - back);
        lastClick += "；GUI 有 " + guiSlots + " 個格子，放進 " + placed + "，關閉時退回 " + back;
        return in;
    }

    private static void clickBlock(ServerLevel level, BlockPos pos, Direction face, FakePlayer p, ItemStack hand) {
        BlockState state = level.getBlockState(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        try {
            ItemInteractionResult r = hand.isEmpty() ? ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
                    : state.useItemOn(hand, level, p, InteractionHand.MAIN_HAND, hit);
            if (r == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)
                clickResult = "useWithoutItem=" + state.useWithoutItem(level, p, hit);
            else
                clickResult = "useItemOn=" + r;
        } catch (RuntimeException e) {
            // a block that does not expect a fake player: nothing happens
            clickResult = "點擊時出錯：" + e;
        }
    }

    /** What the last {@link #clickBlock} got, for the log. */
    private static String clickResult = "";

    /**
     * The block's GUI opened for the fake player: by an empty-hand right-click, and when that opens nothing (the
     * fake player is not given GUIs), by making the block's menu ourselves from its menu provider and giving it
     * to the player. Null when neither works; {@link #lastClick} says what each did.
     */
    private static @org.jetbrains.annotations.Nullable AbstractContainerMenu openGui(ServerLevel level, BlockPos pos, Direction face, FakePlayer p) {
        closeMenu(p);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        clickBlock(level, pos, face, p, ItemStack.EMPTY);
        lastClick = "空手右鍵：" + clickResult;
        if (p.containerMenu != p.inventoryMenu)
            return p.containerMenu;
        BlockState state = level.getBlockState(pos);
        net.minecraft.world.MenuProvider provider = null;
        try {
            provider = state.getMenuProvider(level, pos);
            if (provider == null && level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider mp)
                provider = mp;
        } catch (RuntimeException e) {
            lastClick += "；找選單提供者時出錯：" + e;
        }
        if (provider == null) {
            lastClick += "；方塊和方塊實體都沒有 MenuProvider";
            return null;
        }
        try {
            AbstractContainerMenu menu = provider.createMenu(1, p.getInventory(), p);
            if (menu == null) {
                lastClick += "；MenuProvider（" + provider.getClass().getSimpleName() + "）沒有建出選單";
                return null;
            }
            p.containerMenu = menu;
            lastClick += "；GUI 由 MenuProvider（" + provider.getClass().getSimpleName() + "）直接建立";
            return menu;
        } catch (RuntimeException e) {
            lastClick += "；用 MenuProvider 建選單時出錯：" + e;
            return null;
        }
    }

    private static void emptyInventory(FakePlayer p, BiConsumer<AEKey, Long> stray) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (!s.isEmpty()) {
                stray.accept(AEItemKey.of(s), (long) s.getCount());
                p.getInventory().setItem(i, ItemStack.EMPTY);
            }
        }
    }

    /**
     * The cell {@code distance} blocks in front of the machine at {@code pos}: the way its block
     * faces when it has a facing, else away from the interface on {@code interfaceFace}.
     */
    static BlockPos front(ServerLevel level, BlockPos pos, Direction interfaceFace, int distance) {
        BlockState state = level.getBlockState(pos);
        Direction d;
        if (state.hasProperty(BlockStateProperties.FACING))
            d = state.getValue(BlockStateProperties.FACING);
        else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING))
            d = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        else
            d = interfaceFace.getOpposite();
        return pos.relative(d, distance);
    }

    /** Places one of {@code key} as a block at {@code cell} when there is room; 1 when it did. */
    static long place(ServerLevel level, BlockPos cell, AEItemKey key) {
        if (!(key.getItem() instanceof BlockItem block) || !level.getBlockState(cell).canBeReplaced())
            return 0;
        FakePlayer p = player(level, cell.above());
        ItemStack stack = key.toStack(1);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(cell), Direction.UP, cell, false);
        boolean placed;
        try {
            placed = block.place(new BlockPlaceContext(level, p, InteractionHand.MAIN_HAND, stack, hit)).consumesAction();
        } catch (RuntimeException e) {
            placed = false;
        }
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        return placed ? 1 : 0;
    }

    /**
     * Drops {@code amount} of {@code key} at {@code cell}, lying still and never despawning; returns it
     * all. What lies there already of it is topped up first: items that never despawn do not merge by
     * themselves, and a reconstructor converts one lying stack per shot.
     */
    static long drop(ServerLevel level, BlockPos cell, AEItemKey key, long amount) {
        long left = amount;
        int max = Math.max(1, key.getMaxStackSize());
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(cell))) {
            if (left <= 0)
                break;
            ItemStack s = e.getItem();
            if (!e.isAlive() || s.isEmpty() || !key.matches(s) || s.getCount() >= max)
                continue;
            int n = (int) Math.min(max - s.getCount(), left);
            e.setItem(s.copyWithCount(s.getCount() + n));
            left -= n;
        }
        while (left > 0) {
            int n = (int) Math.min(max, left);
            ItemEntity e = new ItemEntity(level, cell.getX() + 0.5, cell.getY() + 0.05, cell.getZ() + 0.5, key.toStack(n), 0, 0, 0);
            e.setUnlimitedLifetime();
            e.setPickUpDelay(40);
            level.addFreshEntity(e);
            left -= n;
        }
        return amount;
    }

    /** Something of {@code keys} lies at {@code cell}, as an item or as the block there. */
    static boolean holdsAny(ServerLevel level, BlockPos cell, Set<AEKey> keys) {
        if (keys.isEmpty() || !level.isLoaded(cell))
            return false;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(cell)))
            if (e.isAlive() && !e.getItem().isEmpty() && keys.contains(AEItemKey.of(e.getItem())))
                return true;
        BlockState state = level.getBlockState(cell);
        if (state.isAir())
            return false;
        AEItemKey k = AEItemKey.of(state.getBlock().asItem());
        return k != null && keys.contains(k);
    }

    /**
     * Picks up at {@code cell} what is {@code wanted}: items lying there, and the block there (as its
     * item, one). Each amount taken goes to {@code taken}.
     */
    static void pickUp(ServerLevel level, BlockPos cell, java.util.function.Predicate<AEKey> wanted, BiConsumer<AEKey, Long> taken) {
        if (!level.isLoaded(cell))
            return;
        List<ItemEntity> items = new ArrayList<>(level.getEntitiesOfClass(ItemEntity.class, new AABB(cell)));
        for (ItemEntity e : items) {
            ItemStack s = e.getItem();
            if (!e.isAlive() || s.isEmpty())
                continue;
            AEItemKey k = AEItemKey.of(s);
            if (wanted.test(k)) {
                taken.accept(k, (long) s.getCount());
                e.discard();
            }
        }
        BlockState state = level.getBlockState(cell);
        if (!state.isAir()) {
            AEItemKey k = AEItemKey.of(state.getBlock().asItem());
            if (k != null && wanted.test(k) && level.removeBlock(cell, false))
                taken.accept(k, 1L);
        }
    }
}
