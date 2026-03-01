# DogeWorldInventory

A Hytale server plugin that gives each player a separate inventory per world. When a player moves between an isolated world and any other world, their inventory is automatically saved and restored. Inventories persist across disconnects and server restarts.

## How it works

- Each world can be flagged as **isolated** in `config.yml`
- Moving **into or out of** an isolated world triggers a save/restore swap:
  - Current inventory is captured and saved to disk
  - Save is verified (write → read back → commit) before anything is cleared
  - If the save cannot be verified, the swap is aborted — inventory is never touched
  - The destination world's saved inventory is restored (empty on first visit)
- Moving between two **non-isolated** worlds does nothing — inventory is shared as normal
- On disconnect from an isolated world the inventory is saved, so it survives reconnects

## Configuration

`config.yml` is created in the plugin data directory on first run:

```yaml
isolated-worlds:
  - event
```

Add any world name to the list to isolate it. Names are case-insensitive.

## Storage

Inventories are stored as JSON files:

```
Doge_DogeWorldInventory/inventories/{uuid}/world_{name}.json
```

## Thread model

- Inventory reads/writes run on the **world thread**
- All file I/O runs on a dedicated **IO thread** — disk operations never stall the world tick
- The two are chained with `CompletableFuture` in the correct order

## Save safety

Saves use a write-verify-commit sequence:
1. Write to `world_{name}.json.tmp`
2. Read back and verify the file parses correctly
3. Atomically rename `.tmp` → `.json`
4. Only if all three steps succeed is the inventory cleared

A server restart between steps 3 and 4 (clear) is safe — the file is already committed to disk and the inventory has not been touched yet.

## Building

Requires Java 25 and Gradle (wrapper included).

```bash
./gradlew build
```

Output jar: `build/libs/DogeWorldInventory-<version>.jar`
