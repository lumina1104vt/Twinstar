package com.lumina.twinstar.order;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSubmitResult;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.guisync.GuiSync;
import appeng.menu.locator.MenuHostLocator;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.algorithm.ae2.PlanWrapper;
import com.lumina.twinstar.algorithm.model.Amount;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.storage.TwinstarContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * The twin-star plan screen: plans the order with the twin-star engine (in place of AE2's confirm
 * screen), shows the crafting tree, and starts it on the hub. "Back" returns to the order screen
 * with the same amount.
 */
public class PlanMenu extends AEBaseMenu implements ISubMenu {
    private static final String START = "start";
    private static final String REPLAN = "replan";
    private static final String BACK = "back";
    private static final String MARK = "mark";

    /**
     * A mark set on the client: the row of the view it was shown on, the {@link ItemMark}, and for
     * {@link ItemMark#KEEP} how much to keep (decimal digits in the item's base units; empty = all).
     */
    public record MarkRequest(int row, int mark, String keep) {
        public MarkRequest(int row, int mark) {
            this(row, mark, "");
        }
    }

    /** Planning on the engine thread; the client shows "planning…". */
    @GuiSync(10)
    public boolean planning;
    /** Why the last start failed (a {@link appeng.api.networking.crafting.CraftingSubmitErrorCode} name, or "timeout" / "no_hub"); empty = none. */
    @GuiSync(11)
    public String error = "";

    private final ISubMenuHost host;
    // server
    private @Nullable AEKey what;
    private BigInteger amount = BigInteger.ONE;
    private CalculationStrategy strategy = CalculationStrategy.REPORT_MISSING_ITEMS;
    private boolean autoStart;
    private @Nullable Future<ICraftingPlan> job;
    private @Nullable ICraftingPlan result;
    private long startedNanos;
    /** the player's marks for this order (both sides: the client shows them) */
    private final Map<AEKey, ItemMark> marks = new HashMap<>();
    /** KEEP marks with an amount (an item without one is kept whole) */
    private final Map<AEKey, BigInteger> keep = new HashMap<>();
    /** the view last sent (server) or received (client): rows are named by their index in it */
    private @Nullable PlanView view;

    public PlanMenu(int id, Inventory playerInventory, ISubMenuHost host) {
        super(TwinstarContent.PLAN_MENU.get(), id, playerInventory, host);
        this.host = host;
        registerClientAction(START, this::start);
        registerClientAction(REPLAN, this::replan);
        registerClientAction(BACK, this::back);
        registerClientAction(MARK, MarkRequest.class, this::mark);
    }

    public static void open(ServerPlayer player, MenuHostLocator locator, AEKey what, BigInteger amount, boolean bestEffort,
                            boolean autoStart) {
        MenuOpener.open(TwinstarContent.PLAN_MENU.get(), player, locator);
        if (player.containerMenu instanceof PlanMenu menu) {
            menu.what = what;
            menu.amount = amount;
            menu.strategy = bestEffort ? CalculationStrategy.CRAFT_LESS : CalculationStrategy.REPORT_MISSING_ITEMS;
            menu.autoStart = autoStart;
            menu.plan();
            menu.broadcastChanges();
        }
    }

    @Override
    public ISubMenuHost getHost() {
        return host;
    }

    private @Nullable TwinStarService service() {
        IGrid grid = OrderFlow.gridOf(getActionHost());
        if (grid == null)
            return null;
        ITwinStarService s = grid.getService(ITwinStarService.class);
        return s instanceof TwinStarService ts && ts.hasHub() ? ts : null;
    }

    private void plan() {
        if (job != null)
            job.cancel(true);
        job = null;
        result = null;
        error = "";
        TwinStarService service = service();
        if (service == null || what == null) {
            planning = false;
            error = "no_hub";
            return;
        }
        startedNanos = System.nanoTime();
        job = service.beginCalculation(what, Amount.of(amount), strategy, Map.copyOf(marks), Map.copyOf(keep));
        planning = true;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && job != null && job.isDone())
            finishPlanning();
        super.broadcastChanges();
    }

    private void finishPlanning() {
        Future<ICraftingPlan> done = job;
        job = null;
        planning = false;
        try {
            result = done.get();
        } catch (ExecutionException e) {
            error = "timeout";
            // a timeout is expected now and then; anything else is a bug and must not vanish silently
            if (!(e.getCause() instanceof TwinStarService.PlanTimedOutException))
                Twinstar.LOGGER.error("[雙星] 規劃 {} ×{} 時引擎出錯", what, amount, e.getCause());
            return;
        } catch (Exception e) {
            return;     // cancelled
        }
        TwinStarService service = service();
        if (!(result instanceof PlanWrapper pw) || service == null || !(getPlayer() instanceof ServerPlayer player))
            return;
        PlanView v = PlanTree.build(pw, System.nanoTime() - startedNanos, service.freeCapacityExact(), service.getParallelism());
        view = v;
        PacketDistributor.sendToPlayer(player, new PlanViewPacket(containerId, v));
        if (autoStart && pw.plan().isComplete()) {
            autoStart = false;
            start();
        }
    }

    public void start() {
        if (!isServerSide()) {
            sendClientAction(START);
            return;
        }
        TwinStarService service = service();
        if (result == null || service == null)
            return;
        Player player = getPlayer();
        ICraftingSubmitResult r = service.submit(result, null, IActionSource.ofPlayer(player, getActionHost()));
        if (r.successful())
            host.returnToMainMenu(player, this);
        else
            error = r.errorCode() != null ? r.errorCode().name() : "unknown";
    }

    public void replan() {
        if (!isServerSide()) {
            view = null;
            sendClientAction(REPLAN);
            return;
        }
        plan();
    }

    /** Sets the mark of the item on {@code row} of the shown plan and plans again with it. */
    public void mark(MarkRequest request) {
        PlanView shown = view;
        if (shown == null || request.row() < 0 || request.row() >= shown.rows().size()
                || request.mark() < 0 || request.mark() >= ItemMark.values().length)
            return;
        AEKey key = shown.rows().get(request.row()).key();
        ItemMark mark = ItemMark.values()[request.mark()];
        if (mark == ItemMark.AUTO)
            marks.remove(key);
        else
            marks.put(key, mark);
        keep.remove(key);
        if (mark == ItemMark.KEEP && request.keep() != null && !request.keep().isEmpty()) {
            try {
                BigInteger n = new BigInteger(request.keep());
                if (n.signum() >= 0)
                    keep.put(key, n);
            } catch (NumberFormatException ignored) {
                // kept whole
            }
        }
        if (!isServerSide()) {
            view = null;
            sendClientAction(MARK, request);
            return;
        }
        plan();
    }

    /** How much of a KEEP item is kept; null = all of it. */
    public @Nullable BigInteger keepOf(AEKey key) {
        return keep.get(key);
    }

    /** The player's mark on an item of this order. */
    public ItemMark markOf(AEKey key) {
        return marks.getOrDefault(key, ItemMark.AUTO);
    }

    public void back() {
        if (!isServerSide()) {
            sendClientAction(BACK);
            return;
        }
        MenuHostLocator locator = getLocator();
        if (what != null && locator != null && getPlayer() instanceof ServerPlayer player)
            OrderMenu.open(player, locator, what, amount);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (job != null)
            job.cancel(true);
    }

    // ── client ──

    public void setView(PlanView view) {
        this.view = view;
    }

    public @Nullable PlanView getView() {
        return view;
    }
}
