# Sell prices (`/sell`, `/worth`)

Issue #1416. Survival only (`/sell`, `/worth`, `/sellwand` and `/market` are SURVIVAL-scoped).

## One price list

`plugins/Joshymc/sell-prices.yml > prices:` is the only place a sell price comes from.
`SellPriceManager` loads it, and every sell path reads it:

| Path | Price | Payout |
|---|---|---|
| `/sell` GUI (sells on close) | `prices:` | shared formula |
| `/sell hand`, `/sell hand all`, `/sell all <item>` | `prices:` | shared formula |
| Sell Wand on a chest | `prices:` | shared formula × the wand's multiplier |
| `/worth`, `/worth <item>`, the Worth GUI | `prices:` | base price shown |
| Held-item "Sell:" action bar | `prices:` | base price shown, only for stacks `/sell` accepts |
| `/market price <item>` | `prices:` | base price shown |

The shared payout formula (`SellPriceManager.getStackValue(stack, player)`) is
base price × Flower Armor crop bonus × Catalyst mutation × rank sell multiplier, rounded to cents.
Before issue #1416 the `/sell` GUI skipped the crop bonus and the Catalyst bonus while
`/sell hand` applied them; every path now pays the same.

`/shop` (shop.yml) keeps its own buy prices and was not changed.

### Categories are organizational only

The `categories:` block in sell-prices.yml only groups items. It never makes an item sellable
and never sets a price. Older files carried a `sell:` value per category entry; those were never
paid by `/sell`, but they leaked into the held-item Sell line and `/market price`, which is why
those showed different numbers (for example Sugar Cane $8 on the hotbar while `/sell` paid $10).
They are ignored now, and the startup log lists any that differ from `prices:`.

## What can be sold

A stack sells only when its material has a positive price **and** it is an ordinary vanilla stack.
Refused, even when the base material has a price:

- JoshyMC custom items (`custom_item_id`).
- Anything carrying plugin data (PDC): crate keys (Tripwire Hook), money/rank/credit/chat-tag
  vouchers (Paper), Fly Vouchers (Feather), Resurge keys, items from other plugins. Before issue
  #1416 these sold as their vanilla material.
- A custom item model or custom model data.
- Containers or bundles that still hold items.
- Never sellable whatever the file says: shulker boxes, spawners and spawn eggs, weapons, armor,
  the main tools (swords, pickaxes, axes, shovels, hoes, spears, bows, crossbows, tridents, maces,
  shields, elytras, horse/wolf armor), and admin-only or unobtainable blocks.

Still sellable (these tags don't add value): the Catalyst mutation (it is meant to be sold, +50%),
the anti-dupe `death_drop` tag on every death drop, `/signitem` signatures, and plain renames.
JoshyMC fish on a Cod/Salmon/Tropical Fish/Pufferfish base keep selling as that fish, as before.
Fish built on a Heart of the Sea, Nautilus Shell, Prismarine or Glow Ink Sac base are refused,
because selling them would cash a fishing reward in as a valuable vanilla item.

## Validation

On load the catalog reports (one summary line per kind in the console, full list with
`/sell admin problems`): unknown material names, non-items, never-sellable materials, prices that
are not numbers, negative, not finite or above $1,000,000 per item, the same material listed twice
under different spellings (`kelp` and `KELP`: the later one wins, as before), category entries
with an unknown material, category-only entries, and category `sell:` values that differ from
`prices:`. A price of `0` disables an item quietly.

Bundled defaults are merged into an existing file only for materials the file doesn't list in
any spelling, so a hand-written `kelp: 2.25` never gets a second `KELP:` beside it.

## Admin diagnostics

`/sell admin` (`joshymc.sell.admin`, op), read-only:

- `/sell admin hand`: the held stack's material, effective price, source key, category and
  whether this exact stack sells (with the reason if not).
- `/sell admin item <material>`: the same for a plain stack of that material.
- `/sell admin problems [page]`: every problem found in sell-prices.yml.
- `/sell admin summary`: counts.

## Added in issue #1416

| Material | Price | Why this price |
|---|---|---|
| `KELP` | 2.25 | The value in the live catalog; smelts 1:1 into Dried Kelp ($6), which costs fuel |
| `DRIED_KELP_BLOCK` | 54 | 9 × Dried Kelp; crafts both ways |
| `HAY_BLOCK` | 135 | 9 × Wheat; crafts both ways |
| `BONE_BLOCK` | 135 | 9 × Bone Meal; crafts both ways |
| `CLAY_BALL` | 0.75 | Clay ($3) / 4: a Clay block breaks into 4 balls and 4 craft back. The old category value ($5) would have paid 6.7× for placing and breaking Clay |
| `SNOWBALL` | 0.10 | Snow golem farms are fully AFK, so kept below Cobblestone |
| `SNOW_BLOCK` | 0.40 | 4 × Snowball; breaks into 4 and crafts back |
| `BROWN_MUSHROOM`, `RED_MUSHROOM` | 5 | Same tier as flowers and saplings |
| `EGG`, `BROWN_EGG`, `BLUE_EGG` | 3 | Chicken farms are AFK; below Feather ($35) and raw Chicken ($30) |
| `MOSSY_COBBLESTONE` | 1 | The old category value; Cobblestone + Moss/Vine costs more |
| `MANGROVE_ROOTS` | 2 | Mangrove tree farms, same tier as leaves |
| `MUDDY_MANGROVE_ROOTS` | 2.50 | Below Mangrove Roots + Mud (2.75) |

Every other material in the issue's list was already in `prices:` and keeps its price.
`SADDLE` was in the old categories but never had a price; it stays unsellable and was removed
from the bundled categories.

## Proposed adjustments (need Owner approval, NOT applied)

These one-way crafts already pay more than selling the inputs. They existed before issue #1416
and were left unchanged because the issue forbids rebalancing existing prices without approval.
None of them can be fed from `/shop` (every `/shop` price is far above the sell price), so none
is an unlimited money loop; each is a small bonus per unit of a farmed or mined input.

| Craft | Inputs sell | Outputs sell | Possible fix |
|---|---|---|---|
| Iron Ingot → 9 Iron Nuggets | 175 | 180 | `IRON_NUGGET: 19.44` |
| Gold Ingot → 9 Gold Nuggets | 250 | 270 | `GOLD_NUGGET: 27.77` |
| Copper Ingot → 9 Copper Nuggets | 60 | 63 | `COPPER_NUGGET: 6.66` |
| Raw Iron/Gold/Copper Block → 9 raw | 1100 / 1550 / 400 | 1125 / 1575 / 405 | raw blocks at 9 × raw |
| Slime Block → 9 Slime Balls | 500 | 1125 | `SLIME_BLOCK: 1125` |
| Honey Block + 4 Glass Bottles → 4 Honey Bottles | 258 | 300 | `HONEY_BLOCK: 292` |
| Bone → 3 Bone Meal | 40 | 45 | `BONE_MEAL: 13.33` (then `BONE_BLOCK: 120`) |
| 3 Planks → 6 Slabs (every wood) | 15 | 18 | slabs at 2.50 |
| 6 Planks → 3 Doors (every wood) | 30 | 36 | doors at 10 |
| 6 Glass → 16 Glass Panes | 30 | 32 | `GLASS_PANE: 1.87` |
| 8 Glass + Dye → 8 Stained Glass | 50 | 56 | stained glass at 6.25 |
| Flower → Dye (Poppy, Dandelion, ...; tall flowers give 2) | 5 / 10 | 10 / 20 | dyes at 5 |
| Melon Slice → Melon Seeds | 3 | 5 | `MELON_SEEDS: 3` |
| Shearing a Pumpkin → Carved Pumpkin + 4 Seeds | 20 | 45 | `CARVED_PUMPKIN: 0` or seeds at 0 |
| Brown + Red Mushroom + Bowl → Mushroom Stew | 10 | 50 | stew at 10 |
| Pumpkin + Sugar + Egg → Pumpkin Pie | 33 | 50 | pie at 33 |

Smelting raw food/ores and Kelp → Dried Kelp also pays more than the raw item; that is normal
processing value (it costs fuel and time) and was not treated as arbitrage.

`scripts/SellPriceCheck.kt` lists these exact gains, so a new profitable recipe fails the check,
and an approved fix that removes one must move it to the guarded list.

## Checks

`./gradlew -I scripts/sell-price-check.init.gradle.kts sellPriceCheck` (Java 25 launcher, it
loads `Material`): price parsing, the catalog rules, the stack rules, the bundled file against
Paper 26.2's material names (no problems allowed), every material the issue lists, reversible
crafts priced equally, new crafts never beating selling, `/shop` buy prices above sell prices,
and the wiring of every sell path.

## Limitations

- The live `sell-prices.yml` can differ from the bundled one (the issue quotes Sugar Cane $50,
  Pumpkin $80, Wheat $35, which the bundled file never had). The plugin never rewrites existing
  prices; it reports conflicts in the console and in `/sell admin problems` for the owner to fix.
- The held-item Sell line still shows the base price (no rank or armor bonus).
- Booster sell multipliers (`BoosterManager.getSellMultiplier`) were never applied by `/sell` and
  still aren't; that is unchanged.
