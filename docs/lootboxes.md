# Lootboxes (issue #1602)

Inventory Lootboxes with ten levels. A player right-clicks a Lootbox, picks **5 of 27** mystery
slots, confirms, watches five reels roll and stop one after another, and claims five prizes drawn
from that level's own pool. Independent from crates: no keys, blocks, locations or NPCs.

Code: `src/main/kotlin/com/liam/joshymc/lootbox/` (`LootboxManager`, `LootboxMenus`,
`LootboxCommand`; Bukkit-free `LootboxConfig`, `LootboxRules` (draws, selection, layout,
animation plan, refusals), `LootboxStore`). Settings: `plugins/Joshymc/lootboxes.yml` (also
found in `plugins/Joshymc/config/`). Feature flag: `config.yml > features.lootboxes`.

Checks: `./gradlew -I scripts/lootbox-check.init.gradle.kts lootboxCheck`.

## Commands and permissions

| Command | Permission (default) | What it does |
|---|---|---|
| right-click a Lootbox | `joshymc.lootbox.use` (true) | open it |
| `/lootbox help` (alias `/lootboxes`) | `joshymc.lootbox.use` | how Lootboxes work (plus the staff commands the sender may use) |
| `/lootbox give <player> <level> [amount]` | `joshymc.lootbox.give` (op) | give 1-2304 Lootboxes; offline players get them on their next join |
| `/lootbox giveall <level> [amount]` | `joshymc.lootbox.give` (op) | the same for every online player |
| `/lootbox list` | `joshymc.lootbox.admin` (op) | each level's gamemodes, reward count, total weight and status / first problem |
| `/lootbox reload` | `joshymc.lootbox.reload` (op) | reread `lootboxes.yml` and print its problems |
| `/lootbox admin pending` | `joshymc.lootbox.admin` (op) | openings in REVIEW or with prizes still PENDING / INTERRUPTED / FAILED |
| `/lootbox admin inspect <player>` | `joshymc.lootbox.admin` (op) | a player's last 10 openings, their slots and every prize's state |
| `/lootbox admin review <id> grant\|void confirm` | `joshymc.lootbox.recovery` (op) | settle an opening in REVIEW (see recovery) |
| `/lootbox admin resolve <id> <prize 0-4> resend\|delivered confirm` | `joshymc.lootbox.recovery` (op) | settle an INTERRUPTED or FAILED prize |

`/lootbox` is NETWORK-scoped (`GamemodeAssignments`). Delegated runs (`/sudo`, NPC and portal
commands) may only use `/lootbox help` (`DelegatedCommandRules.PLAYER_SUBCOMMANDS_ONLY`). Every
give, reload and recovery action is written to `admin_logs` (`LOOTBOX_*`). `/lootbox give`
refuses players held by a Ranked match (`StaffAction.ITEM_GRANTS`), like other item grants.

### Creating and distributing Lootboxes

1. Add rewards to a level in `lootboxes.yml` (level 1 has disabled examples of every type), then
   `/lootbox reload` and check `/lootbox list` shows the level as **ready**.
2. Give them: `/lootbox give Steve 3 2` (two Level 3 Lootboxes), `/lootbox giveall 1`.
3. From the store (Tebex): `purchase fulfill {id} lootbox <level> <quantity> {transaction}` — the
   `lootbox` reward kind of `/purchase fulfill` (see `docs/store-fulfillment.md`), deduped by the
   reference like every store line.

Both go through the existing purchase delivery queue (`purchase_deliveries`, reward kind
`lootbox`): an online player with room gets them at once, the rest waits (never dropped) and is
delivered when they're online with room, outside the event world and not waiting for a Ranked
inventory restore. A level that isn't ready can't be given at all.

## The item

Any material (default `CHEST`) with `joshymc:lootbox_level`, `joshymc:lootbox_version` and
`joshymc:lootbox_signature`, an HMAC-SHA256 over level and version with a random per-server
secret (`lootbox_secret` table; never on an item or in a log). Names and lore prove nothing:
renaming keeps a Lootbox valid, and an ordinary item named "Level 10 Lootbox" is just an item. A
forged signature is refused and recorded as a CRITICAL anti-dupe alert. Same-level Lootboxes stack.
Creative copies are already refused (`CreativeValuablesListener`, `joshymc:` data).

A Lootbox never acts as its material: right-click never places or uses it (either hand), and
placing, entity use (e.g. chests on donkeys), eating, crafting / crafter use, furnace fuel and
dispensers are cancelled. Left-clicks and moving it around the inventory are untouched.

### Look (issue #1603)

Each level has its own animated Lucky Block in the bundled resource pack, item model
`joshymc:lootbox_level_<n>` (art: `resourcepack/art/items/lootbox_level_<n>.py` on the shared
`_lootbox.py`): a cube with a raised, glowing question mark on four sides and the top, in a metal
frame that gets more ornate with the level (inner lip from 2, rail rivets from 3, corner gems from
4, gem-set rail bands from 7, crystal crown and a looping rainbow at 10). Colours: 1 stone gray,
2 emerald, 3 cyan, 4 sapphire, 5 amethyst, 6 neon pink, 7 crimson, 8 inferno orange, 9 gold,
10 prismatic rainbow. The animation is plain texture animation (`.png.mcmeta`, 16 frames of 2
ticks), so it runs on the client in slots, hands and on the ground with nothing ticking on the
server.

The model is written onto a box when it's made (`levels.<n>.item-model`). Boxes made before the
model existed, or before `item-model` was changed, are brought up to date when they're met:
the player's inventory and ender chest a moment after joining and after `/lootbox reload` /
`/joshymc reload`, a box being picked up, and a box being right-clicked. Only boxes whose
signature verifies are touched, and only their item model changes (level, version, signature,
amount, name, lore stay), so refreshed and new boxes stack. Offline players, containers and
other inventories aren't scanned; those boxes update when a player picks them up or uses them.

## GUI layout (54 slots each)

**1. Selection — "Level X Lootbox"**

| Slots | Content |
|---|---|
| 0-26 | 27 identical mystery slots (`gui.mystery-material`); a picked one turns `gui.selected-material` with a glint and its pick number |
| 27-44 | filler; slot 40 shows "Picked N / 5" |
| 45 | Cancel (closes; nothing is used) |
| 49 | Confirm Selection (grey until exactly 5 are picked) |
| 46-48, 50-53 | filler |

Click a slot to pick it, click it again to un-pick. A sixth pick is refused. The selection lives in
memory per player only; closing the screen uses nothing. Confirm is one-shot.

**2. Rolling — "Opening Level X Lootbox..."**: five reels at 20-24; above (11-15) and below
(29-33) each one lights up when it stops. **3. Summary — "Level X Lootbox Rewards"**: the level
at slot 4, the five prizes at 20-24 with their amounts, **Claim Rewards** at 49.

All GUI slots are cancelled by `GuiManager` (clicks, drags, shift-clicks, double-clicks, number
keys and off-hand swaps into the GUI), so nothing can be taken out or put in.

## Draws and probabilities

The prizes are drawn on the server at Confirm, with `SecureRandom`, from the level's pool filtered
for that player:

- the reward is enabled and allowed in the player's gamemode (`gamemodes:`, default = the level's);
- the player has its `permission:` (if any);
- it is deliverable here: Coins/Credits only where that gamemode's economy lives
  (`TutorialRules.economyBelongsTo`), gamemode-scoped custom items only in their own gamemode and
  never where it is locked away, chat tag unlocks only if the player doesn't own the tag yet.

Each of the five prizes is an **independent weighted draw with replacement**: the chance of a reward
is `weight / total weight` of that filtered pool, so the same reward can be won more than once.
`unique: true` makes a reward come up at most once per opening: once drawn it leaves the pool for
that opening's remaining draws (so the others' chances rise). Chat tag unlocks are always unique.
The picked slots are recorded but never used by the draw, so slot choice and order can't bias
anything. A level opens only if its pool can produce five prizes (a reward that may repeat, or at
least five unique ones); otherwise the player is told and keeps the Lootbox.

Weights must be finite numbers above 0 (fractions allowed, at most 1,000,000,000); negative, zero,
NaN, infinite and text weights are configuration problems.

## Transaction and recovery

```
selection (memory) → Confirm → CONFIRMED → (Lootbox taken, player saved) → REWARDS_COMMITTED → DELIVERING → DELIVERED
```

At Confirm, in one main-thread call:

1. Every check runs again (file, level, gamemode, refusals below, the player still holds an
   authentic Lootbox of that level), the five prizes are drawn and **built** (items serialized
   with `serializeAsBytes`, so the delivered item is exactly what was built). If one can't be
   built, nothing happens and the problem is logged.
2. One transaction writes the opening (`lootbox_openings`, `CONFIRMED`) and its five prizes
   (`lootbox_opening_rewards`, `PENDING`).
3. Exactly one Lootbox is taken from the slot found in step 1 and the player is saved
   (`saveData`). A failed save puts the Lootbox back and voids the opening.
4. `CONFIRMED → REWARDS_COMMITTED` (conditional). From here the prizes are owed, whatever happens.

The animation only displays the stored prizes. Closing any screen, disconnecting, reconnecting,
restarting, reloading or opening another GUI can't change or reroll them.

Delivery reads the stored rows (`deliverOpening` / `deliverPending`): Claim, closing the rolling
or summary screen, joining (5s later) and a one-minute sweep for online players all use it.

- Items: only as many as fit (`depositItemSafely`; never dropped), then the player is saved; the
  rest stays `PENDING` with fewer `remaining`. Nothing is handed over while dead, in the event
  world, with an unsettled event inventory or while a Ranked inventory is held / waiting to be
  restored. Vouchers are copied fresh, one per item (`freshVoucherCopy`).
- Coins / Credits and chat tag unlocks: the row's `PENDING → DELIVERED` and the deposit / unlock
  are one transaction.
- Commands: marked `DELIVERED` first, then run once from console (at most once; a failure is
  logged, never rerun).

Every prize move is a conditional UPDATE on its state and owner, and triggers keep `DELIVERED`
and `VOIDED` final and forbid deleting prizes, so repeated clicks, overlapping triggers, another
player or a staff action can't deliver one twice.

At startup:

- `CONFIRMED` → `REVIEW` (the server stopped between writing the prizes and committing; the
  Lootbox may or may not still be in the player's file). Staff check the player's inventory:
  `review <id> grant confirm` if the Lootbox is gone (the five prizes become owed), `void` if
  they still have it.
- `DELIVERING` prize → `INTERRUPTED` (stopped mid-delivery). Staff check the inventory:
  `resolve <id> <prize> delivered confirm` if it arrived, `resend` to deliver the recorded
  remaining count again.

A `FAILED` prize (it couldn't be built or deposited any more) is resolved the same way.

## Gamemodes

Survival and Lifesteal are separate servers with separate databases, so Lootboxes, openings and
balances never cross. Each level lists the gamemodes it opens in, each reward optionally a subset.
A Lootbox can't be opened while dead, in Mod Mode or Spectator mode, combat-tagged, in a Ranked
match (held, spectating or waiting for a restore), in the event world or with an unsettled event
inventory. There is no heart reward type and Heart items are refused as rewards.

## Configuration (`lootboxes.yml`)

The bundled file ships **no live rewards**: every level's pool is empty, and level 1 holds
disabled examples of every type. A mistake in one level closes only that level; a mistake in the
top-level settings (`gui`, `animation`, `sounds`, `messages`, the `levels` list) closes all
Lootboxes until it is fixed. Openings already committed are always delivered.

```yaml
enabled: true
gui:
  selection-title: "&8Level {level} Lootbox"        # {level}, {name}
  opening-title: "&8Opening Level {level} Lootbox..."
  summary-title: "&8Level {level} Lootbox Rewards"
  mystery-material: ENDER_CHEST
  selected-material: LIME_STAINED_GLASS_PANE
  filler-material: BLACK_STAINED_GLASS_PANE
  rolling-filler-material: GRAY_STAINED_GLASS_PANE
  stopped-material: LIME_STAINED_GLASS_PANE
animation:                      # ticks; visual only
  start-interval-ticks: 2       # 1-20
  end-interval-ticks: 8         # 1-40, not below start
  first-stop-ticks: 40          # 10-400
  stagger-ticks: 12             # 0-200
  summary-delay-ticks: 30       # 0-200
sounds:                         # open, select, deselect, denied, confirm, roll, reel-stop, finish, claim
  roll: { sound: BLOCK_NOTE_BLOCK_HAT, volume: 0.4, pitch: 1.6 }
  finish: false                 # turn one off
messages:
  opened: "&aYou opened a {name}&a!"
levels:
  3:
    name: "&aLevel 3 Lootbox"
    description: ["&7A mystery box of Level 3 rewards."]
    material: CHEST
    item-model: "joshymc:lootbox_level_3"   # the bundled Lucky Block (missing key = this; none = plain material)
    glint: false
    enabled: true
    gamemodes: [survival, lifesteal]
    rewards:
      diamonds:           { type: item, name: "&bDiamonds", material: DIAMOND, amount: 3, weight: 10 }
      pick:               { type: item, material: DIAMOND_PICKAXE, enchantments: { efficiency: 3 }, weight: 4 }
      enchanted_custom:   { type: item, material: DIAMOND_SWORD, custom-enchantments: { lifesteal: 1 }, weight: 1 }
      token:              { type: custom-item, item: token, amount: 2, weight: 5, gamemodes: [survival] }
      exact:              { type: item-base64, item-base64: "<base64>", weight: 1 }
      key:                { type: crate-key, crate: vote, amount: 1, weight: 3 }
      tag:                { type: chat-tag, tag: joshymc, weight: 1 }
      tag_voucher:        { type: chat-tag-voucher, tag: joshymc, weight: 1 }
      coins:              { type: coins, amount: 250, weight: 20, preview: GOLD_INGOT }
      credits:            { type: credits, amount: 10, weight: 2, permission: some.node, unique: true }
      server:             { type: command, commands: ["say {player} won"], weight: 1, preview: PAPER }
```

The example ids/values are only examples; pools and weights are the Owner's to set.

### Reward types

| Type | Fields | Delivered as |
|---|---|---|
| `item` | `material`, `amount` (1-2304), optional `enchantments` (vanilla keys, at most the vanilla max level, must fit the item; stored on books) and `custom-enchantments` (JoshyMC ids, applied with `applyEnchant`) | item |
| `custom-item` | `item` (a `/joshymc give` id), `amount`; built by its own class (Beacon Potions for the gamemode they're won in) | item |
| `item-base64` | `item-base64` (exact snapshot, e.g. copied from crates.yml), `amount`; every component, PDC value, model, attribute, name and lore kept | item |
| `crate-key` | `crate` (crates.yml id), `amount` | item |
| `chat-tag-voucher` | `tag` (voucher chat tag), `amount` (1-64) | Chat Tag Voucher items |
| `chat-tag` | `tag` (voucher chat tag) | unlocked on the account (`chat_tag_unlocks`), no item |
| `coins` / `credits` | `amount` (above 0, rounded to cents) | this server's balance |
| `command` | `commands` (1-10, `{player}` / `{uuid}`) | console commands, once |

Common optional fields: `name`, `preview` (material shown while rolling / in the summary),
`permission`, `gamemodes`, `unique`, `enabled`.

Unknown materials, enchantments, custom items, crates or tags, undecodable snapshots, Heart items
and vouchers that can't be copied are configuration problems: the level closes and the problem is
logged and shown in `/lootbox list` and `/lootbox reload`; nothing is replaced by another item.

### Messages

`disabled`, `level-unavailable`, `wrong-gamemode`, `nothing-to-win`, `invalid-item`, `busy`,
`selection-full`, `selection-cancelled`, `lootbox-gone`, `opened`, `rewards-header`,
`reward-line`, `rewards-waiting`, `rewards-waiting-here`, `chat-tag-owned`, `chat-tag-unlocked`,
`save-failed`, `review` (defaults in `LootboxConfig.DEFAULT_MESSAGES`).

## Animation

One repeating 1-tick task per opening, cancelled as soon as it ends (or the player closes the
screen / quits). The plan (`LootboxAnimationPlan`) is precomputed: preview changes start every
`start-interval-ticks` and ease out to `end-interval-ticks`; reel *i* stops at
`first-stop-ticks + i × stagger-ticks`. An inventory slot is only updated when its preview changes,
with one roll sound per tick at most; each stop lights its indicators and plays `reel-stop`; the end
plays `finish` and a totem particle burst visible to the opener only. Previews are random icons of
the player's eligible pool and never hint at the prizes.

## Limitations

- Cosmetics other than chat tags (kill effects, trails, ...) are permission-based in JoshyMC and
  have no account-unlock store, so they aren't a reward type; grant them through a `command`
  reward (e.g. LuckPerms) if wanted. A `command` reward is the Owner's responsibility (at most once,
  not rolled back).
- Bedrock players see the plain material: the Lucky Blocks aren't in the Geyser mappings, since
  Lootboxes aren't registered custom items.
- There is no in-game reward editor; copy `item-base64` snapshots from crates.yml (`/crateeditor`).
