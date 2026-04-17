# JoshyMC Plugin — Claude Code Guide

## What is this?
A Paper MC plugin (Kotlin) for Joshy's Minecraft server. This is intended to **replace multiple Spring-made plugins** with a single, well-structured custom plugin over time.

## Target Server
- **Paper MC 1.21.11** (newest version, beyond Claude's training cutoff)
- Java 21 toolchain
- Gradle + Shadow plugin for fat JAR, `run-paper` plugin for local testing

## Running locally
```bash
./gradlew runServer    # Starts a Paper 1.21.11 test server with the plugin loaded
./gradlew build        # Builds the shadow JAR
```

## Project structure
```
com.liam.joshymc
├── Joshymc.kt              # Main plugin class, initializes managers
├── manager/
│   ├── DatabaseManager.kt  # SQLite database (plugins/Joshymc/data.db)
│   ├── SettingsManager.kt  # Player settings GUI + SQLite persistence
│   ├── ItemManager.kt      # Registry for all custom items (PDC-tagged)
│   ├── CommandManager.kt   # Registry for all commands
│   ├── ListenerManager.kt  # Registry for all event listeners
│   ├── RecipeManager.kt    # Registry for custom crafting recipes
│   ├── LagCleanerManager.kt # Periodic ground item/mob clearing
│   └── ResourcePackManager.kt
├── item/
│   ├── CustomItem.kt       # Abstract base class for custom items
│   └── impl/
│       └── VoidDrill.kt    # 3x3 mining drill pickaxe
├── listener/
│   ├── DrillMiningListener.kt   # 3x3 break logic + sounds
│   ├── NightVisionListener.kt   # Reapply NV on join
│   ├── GSitListener.kt          # Right-click stairs/slabs to sit
│   ├── DeathCoordsListener.kt   # Send death coords
│   ├── RecipeBlockerListener.kt # Block configured recipes
│   └── ChatItemListener.kt      # [i] shows held item in chat
├── command/
│   ├── JoshyCommand.kt          # /joshymc give|reload
│   ├── SettingsCommand.kt       # /settings GUI
│   └── NightVisionCommand.kt    # /nightvision, /nv
└── util/
    └── TextUtil.kt          # Adventure text helpers (gradients, etc.)
```

## Adding new features
1. **New custom item**: Create a class in `item/impl/` extending `CustomItem`, register it in `ItemManager.registerAll()`
2. **New listener**: Create in `listener/`, register in `ListenerManager.registerAll()`
3. **New command**: Create in `command/`, register in `CommandManager.registerAll()`, add to `plugin.yml`
4. **New recipe**: Create in a `recipe/` package, register in `RecipeManager.registerAll()`

## Custom items use PDC (PersistentDataContainer)
All custom items are tagged with `joshymc:custom_item_id` in their PDC. Use `ItemManager.isCustomItem(stack, "id")` to check.

## Commands
- `/joshymc give <item_id> [player]` — Give a custom item (requires `joshymc.give` permission, default: op)
- `/joshymc reload` — Soft reload config + managers (requires `joshymc.reload`, default: op)
- `/settings` — Open player settings GUI
- `/nightvision [on|off]` (alias `/nv`) — Toggle night vision (requires `joshymc.nightvision`, default: all)

## Player Settings System
- `SettingsManager` provides a chest GUI for toggling per-player preferences
- Stored in SQLite (`player_settings` table)
- Register new settings via `settingsManager.register(SettingDef(...))` in `Joshymc.registerSettings()`
- Features check `settingsManager.getSetting(player, key)` before applying

## Database
- SQLite via `DatabaseManager` — file at `plugins/Joshymc/data.db`
- No external database needed (not using Pebble MySQL)
- Managers call `databaseManager.createTable(...)` on init, then use `execute()`, `query()`, `transaction()`

## Current custom items
- `void_drill` — Void Drill [3x3], diamond pickaxe, mines 3x3 area. Unbreakable.
- `void_drill_5x5` — Void Drill [5x5], netherite pickaxe, mines 5x5 area. Unbreakable.
- `void_bore` — Void Bore [1x1], right-click to drill 1x1 shaft to bedrock. Single use. No deploy limit.
- `void_bore_5x5` — Void Bore [5x5], right-click to drill 5x5 shaft to bedrock. Single use. No deploy limit.
- `void_bore_chunk` — Void Bore [Chunk], right-click to excavate the exact chunk to bedrock. Single use. No deploy limit.
- `easter_egg` — Easter Egg, golden egg (gold nugget). Right-click to open and receive a random prize egg.
- `explosive_egg` — Explosive Egg, throwable. Deals damage on hit with explosion effect. No terrain damage.
- `freeze_egg` — Freeze Egg, throwable. Applies Slowness III for 5 seconds on hit.
- `blindness_egg` — Blindness Egg, throwable. Applies Blindness for 30 seconds on hit.

## Resource Pack
- `ResourcePackManager` enforces a required resource pack on player join
- Config in `config.yml` — `resource-pack.url` and `resource-pack.hash`
- Pack skeleton lives in `resourcepack/` at project root (zip and host externally)
- Custom item models use `setItemModel(NamespacedKey)` — models in `assets/joshymc/models/item/`
- Add 16x16 PNG textures to `resourcepack/assets/joshymc/textures/item/` for each item

## Discord Integration
- JDA 5.2.3 (shaded + relocated in shadow JAR)
- Config in `config.yml` — bot token, channel IDs, message formats, event toggles
- `DiscordManager` handles bot lifecycle, async message queue (batched every 2 ticks)
- 2-way chat bridge: Minecraft chat → Discord channel, Discord messages → in-game broadcast
- Events forwarded: join, leave, death, server start/stop
- `DiscordChatListener` (JDA side) receives Discord messages → broadcasts in-game
- `MinecraftChatListener` (Bukkit side) forwards chat/join/leave/death → Discord
- @everyone/@here mentions are escaped automatically
- Discord bot connects async so it doesn't block server startup

## Style conventions
- Kotlin, no Java files
- Adventure API for all text (no legacy ChatColor)
- NamespacedKey-based PDC for item identification
- Managers handle registration; main class just initializes them
- Lore is simple and unified via LoreBuilder (type, description, usage). No backstories or flavor text.
- Shared constants (UNBREAKABLE blocks, isMineable) live in BlockUtil
