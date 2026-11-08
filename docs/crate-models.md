# Custom crate chest models (issue #1389)

Survival crates can show their own chest model instead of a plain vanilla chest. The crates
redesign (issue #1407, audit #1446) names its models `<crate id>_chest`: Harvest and
Stockpile (issue #1447), then Homestead, Campfire and Cabin (issue #1454), which replace
the older `homestead_crate`, `campfire_crate` and `cabin_crate` models. The older models stay
in the pack for rollback. Lifesteal is prepared for but not enabled.

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

The crate block is never replaced, moved or edited, and it stays the crate's identity (the
`crate_locations` row). `crate/CrateModelDisplays` draws an `ItemDisplay` over it. The
display itself has no hitbox. Models that reach past their block also get an `Interaction`
hitbox (see below). Every click, on the block or on a hitbox, goes through the same
`CrateManager` code. Keys, previews, animations, rewards, permissions and the anti-dupe guards
are untouched.

| Concern | How it's handled |
|---|---|
| Hiding the chest | Each model encloses a vanilla chest (x/z 1..15, y 0..14, latch to z 16) and stays inside its block (x/z 0..16), so neighbouring crates never overlap. |
| Facing | `rotation: auto` turns the model's front (its lock) to the chest's `facing`. Blocks without a facing face south. |
| Restarts and reloads | Displays and hitboxes are **non-persistent**, so they are never saved with the chunk. They are spawned once every chunk they stand in is loaded (and at startup), and removed when any of those chunks unloads, on `/joshymc reload` and at shutdown. Before every spawn, any entity tagged with that location is removed. Any tagged entity that loads from disk anyway is removed in `EntitiesLoadEvent`. The result is one display (and at most one hitbox) per crate, never duplicates. |
| Chunk borders | A model offset across a chunk border is drawn only while both chunks are loaded and removed when either unloads (`CrateModels.requiredChunks`). Chunks are never loaded for a model. |
| Moving or removing crates | `/crate setlocation` and `/crate removelocation`, and deleting a crate in the editor, redraw or remove that location's display and hitbox right away. `/crate models refresh` redraws everything. |
| Breaking and theft | The display and hitbox can't be damaged (any `EntityDamageEvent` on them is cancelled), picked up or looted, and drop nothing. The crate block keeps its existing break protection. |
| Cleanup scope | Only entities with the `joshymc_crate_model` tag **and** JoshyMC's `crate_model` location key are ever removed. |
| Performance | There is no repeating task. Everything is driven by events (chunk load/unload, entity load, resource-pack status, crate location changes). |
| Lighting | Inside a full block (barrel, etc.) the display takes the light level of the block above, so it isn't drawn black. |

### Hitboxes for oversized models (issue #1451)

- At startup the plugin reads each configured model's elements from the bundled pack (after
  element rotations, `CrateModelBounds`) and works out the box the model visibly fills from
  its scale, offset and rotation, the same way Paper draws the display (`CrateModels.placement`).
- `hitbox: auto` (the default) adds an `Interaction` entity only when that box reaches more
  than 1/4 block (`OVERSIZE_TOLERANCE`) past the crate block. Small lips and latches don't
  count. A model that fits keeps plain block clicks with no extra entity. `hitbox: on` always
  adds one, and `hitbox: off` never does.
- The hitbox is the smallest square-footed box around the model (Interaction boxes are
  square), standing on the model's floor and as tall as the model.
- With the bundled models at scale 1, Harvest and the Cabin Chest fit their block (no
  hitbox). Homestead (crown), Campfire (horns) and Stockpile (crowned gem) reach 1/4 to 1/2
  block above it, so they get one. The legacy `_crate` models also get one. Clicking those tops now reaches the crate instead of missing it.
- Right-click (`PlayerInteractEntityEvent`, main hand only) and left-click (Paper's
  `PrePlayerAttackEntityEvent`; attacking an Interaction entity fires no damage event) are
  cancelled and passed to `CrateManager.handleModelClick`. That method resolves the crate
  from the **block** at the registered location and runs the same `handleCrateClick` as a
  block click: preview on left-click, key check, sneak preview or mass-open, free slot, crate
  validity, activation debounce, then the normal opening. There is no second opening, key or
  reward path. Like the block handler it runs at HIGHEST and doesn't skip cancelled events,
  so the hitbox behaves exactly like the block it stands for.
- A hitbox click only counts from the hitbox this server instance placed, for a crate
  location that still exists, from a non-spectator who can see the model, in the same world
  and within 6 blocks (`MAX_REACH`).
- Double actions: one click reaches either the block or the hitbox, never both. On top of
  that, `CrateClickGate` lets each player run one left-click and one right-click crate action
  per tick, whichever route it came through. The "interact at" half of an entity right-click
  is cancelled and ignored. A held item's use in the same tick as a hitbox right-click is
  denied, as the block route already does. Keyed opens keep the existing activation debounce,
  so a key is never spent twice.

## Positioning

- The display stands at the block centre plus `offset` (world axes). The model is scaled
  about it and lifted by `(scale - 1) / 2`, so its floor stays on the block floor.
- `rotation` turns the model's front (+z) to the chest's facing (`auto`) or to a fixed
  direction. Item displays draw their item half a turn round, and the yaw accounts for that.
- A model whose visible box wouldn't touch its crate block at all (for example a small scale
  pushed away by a big offset) is refused. That crate keeps the vanilla block, and
  `/crate models` reports the location.

## C. Resource pack integration

- The models are ordinary JoshyMC item models in the existing pack (`resourcepack/`, which
  is zipped into the JAR, served by `ResourcePackManager` and required on join). Each
  display holds a `CHEST` item whose `item_model` is `joshymc:<id>_crate`.
- At startup the plugin reads the bundled `resourcepack.zip`. A configured model that isn't
  in the pack is logged and that crate keeps its vanilla look, so nobody gets a
  missing-texture cube.
- Displays and hitboxes are hidden by default. A player is only shown them once
  `PlayerResourcePackStatusEvent` reports `SUCCESSFULLY_LOADED` for JoshyMC's pack id. If
  the pack is declined, fails or is discarded, both are hidden again and that player sees
  and clicks the normal crate block. A hidden hitbox isn't sent to that client, so it can't
  get in the way of their clicks.

## D. Assets

**Included in this PR (finished, not placeholders):**

| Crate | Art module | Built files |
|---|---|---|
| Cabin | `resourcepack/art/items/cabin_crate.py` | `assets/joshymc/items/cabin_crate.json`, `models/item/cabin_crate/`, `textures/item/cabin_crate/` |
| Homestead | `resourcepack/art/items/homestead_crate.py` | same layout, `homestead_crate` |
| Campfire | `resourcepack/art/items/campfire_crate.py` | same layout, `campfire_crate` |
| Harvest (#1447) | `resourcepack/art/items/harvest_chest.py` | same layout, `harvest_chest` |
| Stockpile (#1447) | `resourcepack/art/items/stockpile_chest.py` | same layout, `stockpile_chest` |
| Homestead (#1454) | `resourcepack/art/items/homestead_chest.py` | same layout, `homestead_chest` |
| Campfire (#1454) | `resourcepack/art/items/campfire_chest.py` | same layout, `campfire_chest` |
| Cabin (#1454) | `resourcepack/art/items/cabin_chest.py` | same layout, `cabin_chest` |

The first three rows (`*_crate`) are the original models. Since issue #1454 the bundled
config no longer uses them, but they are still built so a server can roll back.

**Harvest Chest** (`joshymc:harvest_chest`, crate id `harvest`): a rustic treasure chest
with a warm brown plank body under a barrel-vaulted lid of curved boards. It has pewter-silver
straps over the vault, arch rims on both ends, heavy corner caps, L brackets, and barrel hinges
at the back. A silver lock with a lamp-lit keyhole sits on the front. The emblem is a silver
medallion with a tied wheat sheaf on a green field, ringed by a wheat-and-leaf wreath. Crossed
wheat sprigs with a red bow are on both side walls. Animated: rim glint, wheat ripple, keyhole.

**Stockpile Chest** (`joshymc:stockpile_chest`, crate id `stockpile`): a refined gem
strongbox. The deep blue enamel body has diamond-lattice panels inside raised silver frames,
each set with a small sapphire. Heavy silver pillars stand on the body corners, and posts
with stepped caps on the lid corners. The stepped, chamfered strongbox lid is crowned by a
large cut sapphire in a clawed bezel. The emblem and lock are one piece: a silver diamond
plate with a faceted sapphire and an icy keyhole. Barrel hinges hold the lid, and pale blue
crystal clusters grow from the back corners. Animated: sapphire twinkle, crystal pulse,
keyhole. It shares no textures with Harvest, and its silhouette differs (flat stepped lid and
pillars vs. vault and straps).

**Homestead Chest** (`joshymc:homestead_chest`, crate id `homestead`, #1454): a royal
purple and gold chest.
- Body: purple lacquer with a damask of fleurs, raised gold panel frames (gold fleurs-de-lis
  on the front, jewelled rosettes on the sides), heavy gold corner brackets, and a layered
  gold plinth with corner blocks.
- Lid: a reliquary lid with four quilted velvet slopes, gold hip ribs and finials, and a gold
  crown (pearl and amethyst points, an orb and cross) on a tasselled cushion.
- Front: the royal crest (a purple shield with a raised gold fleur-de-lis) hangs from the lid
  as the hasp of a large gold lock with scroll flanges, a pendant and a glowing keyhole.
- Animated: crown and crest glint, amethyst twinkle, keyhole.

**Campfire Chest** (`joshymc:campfire_chest`, crate id `campfire`, #1454): a black and
crimson fire-forged war chest.
- Body: charred planks split by glowing ember cracks, riveted iron armour with teeth on every
  corner, a crimson band, and an iron plinth.
- Side walls: forge doors, each a crimson-bordered iron plate with a glowing vent grate.
- Back: full-length strap hinges and a burnt flame brand.
- Lid: two stepped iron layers under a forge seal with an ember heart. Two great crescent
  horns rise from the back corners, with spikes on the front corners and side edges.
- Front: a forged three-tongued flame with living fire inside drops the hasp into a black
  iron lock with fangs, side spikes, a pull ring and an ember keyhole.
- Animated: fire, cracks, horn tips, seal, keyhole.

**Cabin Chest** (`joshymc:cabin_chest`, crate id `cabin`, #1454): a pink and mint cottage
chest.
- Body: soft pink clapboard with mint corner boards, a mint plinth and mint-framed panels
  (rose enamel hearts on the front, flower boxes on the sides).
- Lid: a low cottage-roof lid of mint fish-scale shingles in overlapping courses, with a
  cream ridge cap and mint heart finials, cream bargeboards, a heart vent on each gable end,
  and a scalloped mint valance.
- Front: the emblem is a flower wreath round a pink enamel heart. A mint hasp drops from it
  onto a mint lock with a large enamel heart and a warm keyhole.
- Animated: flowers nod, enamel glint, keyhole.

None of the three keeps its old theme: no barn, log campfire or snowy log cabin.

All models are built on the shared skeleton `art/items/_crate_chest.py` (same footprint, lid
seam, lock and hinges) and have animated textures: window and lantern flicker, ember
cracks, flames, coals and the keyhole glow.

**Adding a model for another crate:**
1. Copy one of the modules to `art/items/<name>.py` and keep the shared skeleton
   (the redesign names them `<crate id>_chest`; Harvest and Stockpile are the quality
   reference for the remaining models). Add the id to `CRATES` in
   `art/tests/test_crate_chests.py`.
2. Run `python3 -m art.check <id>_crate`, `python3 -m art.render <id>_crate` and
   `python3 -m art.build <id>_crate`.
3. Add a `crates.custom-models.models.<crate id>` entry. A `_chest` model needs an
   explicit `model:` (the default is `joshymc:<crate id>_crate`).

`art/tests/test_crate_chests.py` checks the chest-cover and inside-the-block rules.

## E. Configuration (`config.yml > crates.custom-models`)

```yaml
crates:
  custom-models:
    enabled: true            # master switch
    gamemodes: [survival]    # add lifesteal here once Lifesteal has crates
    models:
      harvest:               # crate id from crates.yml
        model: "joshymc:harvest_chest"
      stockpile:
        model: "joshymc:stockpile_chest"
      cabin:
        enabled: true
        model: "joshymc:cabin_chest"   # "joshymc:cabin_crate" = the original model (rollback)
        offset: {x: 0.0, y: 0.0, z: 0.0}   # blocks, -1..1, world axes
        rotation: auto       # or degrees the front faces: 0 south, 90 west, 180 north, 270 east
        scale: 1.0           # 0.5..2.0; the floor stays on the floor
        hitbox: auto         # auto = only when the model reaches 1/4 block past its block; on; off
```

- A missing `model` defaults to `joshymc:<crate id>_crate`.
- Issue #1454: on the first start with this version, an existing config whose Cabin,
  Homestead or Campfire `model` is still the untouched old default (`joshymc:<id>_crate`)
  is switched to `joshymc:<id>_chest`. Any other value is left alone. This happens once:
  the marker `crates.custom-models.chest-models-migrated: true` is then written, so setting
  the old key again rolls back for good.
- An invalid value is logged and only that crate falls back to the vanilla block.
  Nothing is guessed.
- The default entries are backfilled into existing configs. To turn one off, set
  `enabled: false` rather than deleting the entry.
- Animation is texture-based and always plays. No entity animation runs, so there are no
  tasks.
- `/crate models` (`joshymc.crate.admin`) shows:
  - the number of crate locations and expected models, and how many aren't drawn yet
    (chunk or world not loaded, or no block);
  - the number of spawned displays and interaction hitboxes;
  - every tagged crate model entity in loaded chunks, with a warning when some are untracked
    (`/crate models refresh` removes them);
  - per model: status, locations shown, hitbox mode and how many were placed, scale,
    rotation and model height;
  - crates with locations but no model entry, locations refused for their placement, and
    config problems.

## F. Java and Bedrock

- **Java (Paper 26.2):** Java players see the models once the required JoshyMC pack has
  loaded.
- **Bedrock (Geyser/Floodgate):** the displays and hitboxes are never shown to Floodgate
  players (`BedrockUtil.isBedrockPlayer`). They see and use the real crate block, with keys,
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
  (never persistent, hidden by default, no block changes, no repeating task). Issue #1451
  added: `hitbox` parsing; placement boxes for every facing, fixed rotations, scales and
  offsets; refusing models drawn beside their block; hitbox size and position; the bundled
  models' real bounds (which ones get a hitbox, and that it covers the block); chunk-border
  rules; reach; reading model JSON (rotated and Euler elements); the click gate; and that
  hitbox clicks only call `handleModelClick`, which reuses `handleCrateClick` and spends no
  key itself.
- `python3 -m unittest discover -s art/tests -t .` (from `resourcepack/`) covers chest
  cover, inside-the-block, floor, glow-on-accents, built files, and that the config points
  at built models.

Manual (needs a test server with the pack and real crates):
1. Cabin, Homestead and Campfire crates show their new `_chest` models with the lock on
   the chest's front. Check north, east, south and west facings.
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
8. Hitboxes (issue #1451): with F3+B, Homestead/Campfire/Stockpile show a white box
   around the model (Harvest and Cabin show none). Left-click the roof/crown: preview. Right-click it
   with a key: one open and one key spent, also when spam-clicking, and also with sneak
   (mass-open). Without a key: the "You need a … Key" message.
9. Set `scale: 1.5` on one crate and `/joshymc reload`: the hitbox grows with the model and
   still stands on the floor. Set `offset.x: 1.0` on a crate at a chunk edge: the model
   appears once both chunks are loaded and goes away when either unloads.
10. Fly away until the chunks unload, come back, and restart: `/crate models` shows the same
    display and hitbox counts with no untracked entities.
11. Join without the pack, or on Bedrock: no hitbox, and clicks on the block work as before.

## Files

- `src/main/kotlin/com/liam/joshymc/crate/CrateModels.kt`: Bukkit-free config and rules.
- `src/main/kotlin/com/liam/joshymc/crate/CrateModelDisplays.kt`: displays, hitboxes,
  chunk, pack-status and hitbox click events.
- `src/main/kotlin/com/liam/joshymc/crate/CrateModelBounds.kt`: Bukkit-free model JSON bounds.
- `src/main/kotlin/com/liam/joshymc/crate/CrateClickGate.kt`: Bukkit-free one action per tick.
- `CrateManager` owns `models`, starts and stops it, redraws on location changes, and routes
  hitbox clicks through `handleModelClick`.
  `ListenerManager` registers it. `ResourcePackManager.packId` exposes the pack id.
  `/crate models [refresh]` is in `CrateCommand`.
