package com.lumina.twinstar.order;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.stacks.AEKey;
import appeng.menu.locator.MenuHostLocator;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.util.AmountParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;

/**
 * Server side of "next" in the twin-star order screen (craft only what is missing, then the plan
 * screen), and when that screen replaces AE2's amount screen.
 */
public final class OrderFlow {
    private OrderFlow() {
    }

    /** The grid behind a menu host, or null. */
    public static @Nullable IGrid gridOf(@Nullable IActionHost host) {
        IGridNode node = host != null ? host.getActionableNode() : null;
        return node != null ? node.getGrid() : null;
    }

    public static boolean hasHub(@Nullable IGrid grid) {
        if (grid == null)
            return false;
        ITwinStarService service = grid.getService(ITwinStarService.class);
        return service != null && service.hasHub();
    }

    /**
     * "Next" in the twin-star order screen: craft only what is missing (exact stock), then the
     * twin-star plan screen.
     */
    public static void startTwinStar(ServerPlayer player, @Nullable IGrid grid, @Nullable MenuHostLocator locator,
                                     Runnable back, AEKey what, BigInteger amount, boolean craftMissingAmount,
                                     boolean bestEffort, boolean autoStart) {
        if (locator == null || amount.compareTo(AmountParser.MAX_EXTERNAL) > 0)
            return;
        if (!hasHub(grid)) {
            player.sendSystemMessage(Component.translatable("message.twinstar.order_needs_hub"));
            return;
        }
        if (craftMissingAmount) {
            TwinStarService service = (TwinStarService) grid.getService(ITwinStarService.class);
            amount = amount.subtract(service.stockOf(what)).max(BigInteger.ZERO);
        }
        if (amount.signum() <= 0) {
            back.run();
            return;
        }
        PlanMenu.open(player, locator, what, amount, bestEffort, autoStart);
    }

    // ── which amount screen opens ──

    /** Players who asked for the original screen: the next AE2 amount screen they open is not replaced. */
    private static final java.util.Set<java.util.UUID> SKIP_ONCE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void skipOnce(ServerPlayer player) {
        SKIP_ONCE.add(player.getUUID());
    }

    /** True if AE2's amount screen should be replaced by ours: a working hub on the grid, and not skipped. */
    public static boolean replaces(ServerPlayer player, MenuHostLocator locator) {
        if (SKIP_ONCE.remove(player.getUUID()))
            return false;
        IActionHost host = locator.locate(player, IActionHost.class);
        return hasHub(gridOf(host));
    }
}
