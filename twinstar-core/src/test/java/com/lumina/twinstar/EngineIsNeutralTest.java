package com.lumina.twinstar;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine must not know Minecraft, NeoForge or AE2: that is what keeps it portable between versions. Anything
 * it needs from them goes through the bridge and the ports.
 */
class EngineIsNeutralTest {
    private static final List<String> FORBIDDEN = List.of("net.minecraft", "net.neoforged", "net.minecraftforge", "appeng.");

    @Test
    void mainSourcesImportNoPlatformCode() throws IOException {
        Path root = Path.of("src/main/java");
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            for (Path f : sources)
                for (String line : Files.readAllLines(f))
                    if (line.trim().startsWith("import "))
                        for (String banned : FORBIDDEN)
                            if (line.trim().replace("import static ", "import ").startsWith("import " + banned))
                                bad.add(root.relativize(f) + ": " + line.trim());
        }
        assertTrue(bad.isEmpty(), "platform imports in the engine:\n" + String.join("\n", bad));
    }
}
