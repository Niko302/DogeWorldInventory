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
 * All inventory operations are dispatched to the world executor so they run
 * on the correct world thread.
 */
public class WorldInventoryManager {

    private final WorldInventoryConfig config;
    private final InventoryStorage storage;
    private final HytaleLogger logger;

    /** Tracks the world name each online player is currently in. */
    private final Map<UUID, String> playerCurrentWorld = new ConcurrentHashMap<>();

    public WorldInventoryManager(WorldInventoryConfig config,
                                  InventoryStorage storage,
                                  HytaleLogger logger) {
        this.config = config;
        this.storage = storage;
        this.logger = logger;
    }

    // -------------------------------------------------------------------------
    // Event handlers — called from the plugin's event listeners
    // -------------------------------------------------------------------------

    /**
     * Called when a player is added to a world.
     *
     * @param uuid          player UUID
     * @param player        Player component (accessed on the world thread via worldExecutor)
     * @param newWorld      name of the world the player is entering
     * @param worldExecutor executor that runs tasks on the new world's thread
     */
    public void onPlayerAddedToWorld(UUID uuid, Player player, String newWorld, Executor worldExecutor) {
        String previousWorld = playerCurrentWorld.get(uuid);
        playerCurrentWorld.put(uuid, newWorld);

        boolean prevIsolated = config.isIsolated(previousWorld);
        boolean nextIsolated = config.isIsolated(newWorld);

        // Only act when at least one side is isolated
        if (previousWorld == null || (!prevIsolated && !nextIsolated)) {
            return;
        }

        // Dispatch all inventory work to the world thread so entity access is safe
        CompletableFuture.runAsync(() -> {
            try {
                // 1. Load the destination snapshot FIRST, before touching anything
                InventorySnapshot incoming = storage.load(uuid, newWorld);

                // Safety guard: if the destination is a non-isolated world and has no saved
                // snapshot, that means we have never tracked this player leaving it before.
                // Their current inventory may already be their main-world items, so clearing
                // would destroy them. Abort the swap — they keep what they have.
                if (incoming == null && !config.isIsolated(newWorld)) {
                    logger.at(Level.WARNING).log("No snapshot for main world '" + newWorld
                            + "' for " + uuid + " — skipping swap to prevent item loss");
                    return;
                }

                // 2. Capture and save the current inventory as previousWorld's snapshot
                InventorySnapshot outgoing = capture(player);
                storage.save(uuid, previousWorld, outgoing);
                logger.at(Level.FINE).log("Saved " + uuid + " inventory for world '" + previousWorld + "'");

                // 3. Clear and apply the destination snapshot
                player.getInventory().clear();
                if (incoming != null) {
                    apply(player, incoming);
                    logger.at(Level.FINE).log("Restored " + uuid + " inventory for world '" + newWorld + "'");
                }

                player.sendInventory();

            } catch (Exception e) {
                logger.at(Level.SEVERE).log("Error swapping inventory for " + uuid + ": " + e.getMessage());
            }
        }, worldExecutor);
    }

    /**
     * Called when a player disconnects.
     *
     * @param uuid          player UUID
     * @param player        Player component
     * @param worldExecutor executor for the player's current world
     */
    public void onPlayerDisconnect(UUID uuid, Player player, Executor worldExecutor) {
        String currentWorld = playerCurrentWorld.remove(uuid);
        if (!config.isIsolated(currentWorld)) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                InventorySnapshot snapshot = capture(player);
                storage.save(uuid, currentWorld, snapshot);
                logger.at(Level.FINE).log("Saved " + uuid + " inventory on disconnect from world '" + currentWorld + "'");
            } catch (Exception e) {
                logger.at(Level.SEVERE).log("Error saving inventory on disconnect for " + uuid + ": " + e.getMessage());
            }
        }, worldExecutor);
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
}
