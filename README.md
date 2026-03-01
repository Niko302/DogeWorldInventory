# DogeWorldInventory

A Hytale server plugin that gives each player a separate inventory per world. When a player moves between an isolated world and any other world, their inventory is automatically saved and restored. Inventories persist across disconnects.

## How it works

- Each world can be flagged as **isolated** in `config.yml`
- Moving **into or out of** an isolated world triggers a save/restore swap:
  - Current inventory is saved for the world being left
  - Inventory is cleared
  - The destination world's saved inventory is restored (empty on first visit)
- Moving between two **non-isolated** worlds does nothing — inventory is shared as normal
- On disconnect from an isolated world the inventory is saved, so it survives reconnects

## Configuration

`config.yml` is created in the plugin's data directory on first run:

```yaml
isolated-worlds:
  - event
```

Add any world name to the list to isolate it. Names are case-insensitive.

## Storage

Inventories are stored as JSON files:

```
{pluginDataDir}/inventories/{uuid}/world_{name}.json
```

## Building

Requires Java 25 and Gradle (wrapper included).

```bash
./gradlew build
```

Output jar: `build/libs/DogeWorldInventory-<version>.jar`
