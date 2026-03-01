package com.doge.worldinventory.config;

import com.hypixel.hytale.logger.HytaleLogger;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads config.yml and exposes the isolated-worlds list.
 */
public class WorldInventoryConfig {

    private static final String CONFIG_FILE = "config.yml";

    private final Path dataDirectory;
    private final HytaleLogger logger;

    private Set<String> isolatedWorlds = new HashSet<>();

    public WorldInventoryConfig(Path dataDirectory, HytaleLogger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    public void load() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }

            Path configPath = dataDirectory.resolve(CONFIG_FILE);
            if (!Files.exists(configPath)) {
                copyDefaultConfig(configPath);
            }

            Yaml yaml = new Yaml();
            try (InputStream in = Files.newInputStream(configPath)) {
                Map<String, Object> data = yaml.load(in);
                if (data != null) {
                    Object raw = data.get("isolated-worlds");
                    if (raw instanceof List<?> list) {
                        Set<String> worlds = new HashSet<>();
                        for (Object entry : list) {
                            if (entry instanceof String s) {
                                worlds.add(s.toLowerCase());
                            }
                        }
                        isolatedWorlds = worlds;
                    }
                }
            }

            logger.at(Level.INFO).log("Loaded config — isolated worlds: " + isolatedWorlds);

        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to load config: " + e.getMessage());
        }
    }

    /** Returns true if the given world name is marked as isolated (case-insensitive). */
    public boolean isIsolated(String worldName) {
        if (worldName == null) return false;
        return isolatedWorlds.contains(worldName.toLowerCase());
    }

    public Set<String> getIsolatedWorlds() {
        return Collections.unmodifiableSet(isolatedWorlds);
    }

    private void copyDefaultConfig(Path target) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CONFIG_FILE)) {
            if (in != null) {
                Files.copy(in, target);
                logger.at(Level.INFO).log("Created default config.yml");
            } else {
                // Write a minimal fallback if the bundled resource is missing
                try (Writer writer = Files.newBufferedWriter(target)) {
                    writer.write("isolated-worlds:\n  - event\n");
                }
                logger.at(Level.WARNING).log("Bundled config.yml not found — wrote minimal default");
            }
        }
    }
}
