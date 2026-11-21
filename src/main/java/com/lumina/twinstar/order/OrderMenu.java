package com.lumina.twinstar.order;

import appeng.api.networking.IGrid;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.ISubMenuHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.ISubMenu;
import appeng.menu.MenuOpener;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.locator.MenuLocator;
import appeng.menu.me.crafting.CraftAmountMenu;
import appeng.menu.slot.AppEngSlot;
import appeng.menu.slot.InaccessibleSlot;
import appeng.util.inv.AppEngInternalInventory;
import com.lumina.twinstar.api.ITwinStarService;
import com.lumina.twinstar.service.TwinStarService;
import com.lumina.twinstar.storage.TwinstarContent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.Objects;

/**
 * The twin-star order menu: replaces AE2's amount screen on grids with a working hub (the amount
 * field there is also extended by other mods, each with its own limits). Shows what the network has
 * of the item; any amount up to the order limit, with the order options as switches; "next" opens
 * the twin-star plan screen. "Use the original screen" sends the player to AE2's amount screen (as
 * other mods extend it) with the same item, where the other planners take over.
 */
public class OrderMenu extends AEBaseMenu implements ISubMenu {
    private static final String CONFIRM = "confirm";
    private static final String ORIGINAL = "original";
    /** item info is refreshed this often (the infinite-source probe asks the storage) */
    private static final int INFO_EVERY_TICKS = 10;

    /** What "next" sends: the exact amount as decimal text and the order options. */
    public record Request(String amount, boolean craftMissing, boolean bestEffort, boolean autoStart) {
    }

    /** Exact network stock of the item (decimal text). */
    @GuiSync(10)
    public String stock = "0";
    @GuiSync(11)
    public boolean infinite;
    /** Patterns on the network that make the item. */
    @GuiSync(12)
    public int routes;
    /** The engine has every pattern and stock change (plans are current). */
    @GuiSync(13)
    public boolean upToDate = true;
    /** The amount the field starts with (decimal text, any size: back from the plan screen keeps it). */
    @GuiSync(14)
    public String initialAmount = "1";

    private final ISubMenuHost host;
    private final AppEngSlot craftingItem;
    private @Nullable AEKey whatToCraft;
    private int ticks;

    public OrderMenu(int id, Inventory playerInventory, ISubMenuHost host) {
        super(TwinstarContent.ORDER_MENU.get(), id, playerInventory, host);
        this.host = host;
        this.craftingItem = new InaccessibleSlot(new AppEngInternalInventory(1), 0);
        this.craftingItem.setHideAmount(true);
        addSlot(craftingItem, SlotSemantics.MACHINE_OUTPUT);
        registerClientAction(CONFIRM, Request.class, this::confirm);
        registerClientAction(ORIGINAL, String.class, this::useOriginal);
    }

    public static void open(ServerPlayer player, MenuLocator locator, AEKey whatToCraft, int initialAmount) {
        open(player, locator, whatToCraft, BigInteger.valueOf(Math.max(1, initialAmount)));
    }

    public static void open(ServerPlayer player, MenuLocator locator, AEKey whatToCraft, BigInteger initialAmount) {
        MenuOpener.open(TwinstarContent.ORDER_MENU.get(), player, locator);
        if (player.containerMenu instanceof OrderMenu menu) {
            menu.whatToCraft = Objects.requireNonNull(whatToCraft, "whatToCraft");
            menu.craftingItem.set(GenericStack.wrapInItemStack(whatToCraft, 1));
            menu.initialAmount = initialAmount.toString();
            menu.refreshInfo();
            menu.broadcastChanges();
        }
    }

    @Override
    public ISubMenuHost getHost() {
        return host;
    }

    /** Client: the slot only carries the item to the screen, which draws the item itself. */
    public void hideItemSlot() {
        craftingItem.setActive(false);
    }

    /** The item on the client, from the synced slot. */
    public @Nullable GenericStack getWhatToCraft() {
        return GenericStack.unwrapItemStack(craftingItem.getItem());
    }

    private @Nullable TwinStarService service() {
        IGrid grid = OrderFlow.gridOf(getActionHost());
        if (grid == null)
            return null;
        ITwinStarService s = grid.getService(ITwinStarService.class);
        return s instanceof TwinStarService ts ? ts : null;
    }

    private void refreshInfo() {
        TwinStarService service = service();
        if (service == null || whatToCraft == null)
            return;
        TwinStarService.OrderInfo info = service.orderInfo(whatToCraft);
        stock = info.stock().toString();
        infinite = info.infinite();
        routes = info.routes();
        upToDate = info.upToDate();
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide() && ++ticks % INFO_EVERY_TICKS == 0)
            refreshInfo();
        super.broadcastChanges();
    }

    public void confirm(Request request) {
        if (!isServerSide()) {
            sendClientAction(CONFIRM, request);
            return;
        }
        BigInteger amount;
        try {
            amount = new BigInteger(request.amount());
        } catch (NumberFormatException | NullPointerException e) {
            return;
        }
        if (whatToCraft == null || amount.signum() <= 0 || !(getPlayer() instanceof ServerPlayer player))
            return;
        OrderFlow.startTwinStar(player, OrderFlow.gridOf(getActionHost()), getLocator(),
                () -> host.returnToMainMenu(player, this), whatToCraft, amount,
                request.craftMissing(), request.bestEffort(), request.autoStart());
    }

    /** To AE2's amount screen (as other mods extend it), keeping the item; {@code amount} is the typed text's value if it fits an int. */
    public void useOriginal(String amount) {
        if (!isServerSide()) {
            sendClientAction(ORIGINAL, amount);
            return;
        }
        MenuLocator locator = getLocator();
        if (whatToCraft == null || locator == null || !(getPlayer() instanceof ServerPlayer player))
            return;
        int initial = 1;
        try {
            BigInteger v = new BigInteger(amount);
            if (v.signum() > 0)
                initial = v.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        } catch (NumberFormatException | NullPointerException ignored) {
            // keep 1
        }
        OrderFlow.skipOnce(player);
        CraftAmountMenu.open(player, locator, whatToCraft, initial);
    }
}
