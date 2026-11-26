package com.lumina.twinstar.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * How the player wants a graph's lines to go, chosen with a button on the graph's bar and kept in
 * {@code config/twinstar-graph.txt}, so it holds for every graph and after a restart.
 */
final class GraphStyle {
    private static GraphLayout.Style style;

    private GraphStyle() {
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("twinstar-graph.txt");
    }

    static GraphLayout.Style get() {
        if (style == null) {
            style = GraphLayout.Style.LABELS;
            try {
                style = GraphLayout.Style.valueOf(Files.readString(file()).trim().toUpperCase(Locale.ROOT));
            } catch (IOException | IllegalArgumentException ignored) {
                // nothing chosen yet, or not readable: the default
            }
        }
        return style;
    }

    static void next() {
        GraphLayout.Style[] all = GraphLayout.Style.values();
        style = all[(get().ordinal() + 1) % all.length];
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), style.name().toLowerCase(Locale.ROOT));
        } catch (IOException ignored) {
            // the choice is just not remembered
        }
    }

    static Component name(GraphLayout.Style style) {
        return Component.translatable("gui.twinstar.graph.style." + style.name().toLowerCase(Locale.ROOT));
    }
}
