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
            if (r == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION)
                state.useWithoutItem(level, p, hit);
        } catch (RuntimeException e) {
            // a block that does not expect a fake player: nothing taken
        }
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
