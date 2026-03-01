package com.doge.worldinventory.data;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Saves and loads per-player per-world inventory snapshots as JSON files.
 *
 * Storage layout:
 *   {dataDir}/inventories/{uuid}/world_{worldName}.json
 *
 * Save sequence (write-verify-commit):
 *   1. Write to world_{name}.json.tmp
 *   2. Read back and verify Gson can parse it
 *   3. Atomically rename .tmp → .json  (falls back to regular move on Windows/OneDrive)
 *   Returns true only if all three steps succeed.
 *   A false return means the original file is untouched and the caller must NOT clear.
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
     * Write-verify-commit save. Returns true only if the snapshot was successfully
     * written to disk AND read back without errors. Callers must not clear the
     * player's inventory if this returns false.
     */
    public boolean save(UUID uuid, String worldName, InventorySnapshot snapshot) {
        Path dir = inventoryDir(uuid);
        Path finalFile = dir.resolve(fileName(worldName));
        Path tempFile  = dir.resolve(fileName(worldName) + ".tmp");

        try {
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // Step 1: write to temp file
            try (Writer writer = Files.newBufferedWriter(tempFile)) {
                gson.toJson(snapshot, writer);
            }

            // Step 2: read back and verify it parses correctly
            InventorySnapshot verification;
            try (Reader reader = Files.newBufferedReader(tempFile)) {
                verification = gson.fromJson(reader, InventorySnapshot.class);
            }
            if (verification == null) {
                logger.at(Level.SEVERE).log("Save verification failed for " + uuid
                        + " world '" + worldName + "' — file parsed as null, aborting");
                Files.deleteIfExists(tempFile);
                return false;
            }

            // Step 3: commit — atomic rename if supported, regular move otherwise
            try {
                Files.move(tempFile, finalFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING);
            }

            logger.at(Level.FINE).log("Saved and verified inventory for " + uuid
                    + " in world '" + worldName + "'");
            return true;

        } catch (IOException e) {
            logger.at(Level.SEVERE).log("Failed to save inventory for " + uuid
                    + " world '" + worldName + "': " + e.getMessage());
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            return false;
        }
    }

    /**
     * Load a snapshot from disk. Returns null if no snapshot exists or parsing fails.
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
            logger.at(Level.SEVERE).log("Failed to load inventory for " + uuid
                    + " world '" + worldName + "': " + e.getMessage());
            return null;
        }
    }

    // ---- helpers ----

    private Path inventoryDir(UUID uuid) {
        return dataDirectory.resolve("inventories").resolve(uuid.toString());
    }

    private String fileName(String worldName) {
        String safe = worldName.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return "world_" + safe + ".json";
    }
}
