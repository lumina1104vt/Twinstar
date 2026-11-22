package com.lumina.twinstar.client;

import appeng.client.gui.me.crafting.CraftingCPUScreen;
import com.lumina.twinstar.Twinstar;
import com.lumina.twinstar.client.ui.PerfContent;
import com.lumina.twinstar.client.ui.PerfPanel;
import com.lumina.twinstar.cpu.CpuReportPacket;
import com.lumina.twinstar.metrics.HubReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.List;

/**
 * The memory and speed panel beside AE2's CPU screens (the CPU block's and the terminal's crafting status) while
 * they show one of the hub's entries: the server sends the numbers about once a second
 * ({@link CpuReportPacket}); nothing is drawn for other CPUs.
 */
@EventBusSubscriber(modid = Twinstar.MODID, value = Dist.CLIENT)
public final class CpuPerfOverlay {
    private static final PerfPanel PANEL = new PerfPanel(true);
    private static final int TOP = 44;      // below the "pull from network" button

    private CpuPerfOverlay() {
    }

    private static int panelX(AbstractContainerScreen<?> s) {
        int right = s.getGuiLeft() + s.getXSize() + 4;
        if (right + PerfPanel.WIDTH <= s.width)
            return right;
        return Math.max(2, s.getGuiLeft() - PerfPanel.WIDTH - 4);
    }

    private static PerfContent.Content content(HubReport r) {
        return PerfContent.hub(r, r.jobBytes() < 0);
    }

    @SubscribeEvent
    static void render(ScreenEvent.Render.Post e) {
        if (!(e.getScreen() instanceof CraftingCPUScreen<?> screen))
            return;
        HubReport r = CpuReportPacket.Latest.of(screen.getMenu().containerId);
        if (r == null)
            return;
        var font = Minecraft.getInstance().font;
        PANEL.draw(e.getGuiGraphics(), font, panelX(screen), screen.getGuiTop() + TOP, content(r), e.getMouseX(), e.getMouseY());
        List<Component> tip = PANEL.tooltipAt(e.getMouseX(), e.getMouseY());
        if (tip != null)
            e.getGuiGraphics().renderComponentTooltip(font, tip, e.getMouseX(), e.getMouseY());
    }

    @SubscribeEvent
    static void click(ScreenEvent.MouseButtonPressed.Pre e) {
        if (e.getScreen() instanceof CraftingCPUScreen<?> screen && CpuReportPacket.Latest.of(screen.getMenu().containerId) != null
                && e.getButton() == 0 && PANEL.clicked(e.getMouseX(), e.getMouseY()))
            e.setCanceled(true);
    }
}
