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

The crate block is never replaced or moved, and it stays the crate's identity (the
`crate_locations` row). The only edit ever made to it is turning its facing on
`/crate rotate` (see E3). `crate/CrateModelDisplays` draws an `ItemDisplay` over it. The
display itself has no hitbox. Models that reach past their block also get an `Interaction`
hitbox (see below). Every click, on the block or on a hitbox, goes through the same
`CrateManager` code. Keys, previews, animations, rewards, permissions and the anti-dupe guards
are untouched.

| Concern | How it's handled |
|---|---|
| Hiding the chest | Each model encloses a vanilla chest (x/z 1..15, y 0..14, latch to z 16) and stays inside its block (x/z 0..16), so neighbouring crates never overlap. |
| Facing | A location's own facing (`/crate rotate`, issue #1472) wins. Otherwise `rotation: auto` turns the model's front (its lock) to the chest's `facing`, and blocks without a facing face south. |
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

## E2. Monthly model rotation (issue #1460)

Part of the crates redesign (issue #1407, audit #1446). Rotation lets chosen crates show a
different model each calendar month. The 12 monthly models are `january_chest` …
`december_chest`. **January, February and March (issue #1464), April, May and June (issue
#1482) and July, August and September (issue #1495) are in the pack**; October to December are
made in later phases and fall back until then (see below). The bundled config
lists no rotating crates, so nothing changes in game until the Owner lists some.

| Month | Model | Look |
|---|---|---|
| January | `joshymc:january_chest` | frozen winter: frosted ice-brick walls, rime-crusted silver frame, snow-buried lid with heaped corner caps, icicles on every side, a blue crystal cluster on top, a silver snowflake on an ice medallion |
| February | `joshymc:february_chest` | Valentine's gift box: red lacquer with embossed hearts, a pink satin ribbon round every wall, a big pink bow on a quilted pillow, rose gold frame, roses on the corners, a pink enamel heart emblem and heart-framed lock |
| March | `joshymc:march_chest` | lucky spring: mint-green boards, gold bands, a grassy lid with tufts, flowers and gold coins, a large four-leaf clover on a gold stem, gold horseshoes on the sides, ivy on the front corners, raindrops on the lid edge, a clover medallion |
| April | `joshymc:april_chest` | spring showers: light lavender boards, pink corner posts, a soft light blue barrel-vault lid with pewter straps, an open pink and lavender umbrella standing in it, a rain cloud emblem dropping glowing raindrops over a raindrop keyhole, cherry blossom sprigs, raindrops hanging off the lid |
| May | `joshymc:may_chest` | blooming garden: cream boards, leaf green corner posts, a green trellis with a flowering vine on each side, a picket-fenced flower bed lid with two tall sunflowers, tulips, daisies, buttercups, hedges and two butterflies, a pink flower emblem on a leaf wreath, leaf-shaped hinges |
| June | `joshymc:june_chest` | sunny summer: turquoise boards on a rolling surf band, sand gold rope trim and corners, a sandy lid with a tipped straw hat, a palm sprig, shells, a starfish and a pearl, a coral life ring and a starfish on the sides, a glowing sun emblem, a gold-framed lock with a turquoise keyhole and a scallop shell |
| July | `joshymc:july_chest` | summer fireworks: star-sprinkled deep navy boards, red corner posts with gold bands and caps, a red and warm white pennant garland round the lid, a stepped three-tier lid with a launch rack of three rockets under a glowing starburst, rockets clamped to both side walls with glowing fuses, a firework burst emblem in red, gold, white and cyan, a gold five-point star lock with a cyan keyhole |
| August | `joshymc:august_chest` | golden summer sunset: sandy beige boards in warm amber framing with stepped corner plates and sun rivets, a low rounded vault lid banded coral, orange, amber and gold, a glowing half sun on the horizon in the vault's front arch with layered rays and a gold-to-dusk sky, a round amber sun lock with an ember keyhole, wheat sheaves with coral ribbons and flowers on the side walls, ray-fan hinges |
| September | `joshymc:september_chest` | early autumn: dark oak boards in burnished copper with corner brackets and rivets, a flat lid buried in leaf litter and loose maple leaves spilling over the eaves, two ribbed pumpkins and acorns in the leaves, a standing golden wheat bundle, a layered maple leaf emblem in a copper ring, an acorn lock with an amber keyhole, copper leaf hinges |

All nine sit on the shared skeleton `art/items/_crate_chest.py` (they hide a vanilla chest
and reach at most 1/4 block past their block, so `hitbox: auto` adds no hitbox) and are
checked by `art/tests/test_crate_chests.py`.

### Configuration

```yaml
crates:
  custom-models:
    enabled: true
    models:
      harvest:
        model: "joshymc:harvest_chest"   # the static model, also the monthly fallback
        scale: 1.0                       # offset / rotation / scale / hitbox apply to every month
    monthly:
      enabled: true                # rotation switch (the custom-models master switch still wins)
      timezone: America/New_York   # any IANA zone id: UTC, Europe/London, America/Los_Angeles ...
      crates: [harvest]            # crate ids (aliases work) that rotate; empty = nothing rotates
      models:                      # january .. december, each an item model key
        january: "joshymc:january_chest"
        february: "joshymc:february_chest"
        # ... one line per month, up to december
```

- Month ids are the 12 English month names, lower case: `january`, `february`, `march`,
  `april`, `may`, `june`, `july`, `august`, `september`, `october`, `november`, `december`.
  Abbreviations are not accepted.
- The bundled config maps all 12 months to `joshymc:<month>_chest` and lists **no crates**:
  the plugin doesn't know which live crates should rotate, so the Owner chooses. Crates not in
  `monthly.crates` behave exactly as before (their static `models.<id>` entry).
- A rotating crate keeps its own `models.<id>` placement (offset, rotation, scale, hitbox) for
  every month; without an entry it uses the defaults (no offset, auto rotation, scale 1, auto
  hitbox). `models.<id>.enabled: false` turns the crate's model off in every month.
- Turning rotation off: `monthly.enabled: false` (or an empty `crates` list), then
  `/joshymc reload`. Rotating crates go back to their static model.
- Invalid values are logged and never guessed: an unknown month key is ignored, a bad model
  key leaves that month unset (it falls back), a bad timezone or a non-boolean `enabled` turns
  rotation off (static models keep working), and a non-list `crates` rotates nothing.

### How rotation works

- The month is read from the wall clock in `monthly.timezone` (default America/New_York),
  never stored, so a restart or `/joshymc reload` always starts with the right month, also
  when the month changed while the server was down.
- While rotation is active there is **one** delayed check (never one per crate, no repeating
  task): it fires just after the next month start (computed in the timezone, so DST and the
  December → January year change are handled) and at most an hour after the previous check,
  so tick lag or a changed system clock can't make it late by more than that.
- When the month hasn't changed the check does nothing. When it has, only rotating crates
  whose drawn model differs from the new one are redrawn, through the same `refresh` that
  `/crate setlocation` uses (old display and hitbox removed first, so no duplicates). Crates
  in unloaded chunks are drawn with the new month's model when their chunks load; chunks are
  never loaded for this.
- Hitbox bounds are measured for the monthly models too, so a taller monthly model gets its
  hitbox like a static one.
- Only the display's item model changes. Crate ids, keys, rewards, probabilities, animations,
  GUIs, locations (`crate_locations`) and player data are never touched.

### Missing models and fallbacks

For a rotating crate, in order:
1. this month's model, if it is set and in the bundled resource pack;
2. otherwise the crate's static `models.<id>.model`, if it is in the pack;
3. otherwise the vanilla crate block.

A key that isn't in the bundled pack is never spawned. At startup one warning lists every
month whose model is missing (not one per month or crate), plus any listed crate id that
doesn't exist; a month change logs one line. Visibility rules are unchanged: Bedrock players
and players without the JoshyMC pack always see the vanilla block.

### Commands (`joshymc.crate.admin`, op)

- `/crate models`: the existing status, now with the monthly summary when rotation is set up.
- `/crate models monthly`: on/off, timezone, current month and its model, when the next month
  starts, all 12 months with "ok" / "not in the pack" / "not set" (`>` marks the current one),
  and per rotating crate what it shows now (this month's model, static fallback and why, or
  the vanilla block and why).
- `/crate models refresh`: redraw everything (unchanged).
- `/crate models set <crate> <model>`: sets that crate's static
  `crates.custom-models.models.<crate>.model`. The crate must exist (aliases resolve to their
  crate) and the model must be in the bundled pack (`cabin_chest` is read as
  `joshymc:cabin_chest`; tab completion lists the bundled `*_chest` / `*_crate` models).
  config.yml is backed up to `backups/` and saved, then the models are redrawn. Nothing else
  changes. For a rotating crate this is the fallback model. Note: saving writes the loaded
  config, so reload first if config.yml was edited by hand since the last reload. Monthly
  mappings and the crate list are edited in config.yml (then `/joshymc reload`).

### Verifying

- `/crate models monthly` shows the active month, its model and each crate's source.
- Manual (test server): list a crate under `monthly.crates`, map the current month to a model
  that is in the pack (e.g. `joshymc:stockpile_chest`) and reload: that crate shows it. Map it
  to a missing key and reload: the static model shows, and the startup log warns once.
  Change the server clock (or `timezone`) across a month start: within the hour the crate is
  redrawn once, and `/execute if entity @e[type=item_display,tag=joshymc_crate_model]` still
  matches `/crate models`.

### Limitations

- Only January to September exist yet (issues #1464, #1482, #1495); October to December
  fall back until their later phases.
- One month → model map is shared by every rotating crate (no per-crate monthly models).
- Rotation is per crate id, so every location of a listed crate rotates together.
- `/crate models set` only edits static models; monthly mappings are edited in config.yml.

## E3. Per-crate facing (issue #1472)

Part of the crates redesign (audit #1446).

**Why admins couldn't turn single crates before:** the existing rotation rules worked, but
they only existed per crate *type*. `crates.custom-models.models.<id>.rotation` is one value
for every crate of that id, and `auto` follows the crate block's own `facing`, which is set
when the chest is placed. So turning one crate meant breaking and re-placing its block (and
re-registering it), and a fixed `rotation` turned every crate of the type at once.

### Commands (`joshymc.crate.admin`, op)

Look at the crate block (within 5 blocks, like `/crate setlocation`):

- `/crate rotate north|south|east|west` (also `n`/`s`/`e`/`w`): this crate's front (its lock)
  faces that way.
- `/crate rotate next`: one step on from the direction it shows now.
- `/crate rotate auto`: clears this crate's facing. It follows the crate type's `rotation`
  again (with `auto`, the block's facing).
- `/crate rotate mode [on|off]`: quick rotation (below). No argument toggles it.

The target must be a registered crate location exactly. A neighbour that only reaches the
crate through the double-chest fallback is refused with a hint, and any other block gets
"isn't a registered crate". Tab completion lists the directions, `auto`, `next` and `mode`
(admins only). `/crate locations` and `/crate diagnose` show each crate's facing, and
`/crate models` counts locations with their own facing.

### Quick rotation

After `/crate rotate mode on`, **sneak + right-click a crate with an empty hand** turns it
NORTH → EAST → SOUTH → WEST → NORTH, with an action-bar line and a click sound.

- Only crate admins who turned the mode on. Losing `joshymc.crate.admin` ends it, and it is
  cleared on quit (memory only).
- With a key (or any item) in hand, or without sneaking, the click is the normal crate
  flow: open, mass-open, preview and the key checks are unchanged. No key is used, no
  reward is picked and no preview opens for a rotation. Ordinary sneak + right-click (for
  everyone else, and for admins outside rotate mode) still previews.
- Works on the block and on a model's hitbox (both go through `handleCrateClick`, behind
  the same one-action-per-tick click gate).
- `crates.rotation.quick-rotate: false` turns it off server-wide (`/crate rotate` still works).

### How it is stored

- Column `facing` on the existing `crate_locations` table (no second table). It is added once
  at startup when missing (`PRAGMA table_info` check), so older databases keep every row,
  crate type and id. `NULL` = automatic, which is what every crate registered before this
  change has, so they look exactly as before.
- Each row has its own value, so turning one crate never turns the others of its type.
- `/crate setlocation` on a block that is already a crate keeps its facing.
- A value this build can't read (not NORTH/SOUTH/EAST/WEST) is logged at startup, treated as
  automatic and left in the row untouched.
- The facing is read from the location every time a model is drawn (startup, `/joshymc reload`,
  chunk load, `/crate models refresh`, `/crate models set`, pack status, monthly rotation),
  so it survives all of them. Changing the model or the month never resets it.

### Model alignment

Every bundled crate model is built on `art/items/_crate_chest.py`, whose front (lock) is +Z
(south) in model space; `CrateRotationCheck` verifies every `*_chest`/`*_crate` module is.
A facing is a fixed yaw in the same convention as `rotation:` degrees (south 0, west 90,
north 180, east 270), passed through the unchanged `CrateModels.placement`: the display stays
on the block centre plus `offset`, `scale` and the floor lift are kept, the model only turns
about the vertical axis (never mirrored or upside down), and the hitbox is recomputed from the
turned model. A change redraws only that location (`refresh`, which removes the old display
and hitbox first), so there are never duplicates.

### The vanilla block (`crates.rotation.rotate-block`, default true)

When a direction is set, the crate block itself is turned to match where that is safe, so
Bedrock and pack-less players (who see the real block) see the same front:

- only blocks that support that facing (chests, trapped/ender chests, barrels, shulker
  boxes, ...);
- **never half of a double chest**: turning one half would split the pair, so there only the
  model turns (the message says so);
- the same block with a new facing (`setBlockData(..., false)`): the block type and its block
  entity (inventory) stay, and no physics or neighbour updates run, so adjacent chests aren't
  joined or changed;
- `auto` leaves the block as it is; with `rotation: auto` the model then follows it.

`rotate-block: false` turns only the model. The crate registration, keys and rewards are
never touched either way.

### Verifying (needs a test server)

1. Place two crates of one type. `/crate rotate east` on one: only it turns (model and
   chest); the other stays.
2. `/crate rotate north|south|west`: the lock faces that way; F3+B on Homestead / Campfire /
   Stockpile shows the hitbox still around the model.
3. `/joshymc reload`, restart, fly away until the chunk unloads and come back: same facing,
   and `/execute if entity @e[type=item_display,tag=joshymc_crate_model]` matches
   `/crate models`.
4. `/crate rotate mode on`, sneak + right-click with an empty hand: it cycles. With a key:
   mass-open as usual. Not sneaking: normal open / key message. A non-admin sneak +
   right-click: preview.
5. A double chest crate: the model turns, the chest stays paired.
6. `/crate rotate auto`: back to the automatic facing.

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
- `./gradlew -I scripts/crate-rotation-check.init.gradle.kts crateRotationCheck` (issue
  #1472) covers every direction, auto, next/quick-rotate order, stored values and migration,
  model and hitbox alignment per direction (scale/offset kept, never mirrored), the
  vanilla-block rules, quick-rotate gating, the models' +Z front, and the wiring (exact
  location, admin permission, one row updated, one location redrawn, key/reward paths
  untouched).
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
- `src/main/kotlin/com/liam/joshymc/crate/CrateFacing.kt`: Bukkit-free per-location facing
  rules (issue #1472); `CrateManager.setCrateFacing` stores and applies them, `/crate rotate`
  is in `CrateCommand`.
- `CrateManager` owns `models`, starts and stops it, redraws on location changes, and routes
  hitbox clicks through `handleModelClick`.
  `ListenerManager` registers it. `ResourcePackManager.packId` exposes the pack id.
  `/crate models [refresh]` is in `CrateCommand`.
