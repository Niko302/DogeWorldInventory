package com.doge.worldinventory.manager;

import com.doge.worldinventory.config.WorldInventoryConfig;
import com.doge.worldinventory.data.InventorySnapshot;
import com.doge.worldinventory.data.InventoryStorage;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Core save/restore logic for per-world inventory isolation.
 *
 * Thread model:
 *   - Inventory reads/writes run on the world executor (world thread).
 *   - File I/O runs on the ioExecutor (dedicated background thread).
 *   - The two are chained with CompletableFuture so neither blocks the other.
 */
public class WorldInventoryManager {

    private final WorldInventoryConfig config;
    private final InventoryStorage storage;
    private final HytaleLogger logger;
    private final Executor ioExecutor;

    /** Tracks the world name each online player is currently in. */
    private final Map<UUID, String> playerCurrentWorld = new ConcurrentHashMap<>();

    public WorldInventoryManager(WorldInventoryConfig config,
                                  InventoryStorage storage,
                                  HytaleLogger logger,
                                  Executor ioExecutor) {
        this.config = config;
        this.storage = storage;
        this.logger = logger;
        this.ioExecutor = ioExecutor;
    }

    // -------------------------------------------------------------------------
    // Event handlers
    // -------------------------------------------------------------------------

    public void onPlayerAddedToWorld(UUID uuid, Player player, String newWorld, Executor worldExecutor) {
        String previousWorld = playerCurrentWorld.get(uuid);
        playerCurrentWorld.put(uuid, newWorld);

        boolean prevIsolated = config.isIsolated(previousWorld);
        boolean nextIsolated = config.isIsolated(newWorld);

        if (previousWorld == null || (!prevIsolated && !nextIsolated)) {
            return;
        }

        // Step 1 — IO thread: load the destination snapshot before touching anything
        CompletableFuture
            .supplyAsync(() -> storage.load(uuid, newWorld), ioExecutor)

            // Step 2 — world thread: check snapshot, capture current inventory
            .thenApplyAsync(incoming -> {
                // Safety guard: no saved snapshot for non-isolated destination means we have
                // never tracked this player leaving it — abort to prevent item loss.
                if (incoming == null && !config.isIsolated(newWorld)) {
                    logger.at(Level.WARNING).log("No snapshot for main world '" + newWorld
                            + "' for " + uuid + " — skipping swap to prevent item loss");
                    return null;
                }
                InventorySnapshot outgoing = capture(player);
                return new SnapshotPair(outgoing, incoming);
            }, worldExecutor)

            // Step 3 — IO thread: save the outgoing snapshot, abort if save fails
            .thenApplyAsync(pair -> {
                if (pair == null) return null;
                boolean saved = storage.save(uuid, previousWorld, pair.outgoing);
                if (!saved) {
                    logger.at(Level.SEVERE).log("ABORTING inventory swap for " + uuid
                            + " — save to world '" + previousWorld + "' could not be verified."
                            + " Inventory will NOT be cleared.");
                    return null;
                }
                return pair;
            }, ioExecutor)

            // Step 4 — world thread: clear and apply
            .thenAcceptAsync(pair -> {
                if (pair == null) return;
                player.getInventory().clear();
                if (pair.incoming != null) {
                    apply(player, pair.incoming);
                    logger.at(Level.FINE).log("Restored " + uuid + " inventory for world '" + newWorld + "'");
                }
                player.sendInventory();
            }, worldExecutor)

            .exceptionally(e -> {
                logger.at(Level.SEVERE).log("Error swapping inventory for " + uuid + ": " + e.getMessage());
                return null;
            });
    }

    public void onPlayerDisconnect(UUID uuid, Player player, Executor worldExecutor) {
        String currentWorld = playerCurrentWorld.remove(uuid);
        if (!config.isIsolated(currentWorld)) {
            return;
        }

        // Step 1 — world thread: capture inventory
        CompletableFuture
            .supplyAsync(() -> capture(player), worldExecutor)

            // Step 2 — IO thread: save to disk
            .thenAcceptAsync(snapshot -> {
                boolean saved = storage.save(uuid, currentWorld, snapshot);
                if (saved) {
                    logger.at(Level.FINE).log("Saved " + uuid + " inventory on disconnect from world '" + currentWorld + "'");
                } else {
                    logger.at(Level.SEVERE).log("Failed to save inventory on disconnect for " + uuid
                            + " from world '" + currentWorld + "' — items may be lost on next login");
                }
            }, ioExecutor)

            .exceptionally(e -> {
                logger.at(Level.SEVERE).log("Error saving inventory on disconnect for " + uuid + ": " + e.getMessage());
                return null;
            });
    }

    // -------------------------------------------------------------------------
    // Inventory capture / apply
    // -------------------------------------------------------------------------

    private InventorySnapshot capture(Player player) {
        Inventory inv = player.getInventory();
        InventorySnapshot snapshot = new InventorySnapshot();

        snapshot.setStorage(captureContainer(inv.getStorage()));
        snapshot.setArmor(captureContainer(inv.getArmor()));
        snapshot.setHotbar(captureContainer(inv.getHotbar()));
        snapshot.setUtility(captureContainer(inv.getUtility()));
        snapshot.setTool(captureContainer(inv.getTools()));
        snapshot.setActiveHotbarSlot(inv.getActiveHotbarSlot());

        return snapshot;
    }

    private List<InventorySnapshot.SlotEntry> captureContainer(ItemContainer container) {
        if (container == null) return new ArrayList<>();
        short capacity = container.getCapacity();
        List<InventorySnapshot.SlotEntry> slots = new ArrayList<>(capacity);
        for (short i = 0; i < capacity; i++) {
            ItemStack stack = container.getItemStack(i);
            if (stack == null || stack.isEmpty()) {
                slots.add(null);
            } else {
                slots.add(new InventorySnapshot.SlotEntry(
                        stack.getItemId(),
                        stack.getQuantity(),
                        stack.getDurability()
                ));
            }
        }
        return slots;
    }

    private void apply(Player player, InventorySnapshot snapshot) {
        Inventory inv = player.getInventory();
        applyContainer(inv.getStorage(), snapshot.getStorage());
        applyContainer(inv.getArmor(), snapshot.getArmor());
        applyContainer(inv.getHotbar(), snapshot.getHotbar());
        applyContainer(inv.getUtility(), snapshot.getUtility());
        applyContainer(inv.getTools(), snapshot.getTool());
    }

    private void applyContainer(ItemContainer container, List<InventorySnapshot.SlotEntry> slots) {
        if (container == null || slots == null) return;
        int size = Math.min(container.getCapacity(), slots.size());
        for (int i = 0; i < size; i++) {
            InventorySnapshot.SlotEntry entry = slots.get(i);
            if (entry != null && entry.getId() != null) {
                ItemStack stack = new ItemStack(entry.getId(), entry.getQuantity())
                        .withDurability(entry.getDurability());
                container.setItemStackForSlot((short) i, stack);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------

    private record SnapshotPair(InventorySnapshot outgoing, InventorySnapshot incoming) {}
}
