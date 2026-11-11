# Crate GUIs (issue #1514)

Phase 5 of the crates redesign: the player-facing crate screens. Rewards, weights, keys, the
weighted draw, `crate_pending_rewards` recovery and the opening animation are unchanged; only
what players see (and one extra confirmation click in Select mode) changed.

## Screens

| Screen | How to get there | What changed |
|---|---|---|
| **Crate browser** | `/crate` (or `/crate menu`), `joshymc.crate.use` | New. Monthly crates (the ids in `config.yml > crates.custom-models.monthly.crates`) first with the current month, then permanent crates. Icon = the crate key's look (material + `key-item-model`), never a usable key. Lore: how it opens, reward count, key name, the player's keys (also shown as the stack size), and whether it can be opened. Click = Preview with Back. The browser never opens a crate or touches keys. |
| **Preview** (from a crate block) | left-click, or sneak + right-click without a key | Same #1414 layout (sized to the rewards, no glass). New reward lore. |
| **Preview** (from the browser) | click a crate in `/crate` | Reward rows + a bottom row: Back, Previous, crate info, Next, Close. 45 rewards per page. |
| **Select** | right-click a Select crate with its key | Header item, new reward lore, and a **confirmation screen**: the chosen reward highlighted with Confirm / Choose Another / Cancel. The key is still only taken at Confirm. |
| **Select 3** | right-click a Select 3 crate with its key | Progress item lists the picks; selected rewards glow; the confirmation screen has Confirm / Change Picks / Cancel. |
| **Spin / Pulse** | right-click a Random crate | Same reels and timing. Once the stored winner is paid the GUI turns into the result: winner revealed and glowing in the center, other reel slots dimmed, lime frame, "You won!" banner. It closes after 3 s (only if that GUI is still open). |
| **Result (Select / Select 3)** | after Confirm | Display-only "Reward Claimed" screen with the reward(s), banner and Close. |

Chat completion messages now name the crate and the rarity: "You won Diamond x3 (Legendary) from
Rare Crate!". Inventory-full and pending-delivery messages are unchanged.

## Reward lore

Every screen builds reward items through one helper (`CrateManager.rewardDisplay`), with the
wording in the Bukkit-free `crate/CrateRewardText`:

- the reward's real item: material, custom item model, custom name source, enchants and its own lore;
- `Rarity` (only when configured), `Quantity`;
- Preview only: `Chance` = exactly weight / total weight (`crate/CrateOdds`, e.g. `2.5% (1 in 40)`,
  `Locale.ROOT`), labelled `Mass-open chance` on Select crates (their weights only apply to
  sneak mass-opening), and `Status` (Obtainable, or why not: crate being fixed / only on another
  gamemode), from the same checks every open path runs.

## Configuration (all optional, old files work unchanged)

crates.yml, per reward:

```yaml
rarity: legendary   # presentation only; any id from config.yml crates.rarities
hidden: true        # "Mystery Reward" in the Preview and Spin/Pulse reels, real chance still shown
```

crates.yml, per crate: `listed: false` keeps it out of the `/crate` browser (it still works, and
left-click previews still show it).

config.yml:

```yaml
crates:
  rarities-enabled: true
  rarities:
    common: {label: "Common", color: "#AAAAAA"}
    # uncommon, rare, epic, legendary, mythic ...
```

Rarity is never computed from a chance. An unknown `rarity:` id is logged once at load and the
reward simply shows no rarity; it never blocks a crate. Tiers are not backfilled one by one
(deleting a tier keeps it deleted); with no `crates.rarities` section at all the six defaults are used.
The crate editor keeps `rarity`, `hidden` and `listed` when it saves crates.yml (it can't set them yet).

Hidden rewards are always visible on Select / Select 3 screens (players have to see what they pick).

## Security

- GUIs are views only. Every click handler re-checks on the server: `pickStale` refuses when the
  crate definition changed (editor edit or reload: a new `CrateDef` instance) or when the crate
  can't be opened here any more (`crateProblem` / `gamemodeProblem`); then the key in hand and the
  inventory space are checked, as before, before exactly one key is taken.
- Each confirmation screen pays at most once (`CrateSelection.OneShot`), on top of GuiManager's
  200 ms click cooldown. Result screens contain no payout code.
- Shift-click, double-click, number keys, off-hand swap and drags into crate GUIs are blocked by
  `GuiManager` (unchanged).
- Disconnects: Select 3 sessions end on close/quit; running Spin/Pulse rewards stay in
  `crate_pending_rewards` and are delivered on join (unchanged). The browser re-checks
  `joshymc.crate.use` and re-reads the crate on every click.

## Monthly / permanent crates

The browser reads the existing monthly rotation (issue #1460); no second rotation exists. A crate
is "monthly" while it is listed in `crates.custom-models.monthly.crates` and rotation is enabled;
the month is read from the rotation clock in its timezone. The bundled config lists no monthly
crates (the live ids are the Owner's choice), so until then every crate shows as permanent. Crate
ids, keys, rewards and models are not touched.

## Fallbacks

Icons use the key's material with its `key-item-model`; without the resource pack Java shows the
key model's fallback like keys already do, and Bedrock gets the Geyser key mapping. All other GUI
items are vanilla materials.

## Checks

```bash
./gradlew -I scripts/crate-menu-check.init.gradle.kts crateMenuCheck
./gradlew -I scripts/crate-preview-check.init.gradle.kts cratePreviewCheck
./gradlew -I scripts/crate-animation-check.init.gradle.kts crateAnimationCheck
```

## Live Paper checklist

1. `/crate` as a player: browser opens; each crate shows key count, mode and reward count. `/crate help` shows the usage.
2. Add a crate id to `crates.custom-models.monthly.crates`, `/joshymc reload`: it moves to "Monthly Crates" with the current month.
3. Click a crate: Preview with Back/Close; Back returns to the same browser page. A crate with 50+ rewards pages correctly.
4. Give a reward `rarity: epic` and another `hidden: true`, reload: rarity line and colour show; the hidden one is a Mystery Reward with its chance.
5. Chances: a crate with weights 1/1/2 shows 25% / 25% / 50%.
6. Random Spin and Pulse crates: open with a key; the winner is revealed in the center with the banner; chat names the crate; exactly one key used.
7. Select crate: click a reward → confirmation; Choose Another returns; Cancel uses no key; Confirm pays once (spam-click Confirm: one reward, one key) and opens the result screen.
8. Select 3: pick/deselect, fourth pick refused, Change Picks keeps the picks, Confirm pays three rewards for one key.
9. Without a key: right-click says you need a key; nothing opens.
10. Full inventory: Select/Select 3 refuse before the key; Spin/Pulse hold the reward and deliver it once space frees.
11. Shift-click / drag / number-key items into any crate GUI: refused.
12. Quit during Spin: rejoin → reward delivered once. Quit during Select 3: no key used.
13. While a Select screen is open, edit the crate in `/crateeditor` (or reload): Confirm refuses with "This crate changed".
14. Two players opening crates at once: each sees only their own GUI and result.
