package com.doge.worldinventory;

import com.doge.worldinventory.config.WorldInventoryConfig;
import com.doge.worldinventory.data.InventoryStorage;
import com.doge.worldinventory.manager.WorldInventoryManager;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * DogeWorldInventory — per-world inventory isolation.
 *
 * Players moving between an isolated world and any other world have their
 * inventories saved and restored automatically. Config-driven: any world
 * can be flagged as isolated in config.yml.
 */
public class DogeWorldInventoryPlugin extends JavaPlugin {

    private WorldInventoryConfig config;
    private InventoryStorage storage;
    private WorldInventoryManager manager;
    private ExecutorService ioExecutor;

    public DogeWorldInventoryPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        this.ioExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "DogeWorldInventory-IO");
            t.setDaemon(true);
            return t;
        });

        this.config = new WorldInventoryConfig(this.getDataDirectory(), this.getLogger());
        this.config.load();

        this.storage = new InventoryStorage(this.getDataDirectory(), this.getLogger());

        this.manager = new WorldInventoryManager(config, storage, this.getLogger(), ioExecutor);
    }

    @Override
    protected void start() {
        // ---- AddPlayerToWorldEvent ----
        this.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, (event) -> {
            var ref = event.getHolder().getComponent(PlayerRef.getComponentType());
            var player = event.getHolder().getComponent(Player.getComponentType());
            if (ref == null || player == null) return;

            UUID uuid = ref.getUuid();
            World world = event.getWorld();
            String newWorld = world.getName();

            // Defer to the world executor — AddPlayerToWorldEvent fires before addedToStore(),
            // so ref.getReference() is null at event time. By the next world tick it is set.
            world.execute(() -> {
                var entityRef = ref.getReference();
                if (entityRef == null) {
                    getLogger().at(Level.WARNING).log("setGameMode: entityRef still null for " + uuid + " in world '" + newWorld + "'");
                    return;
                }
                Player.setGameMode(entityRef, GameMode.Adventure, entityRef.getStore());
                getLogger().at(Level.INFO).log("Set " + uuid + " to Adventure in world '" + newWorld + "'");
            });

            manager.onPlayerAddedToWorld(uuid, player, newWorld, world);
        });

        // ---- PlayerDisconnectEvent ----
        this.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, (event) -> {
            PlayerRef ref = event.getPlayerRef();
            if (ref == null) return;

            UUID uuid = ref.getUuid();

            try {
                var world = ref.getReference().getStore().getExternalData().getWorld();
                var store = ref.getReference().getStore();
                Player player = store.getComponent(ref.getReference(), Player.getComponentType());
                if (player != null) {
                    manager.onPlayerDisconnect(uuid, player, world);
                }
            } catch (Exception e) {
                getLogger().at(Level.WARNING).log("Could not save inventory on disconnect for " + uuid + ": " + e.getMessage());
            }
        });

        this.getLogger().at(Level.INFO).log("DogeWorldInventory loaded! Isolated worlds: " + config.getIsolatedWorlds());
    }

    @Override
    protected void shutdown() {
        if (ioExecutor != null) {
            ioExecutor.shutdown();
            try {
                if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ioExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ioExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        this.getLogger().at(Level.INFO).log("DogeWorldInventory shutting down.");
    }

    public void reloadConfig() {
        this.config.load();
        this.getLogger().at(Level.INFO).log("DogeWorldInventory configuration reloaded!");
    }
}
