package com.doge.worldinventory.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Saves and loads per-player per-world inventory snapshots as JSON files.
 *
 * Storage layout:
 *   {dataDir}/inventories/{uuid}/world_{worldName}.json
 */
public class InventoryStorage {

    private final Path dataDirectory;
    private final HytaleLogger logger;
    private final Gson gson;

    public InventoryStorage(Path dataDirectory, HytaleLogger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * Persist a snapshot to disk. Blocks the calling thread — call from an async executor.
     */
    public void save(UUID uuid, String worldName, InventorySnapshot snapshot) {
        try {
            Path dir = inventoryDir(uuid);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path file = dir.resolve(fileName(worldName));
            try (Writer writer = Files.newBufferedWriter(file)) {
                gson.toJson(snapshot, writer);
            }
            logger.at(Level.FINE).log("Saved inventory for " + uuid + " in world '" + worldName + "'");
        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to save inventory for " + uuid + " world '" + worldName + "': " + e.getMessage());
        }
    }

    /**
     * Load a snapshot from disk. Returns null if no snapshot exists.
     * Blocks the calling thread — call from an async executor.
     */
    public InventorySnapshot load(UUID uuid, String worldName) {
        Path file = inventoryDir(uuid).resolve(fileName(worldName));
        if (!Files.exists(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            InventorySnapshot snapshot = gson.fromJson(reader, InventorySnapshot.class);
            logger.at(Level.FINE).log("Loaded inventory for " + uuid + " in world '" + worldName + "'");
            return snapshot;
        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to load inventory for " + uuid + " world '" + worldName + "': " + e.getMessage());
            return null;
        }
    }

    // ---- helpers ----

    private Path inventoryDir(UUID uuid) {
        return dataDirectory.resolve("inventories").resolve(uuid.toString());
    }

    /** Convert a world name to a safe filename, e.g. "world_default.json". */
    private String fileName(String worldName) {
        String safe = worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return "world_" + safe + ".json";
    }
}
