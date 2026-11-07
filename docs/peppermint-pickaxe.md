# Peppermint Pickaxe (issue #1399)

A Christmas 2026 cosmetic pickaxe for **Survival only**. Underneath it is an ordinary
Netherite Pickaxe. Only its look and its name/lore are custom.

## What it is

| | |
|---|---|
| Custom item id | `peppermint_pickaxe` (PDC `joshymc:custom_item_id`) |
| Base item | `NETHERITE_PICKAXE`. Mining speed, durability, enchanting, anvils and Netherite Ingot repair are all vanilla. It is not unbreakable and has no abilities. |
| Item model | `joshymc:peppermint_pickaxe` (`assets/joshymc/items/peppermint_pickaxe.json`) |
| Name / lore | Bold **Peppermint Pickaxe** with alternating red and white letters. Lore: "A sweet way to mine through the holidays!" and "Christmas 2026 \| JoshyMC". |
| Gamemode | `GamemodeScope.SURVIVAL`. `/joshymc give` refuses it where Survival is locked away. |
| Class | `src/main/kotlin/com/liam/joshymc/item/impl/PeppermintPickaxe.kt`, registered in `ItemManager` |

Only stacks carrying the PDC tag and item model change. Plain Netherite Pickaxes are untouched.
Enchanting, renaming, combining and repairing keep both the tag and the model. Item components
survive restarts and inventory moves.

## Getting one (admins)

```
/joshymc give peppermint_pickaxe [amount] [player]
```

- Needs `joshymc.give` (default: op).
- Validates the player and the amount, and refuses gamemode-locked targets.
- Each grant is logged as `ITEM_GIVE` in `admin_logs`.

It is **not** added to any crate, kit, shop, quest, event, recipe or store package. Set those
up separately.

## Textures

The art is code: `resourcepack/art/items/peppermint_pickaxe.py`. From `resourcepack/`, run:

```
python3 -m art.check peppermint_pickaxe
python3 -m art.render peppermint_pickaxe   # preview sheets
python3 -m art.build peppermint_pickaxe    # writes textures, models, item definition, Bedrock icon
```

`./gradlew build` zips the pack into the JAR. On startup, `ResourcePackManager` replaces
`plugins/Joshymc/resourcepack.zip` when the bundled copy differs, and players get the update on
their next join. No manual install step is needed.

If a player has no resource pack, they see a normal Netherite Pickaxe with the custom name and
lore. Bedrock players get a flat icon through the Geyser mapping (see `docs/bedrock-custom-items.md`).

## Manual checklist (needs a client)

- [ ] Inventory icon shows the candy-cane pick with the mint at the centre.
- [ ] First and third person: held by the handle, head above the hand, nothing clipping the camera.
- [ ] Dropped item and item frame look right.
- [ ] The frosting glitters and the mints shine (animated).
- [ ] It mines obsidian/stone at Netherite speed and loses durability.
- [ ] Enchanting table and anvil work (Efficiency, Fortune, Mending…); a Netherite Ingot repairs it.
- [ ] Look and name survive renaming, repairing, a relog and a restart.
- [ ] A non-op player can't use `/joshymc give`.
- [ ] A plain Netherite Pickaxe still looks vanilla.
