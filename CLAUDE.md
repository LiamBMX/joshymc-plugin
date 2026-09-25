# JoshyMC Plugin — Claude Code Guide

## What is this?
A Paper MC plugin (Kotlin) for Joshy's Minecraft server. It replaces a stack of Spring/3rd-party plugins with a single custom plugin. As of v1.0.48 it owns: claims, combat, economy, market, auctions, sign shops, custom enchants, custom items, custom spawners, kits, warps/homes/RTP, TPA, vaults/storage, leaderboard holograms, NPCs, crates, arenas, portals, voting, ranks, teams, quests, talismans, fishing, skills, all cosmetics (trails/kill effects/join effects/glow/emote/gadget/chat tags/chat colors/nicks), AFK, anticheat (with Grim bridge), playtime, scoreboard sidebar, MOTD/welcome, announcements, multi-world, resource world, spawn world, settings GUI, lag cleaner, recipe blocker, and a 2-way Discord bridge. World/region protection flags (pvp, block break, potions, ender pearls, etc.) are no longer a custom JoshyMC system — that's WorldGuard's job now (see issue #673).

## Target Server
- **Paper 26.2** (`paper-api 26.2.build.128-stable`, `api-version: '26.2'`) — newer than the model's training cutoff. **Trust the user about API surface**; check the jars with `javap` instead of guessing. Don't reach for `BlockFromToEvent` workarounds you remember from 1.16.
- Kotlin compiled with a Java 21 toolchain to Java 21 bytecode; the server runs Java 25. `paper-api` is published for Java 25, so `build.gradle.kts` calls `java { disableAutoTargetJvm() }`.
- No NMS and no paperweight: plain `compileOnly` Paper API.
- Gradle + Shadow plugin for fat JAR, `run-paper` plugin for local testing
- JDA 5.2.3 (shaded + relocated for Discord)

## Running locally
```bash
./gradlew runServer    # Starts a Paper 26.2 test server (Java 25, fetched by the foojay resolver)
./gradlew build        # Builds the shadow JAR → jar/joshymc-1.0-SNAPSHOT-all.jar
```

## Release workflow (used every iteration)
The user ships almost every change as a tagged GitHub release. Established pattern (commit → push → tag → release):
```bash
git add -A
git commit -m "<conventional msg>"   # commit auto-pushes via global rule on non-main; ask before pushing main
git push
git tag v1.0.X
git push origin v1.0.X
cp jar/joshymc-1.0-SNAPSHOT-all.jar jar/joshymc-1.0.X.jar
gh release create v1.0.X jar/joshymc-1.0.X.jar --title "..." --notes "$(cat <<'EOF' ... EOF)"
```
The repo lives at `https://github.com/LiamBMX/joshymc-plugin`. Latest tag at time of writing: **v1.0.48**.

> Per global git rules, on `main` we commit freely but **ask before pushing**. In practice the user has confirmed this release-on-main workflow many times — when in auto mode and the request matches the established pattern (build/tag/release), proceed.

## Project structure (high level)
```
com.liam.joshymc
├── Joshymc.kt              # Main plugin class — owns all manager lateinits + onEnable/onDisable/reload
├── manager/                # ~55 managers, see the table below
├── item/{CustomItem.kt, impl/*}     # Custom items (PDC-tagged)
├── command/*               # Each command = its own class implementing CommandExecutor + (often) TabCompleter
├── listener/*              # Bukkit listeners — registered in ListenerManager.registerAll()
├── enchant/*               # Custom enchants (CustomEnchant + CustomEnchantManager)
├── gui/{GuiManager.kt}     # Shared chest-GUI framework (CustomGui), see "GUI patterns"
├── discord/                # JDA bot lifecycle, async message queue
├── link/                   # Discord ↔ Minecraft account linking
└── util/                   # TextUtil (Adventure helpers), BlockUtil (UNBREAKABLE / isMineable)
```

## Managers (what each one owns)

Every `xxxManager` is initialized in `Joshymc.onEnable()` as `lateinit var ... ; private set`, then `start()`-ed (most have a feature flag in `config.yml > features.*`). Most use SQLite tables created via `databaseManager.createTable(...)`.

| Manager | Responsibility |
|---|---|
| **DatabaseManager** | SQLite at `plugins/Joshymc/data.db`. Provides `createTable`, `execute`, `query`, `queryFirst`, `transaction`. Every persistent manager calls it. |
| **SettingsManager** | Per-player settings GUI + persistence. Register `SettingDef` in `Joshymc.registerSettings()`. |
| **ItemManager** | Registry for custom items. PDC tag `joshymc:custom_item_id`. Use `isCustomItem(stack, "id")`. |
| **CommandManager** | Wires every `/command` to its executor. **Add new commands here AND in `plugin.yml`.** |
| **ListenerManager** | Registers every Bukkit listener. **Add new listeners here.** |
| **RecipeManager** | Custom crafting recipes. |
| **CommunicationsManager** | Single source of truth for sending Adventure messages — applies prefix, parses `&`/hex, handles cross-platform send. **Always use `commsManager.send(player, component)`** instead of `player.sendMessage` so prefix and chat-format apply. |
| **EconomyManager** | Player balances (table `economy`). `getBalance`, `deposit`, `withdraw`, `setBalance`, `getTopBalances` (returns UUID strings — resolve names via `nameOf(UUID)` or `Bukkit.getOfflinePlayer`). |
| **CombatManager** | Combat-tagging (15s default), combat log → spawn NPC + dump inventory. **Combat-log dupe fix in v1.0.44**: also clears armor (`setArmorContents(arrayOfNulls(4))`) + offhand (`setItemInOffHand(null)`), not just `inventory.clear()`. `isTagged(player)` is the universal "are they in PvP" check. |
| **ArenaManager** | Polygon-defined PvP arenas. Three layers of boundary protection: (1) PlayerMoveEvent setTo(pinned) on cross, (2) `tickPvpZones` cache-admit refusal for tagged/AFK, (3) strict cache+live both-must-be-inside-same-arena check in onDamage. **v1.0.44 fix**: use `from.x == to.x && from.z == to.z` (not blockX/Z) for sub-block-edge crossings, plus 5-tick `tickCombatPin()` backstop. **v1.0.44 fix**: `onPushAcrossBoundary` blocks knockback push-ins via `lastPlayerHitMs` tracking. |
| **ClaimManager** | Rectangular per-player/per-team claims with claim-block currency. `getClaimAt(loc)`, `canAccess(player, loc)`, subclaims. Blocked worlds = `resource`, `spawn`, `afk`. v1.0.44: `/claim addblocks`, `/claim setblocks` admin commands. |
| **ClaimProtectionListener** | All "what can / can't happen in a claim" rules — break/place, interact, bucket, hanging, armor stand, explosions, entity-change-block, PvP, **liquid flow (BlockFromToEvent, v1.0.48)**, **piston extend/retract across boundary (v1.0.48)**, **redstone cross-boundary (v1.0.48)**. Note: BlockRedstoneEvent is NOT Cancellable — neutralize with `event.newCurrent = event.oldCurrent`. |
| **TeamManager** | Player teams. |
| **RankManager** | Player ranks (Owner/Admin/Mod/Helper/etc.) + rank teams. **v1.0.46 dual-team trick**: each rank has `<base>_y` and `<base>_n` Bukkit team variants (same prefix, different COLLISION_RULE). `shouldCollide()` decides which variant to assign based on combat-tagged → arena → world-list. The 2s sidebar tick re-evaluates and reassigns as needed. Solved Bukkit's mutual-exclusivity (one player → one team per scoreboard). |
| **ScoreboardManager** | Per-player sidebar. Each line is rendered via a per-line Bukkit team's prefix (Component) attached to a unique invisible "entry" string. **v1.0.47**: time uses `ZonedDateTime.now(plugin.timezoneManager.zoneFor(player))` — defaults to EST. |
| **TimezoneManager** | (v1.0.47) Per-player timezone (`player_timezones` table), in-memory cached. `zoneFor(player)` → `cache[uuid] ?: DEFAULT_ZONE` where `DEFAULT_ZONE = America/New_York`. `parseZone(input)` accepts IANA + shorthand (EST, PST, UTC, JST, …). |
| **MarketManager** | Dynamic stock market (per-material price drift). |
| **ServerShopManager** | `/shop` GUI buy/sell. |
| **AuctionManager** | `/ah` auction house. |
| **SignShopManager** | Sign-based chest shops. |
| **HopperPlusManager** | Upgraded hoppers with filters. |
| **SpawnerManager** | Custom spawners loaded from `spawners.yml`. Each placed block is a `SpawnerBlock` (key, spawnerId, owner, stackCount, enabled, storage, **filteredOut: MutableSet\<Material\>** added v1.0.48). Drop generation uses `block.filteredOut` to discard at generation. **v1.0.48 GUI**: 54-slot storage view (45 storage + 9 buttons), per-spawner filter sub-GUI (`openFilterGui`), shorter title to avoid overflow, `openStorageGuis` map + per-second tick to refresh slots so hopper pulls show up live. |
| **CrateManager** + **CrateEditorCommand** | Server crates with keys + animations. |
| **NPCManager** | Static NPCs that run commands on click. Each NPC is a `Mannequin` entity (player model, skin by player name via `ResolvableProfile`); no NMS. |
| **HologramManager** | Floating text holograms. |
| **LeaderboardManager** | (v1.0.45) 6 player + 6 team leaderboard hologram types via SQL aggregation joins. **v1.0.45 fix**: money leaderboard was rendering UUIDs as names — resolve via `nameOf(UUID.fromString(...))`. **v1.0.45 fix**: TEAM_MONEY SQL was joining `balances` but actual table is `economy`. |
| **PunishmentManager** | Bans / mutes / kicks / warns + history. |
| **AntiCheatManager** + **GrimIntegration** | Internal AC checks + auto-installer for Grim's `punishments.yml` bridge that calls `/jmc-violation` so Grim flags surface in `/admin`. |
| **AdminManager** | `/admin` GUI panel — player info, freeze, snapshot, rollback, ban/mute lists. |
| **ResourceWorldManager** | Auto-regenerating resource world. |
| **SpawnWorldManager** | The `spawn` world (separate from main overworld). |
| **PortalManager** | Custom portals (region wand → action). |
| **ArenaManager** | (above) |
| **VoteManager** + **VotifierServer** | Vote rewards. `/vote` is intentionally NOT claimed by JoshyMC (other vote plugins own it); we expose `/jvote` only. CommandManager unregisters `/vote` at runtime as a belt-and-suspenders safety net. |
| **SpawnDecorationManager** | Holograms/particles/beacons at spawn. |
| **WarpManager** + (`/spawn`, `/warp`, `/setwarp`, `/pwarp`) | Server + player warps. |
| **KitManager** | `/kit` with cooldowns, `/createkit`, `/deletekit`. |
| **StorageManager** | `/pv` player vaults. |
| **TradeManager** + **TradeInteractListener** | `/trade <player>` and shift+right-click on a player. **v1.0.48 fix**: filter `PlayerInteractAtEntityEvent` to `EquipmentSlot.HAND` so shift+right-click doesn't double-fire (one main, one off-hand). |
| **AFKManager** + **AFKListener** | AFK detection, AFK reward, AFK key custom item. |
| **PlaytimeManager** | Hour-based playtime tracking + `/playtime` `/playtimetop`. |
| **AnnouncementManager** | Periodic broadcasts (configured in `config.yml > announcements`). |
| **ChatTagManager** | Equippable chat tags. |
| **ChatGamesManager** | Math/unscramble/type/reverse chat games on a timer. |
| **QuestCycleManager** | The only quest system (the old numbered/permanent quest journal, `QuestManager`, was fully retired — see issue #489). Unified Daily / Weekly / Quest Master quests defined in `quest-cycle.yml`, opened via `/quests` (aliases `/quest`, `/questboard`, `/questbook`, `/daily`). Server-wide rotating pool, per-player progress in `quest_cycle_progress`. `ResurgeManager` gates prestige on lifetime Quest Master completions (not the old per-category quest requirement) and scales quest target amounts via `getEffectiveAmount`. |
| **TalismanManager** | Equippable talismans/relics. |
| **FishingManager** | Custom fishing collection. |
| **SkillManager** | XP/leveling skills. |
| **TrailManager / KillEffectManager / JoinEffectManager / EmoteManager / GlowManager / GadgetManager** | Cosmetics. |
| **ResourcePackManager** | Required resource pack on join. Serves the `resourcepack.zip` bundled in the JAR and replaces `plugins/Joshymc/resourcepack.zip` whenever the bundled one differs. |
| **ItemManager → CustomItem** | Base class for all custom items; PDC-tagged. |
| **CustomEnchantManager** | Custom enchants on gear (see `Joshymc.registerEnchants()`). |
| **MobVisibilityListener** | (v1.0.43) Per-player mob hiding. **3 damage paths must all be closed**: (1) direct hit (`EntityDamageByEntityEvent` damager-is-LivingEntity), (2) projectile (`Projectile.shooter` resolution), (3) explosion AoE (`EntityDamageEvent` cause `ENTITY_EXPLOSION`/`BLOCK_EXPLOSION`/`WITHER`/`MAGIC`). |
| **CombatListener / DrillMiningListener / VeinminerListener / TreeFellerListener / AutoSmeltListener / ChatItemListener / ChatFormatListener / GSitListener / DeathCoordsListener / RecipeBlockerListener / NightVisionListener / CustomArmorListener / EasterEggListener / SellWandListener / CustomDropListener / UnknownCommandListener / LinkGuiListener / WelcomeListener / MinecraftChatListener** | Self-explanatory listeners — see file. |
| **DiscordManager** + **DiscordChatListener** + **MinecraftChatListener** | 2-way chat bridge. JDA connects async so server startup isn't blocked. @everyone/@here are escaped. |

## Adding new features
1. **New custom item**: Class in `item/impl/` extending `CustomItem`, register in `ItemManager.registerAll()`. PDC-tag with the item id.
2. **New listener**: Class in `listener/`, register in `ListenerManager.registerAll()`.
3. **New command**: Class in `command/`, register in `CommandManager.registerAll()`, add entry to `plugin.yml`. Permission entries also go in `plugin.yml`.
4. **New recipe**: Class in `recipe/`, register in `RecipeManager.registerAll()`.
5. **New manager**: Add `lateinit var fooManager: FooManager` (with `private set`) in `Joshymc.kt`, instantiate in `onEnable()` after dependencies, call `start()` after `databaseManager` is up. Add to `onDisable()` and `reload()` if it has lifecycle.
6. **New chest GUI**: Use `CustomGui` from `gui/GuiManager.kt`, open via `plugin.guiManager.open(player, gui)`. Click handlers per slot.
7. **New player setting**: Register in `Joshymc.registerSettings()` via `SettingDef(...)`. Features check `settingsManager.getSetting(player, key)`.

## GUI patterns (`gui/GuiManager.kt`)
- **`CustomGui(title, size)`** — wraps a Bukkit Inventory + per-slot click handlers + onClose hook.
- **`plugin.guiManager.open(player, gui)`** opens it.
- **Click behavior (v1.0.48 rules)**: `GuiManager.onClick` keys off `event.clickedInventory`. If the click landed on the GUI itself → cancel + route to handler. If it landed on the player's own inventory → allow ordinary clicks but BLOCK actions that would push items into the GUI: `isShiftClick`, `DOUBLE_CLICK`, `NUMBER_KEY`, `SWAP_OFFHAND`. **Pre-v1.0.48 the entire bottom inventory was frozen**, which was the bug Blu3 reported.
- **GUI persistence on page navigation**: `openGuis[uuid]` is set BEFORE `player.openInventory(...)`, so the close-event for the old inventory sees the new GUI as already-tracked and doesn't remove it.
- **Anti-dupe**: 200ms click cooldown enforced on GUI clicks.

## Persistence patterns
- All managers that persist data create their tables in `start()` via `databaseManager.createTable("CREATE TABLE IF NOT EXISTS ...")`.
- For schema migrations, use `databaseManager.execute("ALTER TABLE ... ADD COLUMN ...")` wrapped in try/catch — SQLite throws if the column exists.
- For config files (e.g. `spawners.yml`, `chat-tags.yml`), the pattern is "read user file, compare against bundled resource, merge missing keys without overwriting customizations". `SpawnerManager.mergeMissingFromDefaults` and `ChatTagManager` both implement this.
- `Joshymc.migrateConfig()` does the same for `config.yml` — backfills any keys that exist in the bundled `config.yml` but are missing from the player-edited file.

## Custom items (PDC tag: `joshymc:custom_item_id`)
**v1.0.49 catalog cleanup (issue #708)**: the old RPG loot catalog (crafting-material weapons/tools/armor sets, consumables, legendary items, `void_bore`/`void_bore_5x5`/`void_bore_chunk`, `carrot_sword`, Bunny armor) was removed — those classes, recipes, resourcepack assets, and the `bunny` kit are gone. Surviving custom items: Void Drill (`void_drill`, `void_drill_5x5`), all eggs (`easter_egg`, `explosive_egg`, `freeze_egg`, `blindness_egg`, `teleport_egg`, `levitation_egg`, `knockback_egg`, `swap_egg`, `lightning_egg`, `cobweb_egg`, `confusion_egg`, `ender_egg`), the September Autumn collection (`autumns_edge`, `harvest_scythe`, `orchard_pickaxe`, `golden_crest`, `falling_leaf`), the October Halloween collection (`phantoms_grasp`, `gravedigger`, `jack_o_lantern_mask`, `bone_rattler`, `pumpkin_pummel`), the Woodland collection (`lumberjacks_legacy`, `woodland_hunter`, `maplefang`, `autumn_wanderer`, `hearthkeeper`), the Winter collection (`frostbite`, `glacier_breaker`, `ice_skates`, `wings_of_the_blizzard`, `winters_wrath`), `bubble_butt_leggings`, ModMode/TraineeMode staff tools (`item/impl/ModModeTools.kt`, `item/impl/TraineeModeTools.kt`), and two items kept as exceptions because a whole command/manager depends on them rather than them being catalog decoration: `sell_wand` (SellWandListener/SellWandCommand), `token` (`/tokens` economy).

**Great Pumpkin Pie** (`great_pumpkin_pie`, `HalloweenItems.kt` + `listener/GreatPumpkinPieListener.kt`): a placeable pie eaten slice by slice. Placing goes through the vanilla `BlockPlaceEvent` of its CAKE base (so claims/WorldGuard apply), is cancelled, and spawns an `ItemDisplay` (transform NONE, yaw = placer's yaw so slice 1 faces them; item displays render turned 180 degrees) plus an `Interaction` hitbox holding the state in PDC. Right-click eats a slice (3 food, model swaps via custom model data `bite_N`, squash animation, rising chime), punching wobbles it, sneak-punch picks an untouched pie back up (owner, claim owner or `joshymc.admin`). Pies pop when their support block goes, block/liquid/piston intrusion into their space is refused, and lost models are restored.

**Thrown Maplefang** (`listener/ThrownTridentVisualListener.kt`): the client always draws thrown tridents with the vanilla model, so custom tridents are hidden (`setVisibleByDefault(false)`) and a non-persistent `ItemDisplay` of the thrown item follows them each tick, pointed along the velocity. Plain tridents are untouched; add ids to `CUSTOM_TRIDENTS` with their tip distance.

**Seasonal collections (Autumn, Halloween, Woodland, Winter)** are retextured vanilla gear with oversized 3D models built in `resourcepack/art/items/<id>.py` (see Resource Pack). Their classes (`AutumnCollection.kt`, `HalloweenItems.kt`, `WoodlandCollection.kt`, `WinterCollection.kt`) only set the item model and equippable: helmets clear the equipment asset (`equippable.model = null`) so the client draws the 3D model on the head, while boots and elytras point at `equipment/<id>.json` worn layers.

**Issue #776**: the AFK Key (`afk_key`, `item/impl/AfkKey.kt`) was removed — it had no real crate/reward wiring (no crate type named `afk` was ever defined in `crates.yml`, and the default `afk.reward.items` config never referenced it), so it was pure catalog decoration despite the AFKManager dependency note above. The general AFK system (`AFKManager`, `/afk`, the AFK world, AFK rewards) is untouched — only the standalone key item is gone.

**v1.0.5x (issue #770)**: the Enchanted Scroll + Enchanted Dust system was fully removed — scrolls were the only player-facing way to apply a custom enchant, so custom enchants are now admin-applied only, via `/ce add` (`CustomEnchantCommand`). Removed: `CraftingMaterials.kt`'s `SoulFragment`/`AncientRune`/`EnchantedDust` item classes (that file now only defines the empty `CRAFTING_MATERIAL_IDS` set as an extension point), the `enchant_dust_scroll`/`bedrock_breaker_scroll` recipes and all scroll/dust-only recipe plumbing in `CustomRecipes.kt`, `CustomCraftingListener.kt` (deleted — it existed only to resolve those two recipes), the scroll section of `CustomEnchantManager.kt` (`createScroll`/`applyScroll`/`onScrollClick`/etc.), `/ce givescroll`, and all `enchant_scroll_*`/`enchanted_dust`/`soul_fragment`/`ancient_rune` resourcepack assets + their generator scripts (`resourcepack/generate_scroll_textures.{py,js}`, root `generate.gradle.kts`, and `gen_items.py` — the latter deleted outright since those three materials were its only calls). The custom enchant framework itself (PDC storage, anvil combining, grindstone stripping, lore rendering) is untouched — only the scroll delivery mechanism is gone.

## Resource Pack
- Required pack URL/hash in `config.yml > resource-pack`. `ResourcePackManager` enforces on join.
- Source lives in `resourcepack/` (pack format 88 for 26.2). Gradle's `resourcePackZip` task zips `pack.mcmeta` + `assets/` into the JAR on every build — never commit a zip.
- **3D item art is code**: `resourcepack/art/items/<id>.py`, one module per item, built with `art/kit.py` (texture painters, `box`/`bar`/`arc`/`prism`/`turn`/`mirror` geometry with 26.2's free rotations, grip-aligned `display()` presets, worn layers via `save_layer`). Tools, run from `resourcepack/`: `py -m art.check <id>`, `py -m art.render <id>` (preview sheets copying 26.2's hand/head/wing transforms, vanilla item in the other hand for scale), `py -m art.build` (writes textures, models, item definitions and equipment assets). `_example.py` is the reference; modules starting with `_` never ship.
- Wearables: helmets are 3D models on the head (equippable with **no** asset id, so the client draws the item model through its `head` transform); boots and elytras use `equipment/<id>.json` layers (`humanoid`, `wings`). Any chest item whose equipment asset has a `wings` layer renders wings.
- For big art batches, fan out one agent per item (kit + checker + renderer, each agent owns one file), then review everything on one contact sheet. Sets that must match (the 19 crate keys, the 12 eggs, the 77 fish) get a pilot or a shared style guide first, and later agents copy the finished ones. Big runs can hit the account's usage limit: finished modules are safe on disk, and cut-off agents leave drafts, so relaunch them with a "resume from art/items/<id>.py" prompt instead of starting over.
- Custom item models use `setItemModel(NamespacedKey)` — models live in `assets/joshymc/models/item/`.
- **Animated textures**: `save_animation(frames, name, frametime=, interpolate=, order=)` writes a vertical strip plus `.png.mcmeta` (frames square 16/32/64 px, 2-32 of them); `animate()`, `wave()`, `shine()`, `sparkle()` paint seamless loops and `lathe()` turns round solids (eggs, coins, orbs). `art.render` adds an `*_anim.png` sheet (icon + model across one loop, every frame). Item textures animate everywhere (slot, hand, ground, thrown); worn equipment layers can't. `_example_animated.py` is the reference. Every legacy item (eggs, crate keys, fish, void drills, token, sell wand, fast hopper, Bubble Butt Leggings) is an animated art module; the 20 seasonal items are static.
- Kinds beyond the gear: `item` (any object; `main` may be a flat `sprite()`), `leggings` (needs a `humanoid_leggings` layer) and `cake` (a placeable cake built in block space: `main` plus `bite_1`..`bite_7`, picked by custom model data strings; see the Great Pumpkin Pie). Module constants `MODEL_KEY` (item definition path when the game id differs, e.g. fish modules `fish_<id>` build `items/fish/<id>.json` because FishingManager uses `joshymc:fish/<id>`), `EQUIPMENT_KEY` (Bubble Butt keeps its live `bubble_butt` asset) and `COUNTERPART` (the vanilla sprite the preview shows beside it).
- **Logo glyphs** (`py -m art.logo`, source `art/logo/joshymc_logo.webp`): the logo is two 192x128 halves in the default font joined by a `\uF801` -1 space. `\uE001\uF801\uE002` is 36 px tall hanging down from its line (tab header, followed by blank lines); `\uE003\uF801\uE004` is 32 px tall hanging down from the sidebar title bar over three blank rows (`ScoreboardManager.LOGO_ROWS`), so the sidebar background covers the whole logo; the client draws at most 15 sidebar lines, and with those rows the sidebar uses all 15. `\uE000` is the old logo, still used by the spawn hologram. Font glyphs must fit a 256x256 font page and advance `round(width * scale) + 1`, which is why the halves are 192 wide.

## Discord Integration
- JDA 5.2.3 (shaded + relocated)
- Bot connects async; doesn't block server startup
- 2-way chat bridge + join/leave/death/start/stop forwarding
- @everyone/@here escaped automatically
- `/link` ↔ `/unlink` for account linking, persisted via `LinkManager`

## Style conventions
- **Kotlin only**, no Java files.
- **Adventure API** for all text — never legacy `ChatColor`. Use `Component.text("...", NamedTextColor.X)` and `commsManager.parseLegacy("&7...")` when reading from config.
- **Send messages via `commsManager.send(...)`** so the prefix and chat-color rules apply uniformly.
- **NamespacedKey-based PDC** for item identification.
- Managers handle registration; `Joshymc.kt` only initializes them.
- **Lore is simple and unified** via `LoreBuilder` (type, description, usage). Joshy doesn't want backstories or flavor text. The user has explicitly course-corrected on this.
- Shared constants (UNBREAKABLE blocks, `isMineable`) live in `util/BlockUtil`.

## Recurring gotchas (the kind that bite twice)

- **`PlayerInteractAtEntityEvent` fires once per hand** — filter to `EquipmentSlot.HAND` or every interaction-driven action runs twice. (v1.0.48 trade fix.)
- **`PlayerInteractEvent` likewise** — same hand-filter pattern when adding right-click handlers.
- **`BlockRedstoneEvent` is NOT Cancellable** — neutralize via `event.newCurrent = event.oldCurrent`. (v1.0.48 redstone-cross-boundary fix.)
- **Bukkit teams are mutually exclusive** — a player can only be in one team per scoreboard. The collision-toggle (v1.0.46) solves this by maintaining `<base>_y` / `<base>_n` variants of every rank team and reassigning each tick.
- **`PlayerInventory.clear()` does NOT clear armor or off-hand** — must also `setArmorContents(arrayOfNulls(4))` + `setItemInOffHand(null)` for combat-log de-dupe. (v1.0.44 fix.)
- **`event.inventory` in `InventoryClickEvent` is always the top inventory** — to know where the player actually clicked, use `event.clickedInventory`. (v1.0.48 GuiManager fix.)
- **Bukkit chest GUI title overflows visually** — keep titles under ~28 chars. Don't append `(Page X/Y)` when there's only one page. (v1.0.48 spawner fix.)
- **`GameRule.DO_TRADER_SPAWNING`** and the other old game-rule constants are deprecated (still work in 26.2).
- **26.2 equipment getters are non-null** (`getHelmet()` returns an empty stack, not null), so Kotlin can't assign them as properties: use `setHelmet(...)` etc.
- **GameRule import for `World.setGameRule`** — `org.bukkit.GameRule.DO_TRADER_SPAWNING`.
- **`world.getCanGenerateStructures` is read-only on most Paper builds** — `Joshymc.disableStructureGeneration` tries a Bukkit setter, then an NMS WorldOptions reflection swap, then logs a manual instruction. On 26.2 the reflection path fails (`worldGenOptions()` is gone), so it falls through to the manual instruction.
- **Generate-structures only takes effect on NEW chunks**; existing chunks keep their structure-gen settings. Worth saying so when the user asks why an old world still spawns villages.
- **Polygon point-in-polygon uses ray-casting**; sub-block edge crossings can be missed if you compare blockX/blockZ. Use raw `from.x == to.x` checks for inside/outside transitions.
- **Mob visibility = 3 damage paths** (direct, projectile, explosion). Closing only the first leaves creepers exploding through invisible walls.

## Course corrections from the user (worth remembering)
- *"I didn't tell you to add them, just asked"* — answer questions before implementing. Don't jump straight to code if the user asked an open question.
- *"We want the AC to work with our admin panel"* — when the user mentions a 3rd-party plugin, prefer **integration** over coexistence. (Led to the Grim → JoshyMC bridge.)
- Lore should stay simple — no edgy backstories.
- The user prefers **bundled fixes per release** over a flurry of small commits. When mid-edit they often interrupt with *"also do X"* — fold X into the same release.
- The user names the friends in the loop: **Joshy** (server owner) and **Blu3_B3rry_** (Sr Mod). Their feedback comes via Discord screenshots.

## Where we left off (v1.0.48 — last release before Mac handoff)
- All known feedback from Joshy/Blu3 in the recent batch is shipped.
- Open question still on the table: **"drop all"** in the spawner storage GUI — left alone because **Withdraw All** already drops everything to the player's inventory. If they actually wanted a "dump to floor" / "trash all" variant, add it as a separate button (slot 51 in the new 54-slot layout would be a good home; slots 45/47/48/49/50/53 are taken).
- The compileKotlin warning at `SpawnerManager.kt:401` ("Condition is always 'true'") is benign — leftover from the storage-amount check inside `tickHoppers`. Not blocking anything.
- Sidebar/chat/admin-panel UX has already gone through several rounds of polish; if the user asks for "another pass" expect cosmetic tweaks not architecture changes.

## Repo state
- `main` branch, clean working tree at v1.0.48 (`3629d00`).
- All commits pushed; tag `v1.0.48` pushed; release published with JAR attached.
- `jar/joshymc-1.0.48.jar` exists at repo root — gitignored.
