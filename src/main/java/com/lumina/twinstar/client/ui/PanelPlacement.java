package com.lumina.twinstar.client.ui;

import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Where the player moved each small panel to: an offset from the panel's own default place, kept in a
 * small file in the config folder so it is the same the next time the screen opens. The logic needs no
 * game ({@code PanelPlacementTest}).
 */
public final class PanelPlacement {
    private final @Nullable Path file;
    private final Map<String, int[]> offsets = new HashMap<>();
    private boolean loaded;

    public PanelPlacement(@Nullable Path file) {
        this.file = file;
    }


    /** The one the screens use: {@code config/twinstar-panels.properties} of the game folder. */
    public static PanelPlacement shared() {
        return Shared.INSTANCE;
    }

    private static final class Shared {
        static final PanelPlacement INSTANCE = new PanelPlacement(
                Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("twinstar-panels.properties"));
    }

    /** The offset (dx, dy) of a panel; (0, 0) when it was never moved. */
    public int[] get(String id) {
        load();
        int[] o = offsets.get(id);
        return o == null ? new int[]{0, 0} : o.clone();
    }

    /** Remembers an offset; (0, 0) forgets it. Written to the file at once. */
    public void set(String id, int dx, int dy) {
        load();
        if (dx == 0 && dy == 0)
            offsets.remove(id);
        else
            offsets.put(id, new int[]{dx, dy});
        save();
    }

    /**
     * The offset that keeps a panel of {@code size} inside {@code area} when its default place is
     * {@code def}: the panel's position {@code def + offset} is held between 0 and {@code area - size}.
     */
    public static int clamp(int offset, int def, int size, int area) {
        int pos = def + offset;
        int max = Math.max(0, area - size);
        return Math.max(0, Math.min(max, pos)) - def;
    }

    private void load() {
        if (loaded)
            return;
        loaded = true;
        if (file == null || !Files.isRegularFile(file))
            return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return;
        }
        for (String id : p.stringPropertyNames()) {
            String[] parts = p.getProperty(id).split(",");
            try {
                if (parts.length == 2)
                    offsets.put(id, new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
            } catch (NumberFormatException ignored) {
                // a bad line is skipped
            }
        }
    }

    private void save() {
        if (file == null)
            return;
        Properties p = new Properties();
        offsets.forEach((id, o) -> p.setProperty(id, o[0] + "," + o[1]));
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                p.store(w, "Twin-star panels moved by the player: id = x offset, y offset");
            }
        } catch (IOException ignored) {
            // the place is just not remembered
        }
    }
}
