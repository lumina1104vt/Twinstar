package com.lumina.twinstar.client;

import appeng.api.client.AEKeyRendering;
import com.lumina.twinstar.order.JobDonePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * "Crafting done" notices at the top right of the screen, like advancement toasts: the item's picture,
 * the amount and the time it took. Each slides in from the right, stays five seconds and slides out;
 * several stack downwards (Minecraft's own toast list).
 */
public final class JobDoneNotices {
    private JobDoneNotices() {
    }

    public static void show(JobDonePacket job) {
        Minecraft.getInstance().getToasts().addToast(new DoneToast(job));
    }

    private static final class DoneToast implements Toast {
        private static final ResourceLocation BACKGROUND = ResourceLocation.withDefaultNamespace("toast/advancement");
        private static final long SHOW_MS = 5000;
        private final JobDonePacket job;

        DoneToast(JobDonePacket job) {
            this.job = job;
        }

        @Override
        public Visibility render(GuiGraphics g, ToastComponent toasts, long timeSinceLastVisible) {
            Minecraft mc = toasts.getMinecraft();
            Font font = mc.font;
            g.blitSprite(BACKGROUND, 0, 0, width(), height());
            // the item's own picture, as on an advancement toast
            AEKeyRendering.drawInGui(mc, g, 8, 8, job.what());

            int tx = 30, room = width() - tx - 6;
            String time = time(job.seconds());
            g.drawString(font, Component.translatable("twinstar.job.done.title"), tx, 7, 0xFFFFFF00, false);
            g.drawString(font, time, width() - 6 - font.width(time), 7, 0xFFA0A8C0, false);
            String amount = " ×" + job.amount();
            String line = fit(font, AEKeyRendering.getDisplayName(job.what()).getString(), room - font.width(amount)) + amount;
            g.drawString(font, fit(font, line, room), tx, 18, 0xFFFFFFFF, false);

            return timeSinceLastVisible >= SHOW_MS * toasts.getNotificationDisplayTimeMultiplier()
                    ? Visibility.HIDE : Visibility.SHOW;
        }
    }

    private static String time(long seconds) {
        return seconds >= 3600 ? String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private static String fit(Font font, String s, int width) {
        if (width <= 0)
            return "";
        if (font.width(s) <= width)
            return s;
        return font.plainSubstrByWidth(s, width - font.width("…")) + "…";
    }
}
