# Custom crate chest models (issue #1389)

Survival crates can show their own chest model instead of a plain vanilla chest. Cabin,
Homestead and Campfire ship with finished models. Lifesteal is prepared for but not
enabled.

## A. Existing crate system (unchanged)

- Crates are defined in `plugins/Joshymc/crates.yml` (ids, keys, rewards, weights, modes,
  animations, particles) and placed with `/crate setlocation <type>`. Placements are rows in
  the SQLite table `crate_locations` (world, x, y, z, crate_type).
- `CrateManager` handles every interaction on the **block** at a registered location:
  - right-click with the key opens the crate;
  - left-click or sneak + right-click without a key opens the preview;
  - sneak + right-click with a key mass-opens.
  
  It also handles key checks, pending-reward safety, animations and the block-break guard.
- Crate ids are not display names. The three Survival crates covered here are `cabin`,
  `homestead` and `campfire`: `QuestCycleManager` hands out keys under those ids, and the
  key items are `cabin_key`, `homestead_key` and `campfire_key`. The bundled `crates.yml`
  only has example crates (common, rare, joshy), so the live definitions are in the
  server's own `crates.yml`. Run `/crate list` on the server to confirm the ids match.

## B. How the models are shown

The crate block is never replaced, moved or edited. `crate/CrateModelDisplays` draws an
`ItemDisplay` over it. The display has no hitbox, so every click still hits the real block
and goes through exactly the same `CrateManager` code. Keys, previews, animations, rewards,
permissions and the anti-dupe guards are untouched.

| Concern | How it's handled |
|---|---|
| Hiding the chest | Each model encloses a vanilla chest (x/z 1..15, y 0..14, latch to z 16) and stays inside its block (x/z 0..16), so neighbouring crates never overlap. |
| Facing | `rotation: auto` turns the model's front (its lock) to the chest's `facing`. Blocks without a facing face south. |
| Restarts and reloads | Displays are **non-persistent**, so they are never saved with the chunk. They are spawned when a crate's chunk loads (and at startup) and removed when it unloads, on `/joshymc reload` and at shutdown. Before every spawn, any display tagged with that location is removed. Any tagged display that loads from disk anyway is removed in `EntitiesLoadEvent`. The result is one display per crate, never duplicates. |
| Moving or removing crates | `/crate setlocation` and `/crate removelocation`, and deleting a crate in the editor, redraw or remove that location's display right away. `/crate models refresh` redraws everything. |
| Breaking and theft | The display can't be hit, damaged, picked up or looted and drops nothing. The crate block keeps its existing break protection. |
| Performance | There is no repeating task. Everything is driven by events (chunk load/unload, entity load, resource-pack status, crate location changes). |
| Lighting | Inside a full block (barrel, etc.) the display takes the light level of the block above, so it isn't drawn black. |

## C. Resource pack integration

- The models are ordinary JoshyMC item models in the existing pack (`resourcepack/`, which
  is zipped into the JAR, served by `ResourcePackManager` and required on join). Each
  display holds a `CHEST` item whose `item_model` is `joshymc:<id>_crate`.
- At startup the plugin reads the bundled `resourcepack.zip`. A configured model that isn't
  in the pack is logged and that crate keeps its vanilla look, so nobody gets a
  missing-texture cube.
- Displays are hidden by default. A player is only shown them once
  `PlayerResourcePackStatusEvent` reports `SUCCESSFULLY_LOADED` for JoshyMC's pack id. If
  the pack is declined, fails or is discarded, the displays are hidden again and that
  player sees the normal crate block.

## D. Assets

**Included in this PR (finished, not placeholders):**

| Crate | Art module | Built files |
|---|---|---|
| Cabin | `resourcepack/art/items/cabin_crate.py` | `assets/joshymc/items/cabin_crate.json`, `models/item/cabin_crate/`, `textures/item/cabin_crate/` |
| Homestead | `resourcepack/art/items/homestead_crate.py` | same layout, `homestead_crate` |
| Campfire | `resourcepack/art/items/campfire_crate.py` | same layout, `campfire_crate` |

All three are built on the shared skeleton `art/items/_crate_chest.py` (same footprint, lid
seam, lock and hinges) and have animated textures: window and lantern flicker, ember
cracks, flames, coals and the keyhole glow.

**Adding a model for another crate:**
1. Copy one of the modules to `art/items/<id>_crate.py` and keep the shared skeleton.
2. Run `python3 -m art.check <id>_crate`, `python3 -m art.render <id>_crate` and
   `python3 -m art.build <id>_crate`.
3. Add a `crates.custom-models.models.<crate id>` entry.

`art/tests/test_crate_chests.py` checks the chest-cover and inside-the-block rules.

## E. Configuration (`config.yml > crates.custom-models`)

```yaml
crates:
  custom-models:
    enabled: true            # master switch
    gamemodes: [survival]    # add lifesteal here once Lifesteal has crates
    models:
      cabin:                 # crate id from crates.yml
        enabled: true
        model: "joshymc:cabin_crate"
        offset: {x: 0.0, y: 0.0, z: 0.0}   # blocks, -1..1, world axes
        rotation: auto       # or degrees the front faces: 0 south, 90 west, 180 north, 270 east
        scale: 1.0           # 0.5..2.0; the floor stays on the floor
```

- A missing `model` defaults to `joshymc:<crate id>_crate`.
- An invalid value is logged and only that crate falls back to the vanilla block.
  Nothing is guessed.
- The default entries are backfilled into existing configs. To turn one off, set
  `enabled: false` rather than deleting the entry.
- Animation is texture-based and always plays. No entity animation runs, so there are no
  tasks.
- `/crate models` (`joshymc.crate.admin`) shows each entry's status and how many
  locations are drawn.

## F. Java and Bedrock

- **Java (Paper 26.2):** Java players see the models once the required JoshyMC pack has
  loaded.
- **Bedrock (Geyser/Floodgate):** the displays are never shown to Floodgate players
  (`BedrockUtil.isBedrockPlayer`). They see and use the real crate block, with keys,
  previews and rewards unchanged. Geyser doesn't render Java item models on display
  entities, and the Bedrock pack built by `BedrockPackManager` only maps items players can
  hold, so **custom crate models on Bedrock are not supported**. Supporting them would
  need Bedrock entity/attachable geometry or custom blocks through Geyser, which is
  outside this change. No extra Bedrock pack setup is needed for crates to keep working.
  `art.bedrock` still writes inventory icons for the three models (it renders every
  item definition), but they aren't used.

## G. Testing

Automated:
- `./gradlew -I scripts/crate-model-check.init.gradle.kts crateModelCheck` covers config
  parsing and rejection, rotation, scaling, visibility rules, bundled defaults and wiring
  (never persistent, hidden by default, no hitbox, no block changes, no repeating task).
- `python3 -m unittest discover -s art/tests -t .` (from `resourcepack/`) covers chest
  cover, inside-the-block, floor, glow-on-accents, built files, and that the config points
  at built models.

Manual (needs a test server with the pack and real crates):
1. Cabin, Homestead and Campfire crates show their models with the lock on the chest's
   front. Check north, east, south and west facings.
2. Right-click with the key opens the crate. Without a key you get the "You need a … Key"
   message. Left-click and sneak + right-click preview. Sneak + right-click with keys
   mass-opens. Animations play and rewards arrive as before.
3. Restart the server and run `/joshymc reload`: each crate still has exactly one display.
   `/execute if entity @e[type=item_display,tag=joshymc_crate_model]` should report the
   same count as `/crate models`.
4. Move a crate with `/crate removelocation` + `/crate setlocation <type>`: the model
   follows and the old spot is clean.
5. Decline the pack (or join with packs disabled): you see plain chests that still work.
6. Join on Bedrock: you see plain chests, and opening, preview and rewards work.
7. On a Lifesteal server or world: no models (Survival only).

## Files

- `src/main/kotlin/com/liam/joshymc/crate/CrateModels.kt`: Bukkit-free config and rules.
- `src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt`: displays, chunk and
  pack-status events.
- `CrateManager` owns `models`, starts and stops it, and redraws on location changes.
  `ListenerManager` registers it. `ResourcePackManager.packId` exposes the pack id.
  `/crate models [refresh]` is in `CrateCommand`.
