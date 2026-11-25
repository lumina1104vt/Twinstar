package com.lumina.twinstar.client;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import com.lumina.twinstar.client.hover.KeyHit;
import com.lumina.twinstar.client.hover.KeyHits;
import com.lumina.twinstar.client.hover.KeyHover;
import com.lumina.twinstar.client.ui.PixelIcon;
import com.lumina.twinstar.usage.UsagePackets;
import com.lumina.twinstar.usage.UsageSnapshot;
import com.lumina.twinstar.usage.UsageStore;
import com.lumina.corelib.util.BigAmountFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * What the machines of the network used up lately (items and fluids): a list of machines (or of interfaces with their machines)
 * on the left, the machine picked on the right with every material in the three windows. The server answers for the grid of the
 * AE2 menu that is open underneath; the screen asks every two seconds while it is open and never otherwise.
 */
public class UsageScreen extends Screen implements KeyHover {
    private static final int ASK_EVERY_TICKS = 40;
    private static final int BG = 0xFF07070C, PANEL = 0xF0100F1A, PANEL_LINE = 0xFF3A3458, ROW = 0xFF14121F, ROW_HOT = 0xFF1E1B30,
            ROW_SELECTED = 0xFF2A2446, HEADER = 0xFF1B1830, BUTTON = 0xFF1E1C2C, BUTTON_LINE = 0xFF4E4474, BUTTON_HOT = 0xFFA082F0,
            BUTTON_ON = 0xFF3A3070, TEXT = 0xFFDEDEEA, DIM = 0xFF9894B4, ACCENT = 0xFFB4A6FF, STOCK = 0xFFFFB45A, HUB = 0xFF78B4FF,
            PROVIDER = 0xFF5ADCC8, EXPORT = 0xFFE6E678, INTERFACE = 0xFFD08CFF, SENDER = 0xFFB0B0B8;

    /** 8x8 bars: the button that opens this screen */
    private static final String[] ICON = {
            "......#.",
            "......#.",
            "..#...#.",
            "..#.#.#.",
            "#.#.#.#.",
            "#.#.#.#.",
            "########",
            "........"};
    private static final String[] ARROW_UP = {"..#..", ".###.", "#####"};
    private static final String[] ARROW_DOWN = {"#####", ".###.", "..#.."};

    // what the player chose last stays for the next time the screen opens
    private static int window = 1;
    private static boolean byInterface;
    private static UsageOrder.Sort sort = UsageOrder.Sort.AMOUNT;
    private static boolean ascending;

    private final Supplier<Screen> back;
    private final EditBox search;
    private final KeyHits hits = new KeyHits();
    private final List<Click> clicks = new ArrayList<>();
    private int ticks, seenVersion = -1;
    private @Nullable UsageSnapshot snapshot;
    private @Nullable UsageOrder<UsageSnapshot.Machine, UsageSnapshot.Row> model;
    private boolean dirty = true;
    private @Nullable String selected;
    private int listScroll, detailScroll, listHeight, detailHeight;
    private int listX, listY, listW, listH, detailX, detailY, detailW, detailH;

    private record Click(int x, int y, int w, int h, Runnable action) {
        boolean has(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    public UsageScreen(Supplier<Screen> back) {
        super(Component.translatable("gui.twinstar.usage.title"));
        this.back = back;
        UsagePackets.UsageCache.clear();
        UsagePackets.UsageCache.ask();
        this.search = new EditBox(Minecraft.getInstance().font, 0, 0, 120, 12, Component.translatable("gui.twinstar.usage.search"));
        search.setBordered(false);
        search.setMaxLength(48);
        search.setResponder(t -> {
            dirty = true;
            listScroll = 0;
        });
    }

    /** Opens the screen from the screen now open, coming back to {@code back}. */
    public static void open(Supplier<Screen> back) {
        Minecraft.getInstance().setScreen(new UsageScreen(back));
    }

    /** A 16x16 button for other screens' toolbars that opens the screen. */
    public static Button button(int x, int y, Runnable onPress) {
        Button b = new Button(x, y, 16, 16, Component.translatable("gui.twinstar.usage.open"), btn -> onPress.run(),
                supplier -> supplier.get()) {
            @Override
            protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
                // AE2's toolbar background lies at z 1: above it, like AE2's own icons
                g.pose().pushPose();
                g.pose().translate(0, 0, 10);
                g.fill(getX(), getY(), getX() + width, getY() + height, isHoveredOrFocused() ? 0xFFA082F0 : 0xFF4E4474);
                g.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, isHoveredOrFocused() ? 0xFF2C2642 : 0xFF1E1C2C);
                PixelIcon.draw(g, ICON, getX() + 4, getY() + 4, 0xFFDEDEEA);
                g.pose().popPose();
            }
        };
        b.setTooltip(Tooltip.create(Component.translatable("gui.twinstar.usage.open.tooltip")));
        return b;
    }

    @Override
    public @Nullable KeyHit keyAt(double mouseX, double mouseY) {
        return hits.at(mouseX, mouseY);
    }

    @Override
    public void tick() {
        if (ticks++ % ASK_EVERY_TICKS == 0)
            UsagePackets.UsageCache.ask();
        int v = UsagePackets.UsageCache.version();
        if (v != seenVersion) {
            seenVersion = v;
            snapshot = UsagePackets.UsageCache.latest();
            dirty = true;
        }
    }

    private void rebuild() {
        dirty = false;
        UsageSnapshot s = snapshot;
        model = s == null || !s.allowed() ? null : UsageModel.build(s, window, byInterface, sort, ascending, search.getValue());
        if (selected != null && (model == null || model.find(selected) == null))
            selected = null;
    }

    // ───────────────────────────── drawing ─────────────────────────────

    @Override
    public void renderBackground(GuiGraphics g) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        renderBackground(g);
        if (dirty)
            rebuild();
        hits.clear();
        clicks.clear();
        List<Component> tooltip = null;
        var font = this.font;

        // title and search
        g.drawString(font, title, 8, 7, TEXT, false);
        int sx = width - 8 - 130;
        search.setX(sx + 4);
        search.setY(7);
        search.setWidth(122);
        g.fill(sx, 4, sx + 130, 19, search.isFocused() ? BUTTON_HOT : PANEL_LINE);
        g.fill(sx + 1, 5, sx + 129, 18, 0xFF040408);
        search.render(g, mx, my, partialTick);
        if (search.getValue().isEmpty() && !search.isFocused())
            g.drawString(font, Component.translatable("gui.twinstar.usage.search"), sx + 4, 8, 0xFF5C5878, false);

        // controls: window, grouping, sorting (a row of their own; they wrap when the window is narrow)
        ctlX = 8;
        ctlY = 24;
        for (int w = 0; w < UsageSnapshot.WINDOWS; w++) {
            final int win = w;
            button(g, Component.translatable("gui.twinstar.usage.window." + w), window == w, mx, my, () -> {
                window = win;
                dirty = true;
            }, 0);
        }
        ctlX += 8;
        for (int k = 0; k < 2; k++) {
            final boolean iface = k == 1;
            button(g, Component.translatable(iface ? "gui.twinstar.usage.group.interface" : "gui.twinstar.usage.group.machine"),
                    byInterface == iface, mx, my, () -> {
                        byInterface = iface;
                        dirty = true;
                        listScroll = 0;
                    }, 0);
        }
        ctlX += 8;
        for (UsageOrder.Sort s : UsageOrder.Sort.values()) {
            boolean on = sort == s;
            Component label = Component.translatable("gui.twinstar.usage.sort." + s.name().toLowerCase(java.util.Locale.ROOT));
            int bx = ctlX, by = ctlY;
            int bw = button(g, label, on, mx, my, () -> {
                if (sort == s)
                    ascending = !ascending;
                else {
                    sort = s;
                    ascending = s == UsageOrder.Sort.NAME;
                }
                dirty = true;
            }, on ? 8 : 0);
            // the button may have wrapped to the next row: where it really is
            if (bx + bw > width - 8) {
                bx = 8;
                by += 16;
            }
            if (on)
                PixelIcon.draw(g, ascending ? ARROW_UP : ARROW_DOWN, bx + bw - 11, by + 6, TEXT);
            if (mx >= bx && mx < bx + bw && my >= by && my < by + 14)
                tooltip = List.of(Component.translatable("gui.twinstar.usage.sort.tooltip.amount"),
                        Component.translatable("gui.twinstar.usage.sort.tooltip.rate").withStyle(st -> st.withColor(DIM)),
                        Component.translatable("gui.twinstar.usage.sort.tooltip.name").withStyle(st -> st.withColor(DIM)));
        }

        // body
        int top = ctlY + 18, bottom = height - 16;
        detailW = Math.max(200, Math.min(300, width * 2 / 5));
        listX = 6;
        listY = top;
        listW = width - detailW - 18;
        listH = bottom - top;
        detailX = listX + listW + 6;
        detailY = top;
        detailH = listH;
        g.fill(listX - 1, listY - 1, listX + listW + 1, listY + listH + 1, PANEL_LINE);
        g.fill(listX, listY, listX + listW, listY + listH, PANEL);
        g.fill(detailX - 1, detailY - 1, detailX + detailW + 1, detailY + detailH + 1, PANEL_LINE);
        g.fill(detailX, detailY, detailX + detailW, detailY + detailH, PANEL);

        UsageSnapshot s = snapshot;
        if (s == null) {
            note(g, "gui.twinstar.usage.loading");
        }
        else if (!s.allowed()) {
            note(g, "gui.twinstar.usage.denied");
        }
        else if (model == null || model.lines().isEmpty()) {
            note(g, s.machines().isEmpty() ? "gui.twinstar.usage.empty" : "gui.twinstar.usage.nomatch");
        }
        else {
            List<Component> t1 = list(g, mx, my);
            if (t1 != null)
                tooltip = t1;
        }
        List<Component> t2 = detail(g, mx, my);
        if (t2 != null && tooltip == null)
            tooltip = t2;

        // footer
        if (s != null && s.allowed()) {
            String foot = Component.translatable("gui.twinstar.usage.footer", Math.max(1, s.memory() / 1024)).getString();
            if (s.dropped() > 0)
                foot += "  " + Component.translatable("gui.twinstar.usage.dropped", s.dropped()).getString();
            g.drawString(font, font.plainSubstrByWidth(foot, width - 16), 8, height - 12, DIM, false);
        }
        if (tooltip != null)
            g.renderComponentTooltip(font, tooltip, mx, my);
    }

    private void note(GuiGraphics g, String key) {
        var lines = font.split(Component.translatable(key), listW - 16);
        for (int i = 0; i < lines.size(); i++)
            g.drawString(font, lines.get(i), listX + 8, listY + 8 + i * 10, DIM, false);
    }

    private int ctlX, ctlY;

    /** A button of the text width plus padding at the next free place of the control rows; returns its width. */
    private int button(GuiGraphics g, Component text, boolean on, int mx, int my, Runnable action, int extra) {
        int w = font.width(text) + 12 + extra;
        if (ctlX + w > width - 8 && ctlX > 8) {
            ctlX = 8;
            ctlY += 16;
        }
        int x = ctlX, y = ctlY;
        boolean hot = mx >= x && mx < x + w && my >= y && my < y + 14;
        g.fill(x, y, x + w, y + 14, hot ? BUTTON_HOT : BUTTON_LINE);
        g.fill(x + 1, y + 1, x + w - 1, y + 13, on ? BUTTON_ON : BUTTON);
        g.drawString(font, text, x + 6, y + 3, on ? TEXT : DIM, false);
        clicks.add(new Click(x, y, w, 14, action));
        ctlX += w + 2;
        return w;
    }

    private static final int HEADER_H = 14, MACHINE_H = 30;

    /** The machine list; returns a tooltip when the mouse is on something that has one. */
    private @Nullable List<Component> list(GuiGraphics g, int mx, int my) {
        var m = model;
        UsageSnapshot s = snapshot;
        if (m == null || s == null)
            return null;
        int total = 0;
        for (var line : m.lines())
            total += line.machine() == null ? HEADER_H : MACHINE_H;
        listHeight = total;
        listScroll = Math.max(0, Math.min(listScroll, total - listH));
        g.enableScissor(listX, listY, listX + listW, listY + listH);
        List<Component> tip = null;
        int y = listY - listScroll;
        for (var line : m.lines()) {
            var e = line.machine();
            if (e == null) {
                if (y + HEADER_H > listY && y < listY + listH) {
                    g.fill(listX, y, listX + listW, y + HEADER_H, HEADER);
                    Component head = line.first().side() == UsageStore.TRANSFER_SIDE
                            ? Component.translatable("gui.twinstar.usage.senders_head", line.count())
                            : Component.translatable("gui.twinstar.usage.iface_head", UsageModel.ifaceTitle(line.first()),
                            pos(line.first().ifacePos()), line.count());
                    g.drawString(font, font.plainSubstrByWidth(head.getString(), listW - 10), listX + 5, y + 3, ACCENT, false);
                }
                y += HEADER_H;
                continue;
            }
            if (y + MACHINE_H > listY && y < listY + listH) {
                int indent = line.indent() ? 8 : 0;
                int rx = listX + indent, rw = listW - indent;
                boolean over = mx >= rx && mx < rx + rw && my >= y && my < y + MACHINE_H - 1 && my >= listY && my < listY + listH;
                boolean sel = e.machine().id().equals(selected);
                g.fill(rx, y, rx + rw, y + MACHINE_H - 1, sel ? ROW_SELECTED : over ? ROW_HOT : ROW);
                if (sel)
                    g.fill(rx, y, rx + 2, y + MACHINE_H - 1, ACCENT);
                String pos = pos(e.machine().pos());
                int posW = font.width(pos);
                g.drawString(font, font.plainSubstrByWidth(e.machine().name().getString(), rw - posW - 14), rx + 6, y + 3, TEXT, false);
                g.drawString(font, pos, rx + rw - posW - 5, y + 3, DIM, false);
                int clickTop = Math.max(y, listY), clickBottom = Math.min(y + MACHINE_H - 1, listY + listH);
                clicks.add(new Click(rx, clickTop, rw, Math.max(0, clickBottom - clickTop), () -> selected = e.machine().id()));
                // the biggest users, each an icon and its rate
                int ix = rx + 6;
                boolean any = false;
                for (UsageSnapshot.Row r : e.rows()) {
                    if (r.totals()[window] <= 0)
                        continue;
                    String rate = UsageModel.rateText(r.key(), r.totals()[window], s.spans()[window]);
                    int cw = 18 + font.width(rate) + 6;
                    if (ix + cw > rx + rw - 4)
                        break;
                    AEKeyRendering.drawInGui(Minecraft.getInstance(), g, ix, y + 11, r.key());
                    hits.add(r.key(), ix, y + 11, 16, 16);
                    g.drawString(font, rate, ix + 18, y + 16, DIM, false);
                    if (mx >= ix && mx < ix + cw - 4 && my >= y + 11 && my < y + 27 && my >= listY && my < listY + listH)
                        tip = itemTip(r, s);
                    ix += cw;
                    any = true;
                }
                if (!any)
                    g.drawString(font, Component.translatable(e.machine().side() == UsageStore.TRANSFER_SIDE ? "gui.twinstar.usage.idle_sent" : "gui.twinstar.usage.idle"), rx + 6, y + 16, 0xFF5C5878, false);
                if (over && tip == null)
                    tip = machineTip(e.machine());
            }
            y += MACHINE_H;
        }
        g.disableScissor();
        scrollBar(g, listX + listW - 3, listY, listH, listHeight, listScroll);
        return tip;
    }

    /** The picked machine on the right. */
    private @Nullable List<Component> detail(GuiGraphics g, int mx, int my) {
        var m = model;
        UsageSnapshot s = snapshot;
        if (m == null || s == null)
            return null;
        var e = selected == null ? null : m.find(selected);
        if (e == null) {
            var lines = font.split(Component.translatable("gui.twinstar.usage.pick"), detailW - 12);
            for (int i = 0; i < lines.size(); i++)
                g.drawString(font, lines.get(i), detailX + 6, detailY + 6 + i * 10, DIM, false);
            return null;
        }
        UsageSnapshot.Machine mc = e.machine();
        List<Component> tip = null;
        g.enableScissor(detailX, detailY, detailX + detailW, detailY + detailH);
        int x = detailX + 6, y = detailY + 6 - detailScroll, w = detailW - 12;
        int y0 = y;
        g.drawString(font, font.plainSubstrByWidth(mc.name().getString(), w), x, y, TEXT, false);
        y += 11;
        g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.twinstar.usage.at", pos(mc.pos()), shortDim(mc.dim())).getString(), w),
                x, y, DIM, false);
        y += 10;
        if (mc.side() != UsageStore.TRANSFER_SIDE) {
            Component iface = mc.ifaceName() != null ? mc.ifaceName() : Component.translatable("gui.twinstar.usage.interface_default");
            g.drawString(font, font.plainSubstrByWidth(Component.translatable("gui.twinstar.usage.via", iface,
                    pos(mc.ifacePos())).getString(), w), x, y, DIM, false);
            y += 10;
        }
        if ((mc.kinds() & UsageStore.HUB) != 0) {
            g.fill(x, y + 1, x + 6, y + 7, HUB);
            g.drawString(font, Component.translatable("gui.twinstar.usage.kind.hub"), x + 10, y, DIM, false);
            y += 10;
        }
        if ((mc.kinds() & UsageStore.STOCK) != 0) {
            g.fill(x, y + 1, x + 6, y + 7, STOCK);
            g.drawString(font, Component.translatable("gui.twinstar.usage.kind.stock"), x + 10, y, DIM, false);
            y += 10;
        }
        int[] sentKinds = {com.lumina.twinstar.monitor.Transfers.PROVIDER, com.lumina.twinstar.monitor.Transfers.EXPORT_BUS,
                com.lumina.twinstar.monitor.Transfers.INTERFACE, com.lumina.twinstar.monitor.Transfers.OTHER};
        int[] sentColors = {PROVIDER, EXPORT, INTERFACE, SENDER};
        String[] sentKeys = {"provider", "export", "interface", "other"};
        for (int i = 0; i < sentKinds.length; i++)
            if ((mc.kinds() & sentKinds[i]) != 0) {
                g.fill(x, y + 1, x + 6, y + 7, sentColors[i]);
                g.drawString(font, Component.translatable("gui.twinstar.usage.kind." + sentKeys[i]), x + 10, y, DIM, false);
                y += 10;
            }
        if (!mc.loaded()) {
            g.drawString(font, Component.translatable("gui.twinstar.usage.unloaded").withStyle(st -> st.withColor(STOCK)), x, y, STOCK, false);
            y += 10;
        }
        y += 4;
        // table head: the three windows and the rate of the chosen one
        int colW = Math.max(30, (w - 90) / 3);
        int nameW = w - colW * 3 - 2;
        int cx = x + nameW;
        for (int k = 0; k < UsageSnapshot.WINDOWS; k++) {
            Component label = Component.translatable("gui.twinstar.usage.window." + k);
            g.drawString(font, label, cx + colW * k + colW - font.width(label), y, k == window ? TEXT : DIM, false);
        }
        y += 11;
        g.fill(x, y - 1, x + w, y, PANEL_LINE);
        y += 2;
        boolean anyRow = false;
        for (UsageSnapshot.Row r : e.allRows()) {
            anyRow = true;
            boolean over = mx >= x && mx < x + w && my >= y && my < y + 18 && my >= detailY && my < detailY + detailH;
            if (over)
                g.fill(x - 2, y, x + w + 2, y + 18, ROW_HOT);
            AEKeyRendering.drawInGui(Minecraft.getInstance(), g, x, y + 1, r.key());
            hits.add(r.key(), x - 2, y, w + 4, 18);
            g.drawString(font, font.plainSubstrByWidth(AEKeyRendering.getDisplayName(r.key()).getString(), nameW - 22), x + 19, y + 5, TEXT, false);
            for (int k = 0; k < UsageSnapshot.WINDOWS; k++) {
                String t = r.totals()[k] <= 0 ? "-" : r.key().formatAmount(r.totals()[k], AmountFormat.SLOT);
                g.drawString(font, t, cx + colW * k + colW - font.width(t), y + 5, k == window ? TEXT : DIM, false);
            }
            if (over)
                tip = itemTip(r, s);
            y += 18;
        }
        long[] other = mc.other();
        if (other[UsageSnapshot.WINDOWS - 1] > 0) {
            g.drawString(font, Component.translatable("gui.twinstar.usage.other"), x + 2, y + 5, DIM, false);
            for (int k = 0; k < UsageSnapshot.WINDOWS; k++) {
                String t = other[k] <= 0 ? "-" : BigAmountFormat.unitForm(BigInteger.valueOf(other[k]));
                g.drawString(font, t, cx + colW * k + colW - font.width(t), y + 5, DIM, false);
            }
            if (mx >= x && mx < x + w && my >= y && my < y + 18 && my >= detailY && my < detailY + detailH)
                tip = List.of(Component.translatable("gui.twinstar.usage.other.tooltip"));
            y += 18;
            anyRow = true;
        }
        if (!anyRow) {
            g.drawString(font, Component.translatable("gui.twinstar.usage.none_used"), x, y + 2, DIM, false);
            y += 12;
        }
        detailHeight = y - y0 + 12;
        g.disableScissor();
        detailScroll = Math.max(0, Math.min(detailScroll, detailHeight - detailH));
        scrollBar(g, detailX + detailW - 3, detailY, detailH, detailHeight, detailScroll);
        return tip;
    }

    private void scrollBar(GuiGraphics g, int x, int y, int h, int content, int scroll) {
        if (content <= h)
            return;
        int bar = Math.max(12, h * h / content);
        int by = y + (int) ((long) (h - bar) * scroll / Math.max(1, content - h));
        g.fill(x, y, x + 2, y + h, 0x40FFFFFF);
        g.fill(x, by, x + 2, by + bar, 0xFFA082F0);
    }

    // ───────────────────────────── tooltips and texts ─────────────────────────────

    private static String pos(long packed) {
        return UsageModel.pos(packed);
    }

    private static String shortDim(String dim) {
        int i = dim.indexOf(':');
        return i >= 0 && dim.startsWith("minecraft:") ? dim.substring(i + 1) : dim;
    }

    private List<Component> itemTip(UsageSnapshot.Row r, UsageSnapshot s) {
        List<Component> lines = new ArrayList<>();
        lines.add(AEKeyRendering.getDisplayName(r.key()));
        for (int k = 0; k < UsageSnapshot.WINDOWS; k++) {
            long t = r.totals()[k];
            int color = k == window ? TEXT : DIM;
            lines.add(Component.translatable("gui.twinstar.usage.tip.line", Component.translatable("gui.twinstar.usage.window." + k),
                    r.key().formatAmount(t, AmountFormat.FULL), UsageModel.rateText(r.key(), t, s.spans()[k])).withStyle(st -> st.withColor(color)));
        }
        return lines;
    }

    private List<Component> machineTip(UsageSnapshot.Machine m) {
        List<Component> lines = new ArrayList<>();
        lines.add(m.name());
        lines.add(Component.translatable("gui.twinstar.usage.at", pos(m.pos()), shortDim(m.dim())).withStyle(st -> st.withColor(DIM)));
        lines.add(Component.translatable("gui.twinstar.usage.via",
                m.ifaceName() != null ? m.ifaceName() : Component.translatable("gui.twinstar.usage.interface_default"), pos(m.ifacePos())).withStyle(st -> st.withColor(DIM)));
        lines.add(Component.translatable("gui.twinstar.usage.click").withStyle(st -> st.withColor(DIM)));
        return lines;
    }

    // ───────────────────────────── input ─────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean inSearch = search.isMouseOver(mx, my) || (mx >= search.getX() - 4 && mx < search.getX() + search.getWidth() + 4 && my >= 4 && my < 19);
        search.setFocused(inSearch);
        if (inSearch)
            return search.mouseClicked(mx, my, button);
        if (button == 0)
            for (int i = clicks.size() - 1; i >= 0; i--)
                if (clicks.get(i).has(mx, my)) {
                    clicks.get(i).action().run();
                    return true;
                }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int step = (int) Math.round(-delta * 16);
        if (mx >= detailX && mx < detailX + detailW && my >= detailY && my < detailY + detailH)
            detailScroll = Math.max(0, Math.min(detailScroll + step, Math.max(0, detailHeight - detailH)));
        else
            listScroll = Math.max(0, Math.min(listScroll + step, Math.max(0, listHeight - listH)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (search.isFocused() && keyCode != 256) {     // Esc still leaves
            search.keyPressed(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!search.isFocused()) {
            search.setFocused(true);    // typing anywhere searches
            search.setValue("");
        }
        return search.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (minecraft != null)
            minecraft.setScreen(back.get());
    }
}
