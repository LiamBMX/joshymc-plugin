# Bedrock custom items (Geyser)

Java players see JoshyMC's custom items through the Java resource pack. Bedrock players
connect through Geyser, which can't read Java resource packs, so they need two extra
things: a **Bedrock resource pack** with the item textures and **Geyser custom item
mappings** that say which Java item becomes which Bedrock item. JoshyMC produces both and
installs them into Geyser on every startup.

> **Status:** the files are generated and validated automatically, but Bedrock rendering
> has **not** been verified with a real Bedrock client yet. Run the manual check below
> before calling it done.

## What a Bedrock player gets

- Every JoshyMC custom item that has a custom model (custom items, the 77 fish, crate
  keys with a `key-item-model`) shows its **own icon** in the inventory, hotbar and hand,
  with its normal custom name and lore.
- The icon is the item's real Java inventory view (the 3D model rendered from the same
  angle the Java inventory uses), so it looks like what Java players see in their slots.
- Items keep their Java base item, PDC tags, item model and data. Nothing about stored
  items, player data or Java visuals changes; Geyser only changes how it *draws* them.

## Limitations (what Bedrock can't show yet)

| Java feature | On Bedrock |
|---|---|
| 3D models in the hand / on the ground / in item frames | The flat icon, held like a vanilla item (swords and tools upright). Converting the 3D models needs Bedrock geometry + attachables per item; not done, because it can't be checked without a Bedrock client. |
| Helmets drawn as 3D models on the head, custom armor and boot layers | Not drawn: Bedrock needs an attachable per armor piece. Expect custom armor to look **invisible** when worn by Bedrock players (the armor still protects normally). |
| Animated textures (eggs, keys, fish, drills...) | The first frame; Bedrock item icons can't animate. |
| Oversized inventory models | Clipped to the slot. |
| Great Pumpkin Pie bites / furniture models in the world | Those are display entities Geyser already shows with the base item; unchanged by this. |
| Shields (Hearthkeeper) and elytras (Falling Leaf, Wings of the Blizzard, Bat Wings) | **Not mapped** (`bedrock-pack.excluded-base-items`), so they keep the vanilla shield/elytra look on Bedrock. A Bedrock custom item can't block or glide like the vanilla one. |
| Bows, crossbows, tridents, eggs, snowballs, food, cake-based placeables | Mapped. Geyser gives custom items on these bases the base item's behavior (charging, throwing, eating), but that needs the manual check below. If one fails, add its base item to `excluded-base-items`. |

## How it works

1. **Icons (build time).** `python3 -m art.bedrock` (run from `resourcepack/`, and by
   `art.build` after every build) reads the *built* Java pack: every item definition in
   `assets/*/items/**.json`, the model its inventory view uses and that model's textures.
   It renders a 64x64 icon per item model key into
   `src/main/resources/bedrock/icons/<namespace>/<path>.png` and writes
   `src/main/resources/bedrock/icons.json` (model key, Bedrock name, icon, sha1). These
   are generated files; commit them with the art.
2. **Pack + mappings (server start).** `BedrockPackManager` collects every look the plugin
   hands out: each registered custom item (`createItemStack()`: base item, item model, max
   stack size), each custom fish (`joshymc:fish/<id>`) and each crate's `key-item-model`.
   `GeyserMappings` turns those into Geyser's **v2 mappings** (`format_version: 2`,
   `type: definition`, matched by base item + `minecraft:item_model`, no CustomModelData),
   and `BedrockPack` builds a `.mcpack` with just the icons those mappings use plus
   `textures/item_texture.json`. Bedrock identifiers are `joshymc:<name>` where the name
   is the model path with `/` turned into `_` (`joshymc:fish/cod` -> `joshymc:fish_cod`).
   A model used on more than one base item (a crate key on a tripwire hook) gets
   `_<base item>` appended for the extra bases.
3. **Install.** It writes `packs/JoshyMC-Bedrock.mcpack` and
   `custom_mappings/joshymc.json` into Geyser's folder, only those two files, only when
   their bytes changed. Copies always go to `plugins/Joshymc/bedrock/`. JoshyMC loads
   before Geyser-Spigot (`loadbefore` in plugin.yml), so Geyser reads the fresh files the
   same startup.

The pack bytes are deterministic and the pack UUID never changes; its version is a hash
of its content, so Bedrock players download it again only when an icon actually changed.

## Deploying on PebbleHost (Paper 26.2 + Geyser-Spigot)

Nothing to copy by hand when Geyser runs as a plugin on the same server:

1. Upload the new JoshyMC JAR to `plugins/` as usual.
2. Make sure Geyser's `config.yml` keeps `add-non-bedrock-items: true` (the default;
   JoshyMC logs a warning if it's off, and never edits Geyser's config).
3. Restart the server (not `/reload`). The console shows
   `[Bedrock] N custom items mapped for Geyser in plugins/Geyser-Spigot (updated)`.
4. If the log instead says *Geyser was already running*, restart once more.

Settings (`config.yml`):

```yaml
features:
  bedrock-pack: true          # false = write nothing anywhere
bedrock-pack:
  geyser-folder: ""           # e.g. "plugins/Geyser-Spigot"; empty = find the Geyser plugin
  excluded-base-items:
    - minecraft:shield
    - minecraft:elytra
```

**Geyser on a proxy (Velocity/Bungee) or standalone:** JoshyMC can't reach it, so it
logs that Geyser isn't installed and only writes `plugins/Joshymc/bedrock/`. Copy
`JoshyMC-Bedrock.mcpack` into the proxy Geyser's `packs/` folder and `joshymc.json` into
its `custom_mappings/` folder after each JoshyMC update that changes items, then restart
Geyser. Delete any older copy of the two files with a different name.

## Adding a new custom item

Nothing Bedrock-specific is needed:

1. Build its art as usual: `python3 -m art.build <id>` also refreshes the Bedrock icons.
2. Register the item and give it `meta.setItemModel(NamespacedKey(Joshymc.instance, "<id>"))`.
3. `./gradlew build`. The next server start maps it.

Checks that catch mistakes:

- `python3 -m art.bedrock --check` fails when an icon is missing, stale (the Java model
  changed) or orphaned, when a model/texture is missing, and when two model keys would
  get the same Bedrock name.
- `python3 -m unittest discover -s art/tests -t .` (from `resourcepack/`) runs the
  converter tests and checks that every item model the Kotlin code and `crates.yml` use
  has an icon.
- At startup, any item whose model has no icon, or whose Bedrock identifier would
  collide, is logged as a `[Bedrock]` warning with the fix, and keeps its vanilla
  Bedrock look instead of a broken one.

## Manual verification with a Bedrock client

Do this on a test server (or a quiet moment on the live one) with Geyser + Floodgate:

1. Start the server with the new JAR. Check the console for the `[Bedrock] ... mapped`
   line and no `[Bedrock]` warnings, and that Geyser lists `JoshyMC Custom Items` when it
   loads resource packs and reports custom items being registered.
2. Join from Bedrock (Windows/phone/console). Accept the resource pack prompt.
3. As an op on Java, give the Bedrock player a sample of each base type and check each
   one shows its JoshyMC icon (not the vanilla item) in the hotbar, inventory and hand:
   - `/joshymc give frostbite 1 <player>` (sword), `orchard_pickaxe` (tool),
     `void_drill` (pickaxe that mines 3x3)
   - `freeze_egg` (throw it), `tomato` (throw it, stack of 16)
   - `peanut_butter_cup`, `candy_corn`, `pumpkin_pummel` (eat them)
   - `phantoms_grasp` (draw and shoot the bow), `woodland_hunter` (crossbow),
     `maplefang` and `viking_boomerang` (throw the tridents)
   - `gun` (stack of 1), `token`, `sell_wand`, `fast_hopper` (place it)
   - `small_gold_pile` (place it as furniture), `great_pumpkin_pie` (place and eat)
   - a crate key (`/crate givekey`), a caught custom fish
   - armor: `viking_helmet` and `krampus_chestplate`: equip them, check the icons and
     note how they look worn (expected: not drawn, see Limitations)
4. Check behavior didn't change: every item above still does what it does on Java.
   Anything that lost its vanilla behavior on Bedrock: add its base item to
   `excluded-base-items`, restart, and report it.
5. Check a Java player at the same time still sees every item exactly as before.
6. Note the result (device, Geyser build, what passed/failed) on the issue.
