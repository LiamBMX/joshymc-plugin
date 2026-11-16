# Lifesteal Starter Kit and supply kits (issue #1577)

Lifesteal only. Everything here uses the existing kit system (`kits.yml`, `/kit`, `/createkit`,
`/editkit`, `kit_cooldowns`); Survival kits and the Survival claim path are unchanged.

## Lifesteal Starter Kit (bundled)

`kits.yml > kits.lifesteal_starter`:

| Key | Value |
|---|---|
| `gamemode` | `lifesteal` (shown only in Lifesteal worlds) |
| `category` | `starter` (the Starter Kit button on the `/kit` main menu) |
| `one-time` | `true` (one claim per player, ever) |
| `permission` | `joshymc.kit` (default true, so every player) |
| contents | full unenchanted iron armor, iron sword, pickaxe, axe, shovel, shield, 32 cooked beef, 32 oak logs, 32 torches, 1 water bucket (13 stacks) |

On a server that hosts Lifesteal, bundled Lifesteal kits missing from an existing `kits.yml` are
added once at startup (`KitManager.seedBundledLifestealKits`) and their names recorded in
`kits.yml > seeded-defaults`. A kit already in the file is never overwritten, and a kit the staff
deleted (`/deletekit`) or renamed is never re-added. Survival servers' `kits.yml` isn't touched.

### One-time kits

`one-time: true` (Lifesteal kits only; ignored on Survival kits) makes a kit claimable once per
player. The claim row in `kit_cooldowns` (PK uuid + kit) is reserved before anything is given and
never moved again (`LifestealKitRules.ONE_TIME`), so reconnecting, restarting, a double click or an
alias can't claim it twice. The cooldown bypass permissions (`joshymc.kit.cooldown.bypass`,
`joshymc.kit.bypass.*`) **don't** reopen a one-time kit. The menu shows "One claim per player" and
"Already claimed". To let one player claim it again, staff delete their row:
`DELETE FROM kit_cooldowns WHERE uuid = '<uuid>' AND kit = 'lifesteal_starter';`

## Supply kits (rank kits)

No rank kit rewards ship and no Survival kit is copied. To add one on the Lifesteal server:

1. Stand in a Lifesteal world and run `/createkit <name>`; the kit records `gamemode: lifesteal`.
   Put the items in, close the chest. Change contents later with `/editkit <name>`.
2. In `kits.yml`, set `cooldown-hours` (and optionally `icon`, `category: rank`, `enabled`).
3. Tie it to a rank: add the kit name to `lifesteal-ranks.yml > ranks.<rank>.kits`, then
   `/lsranks admin reload`. That rank and every higher one can claim it; the rank hierarchy alone
   decides, never a permission node (`docs/lifesteal-ranks.md`). A Lifesteal kit no rank lists uses
   its kits.yml `permission` instead.

Cooldowns are per player per kit in `kit_cooldowns` (SQLite, survive restarts and reconnects) and
are separate from Survival's (different kit names; on the dedicated Lifesteal server a separate
database). Rank kits get the `kit-cooldown-reduction-percent` perk.

## Every Lifesteal claim

`KitManager.claimLifestealKit`, refusal rules in the Bukkit-free `lifesteal/kits/LifestealKitRules`:

1. refuses while dead, in Mod Mode, in a Ranked match, spectating one, with a Ranked inventory restore
   still pending, in the event world, while an event inventory operation is unsettled
   (`EventInventoryKeeper.unsettled`: being reconciled, waiting for a retry, the player-file marker
   still set, or an open snapshot row), or combat-tagged (`lifesteal-ranks.yml >
   kits.refuse-while-combat-tagged`, default true). A check that throws refuses;
2. copies vouchers with a fresh id/signature (`freshVoucherCopy`); a template that can't be copied
   refuses the whole claim;
3. refuses unless every stack fits the storage slots (nothing dropped, nothing used up);
4. reserves the claim in one conditional statement before giving anything;
5. gives the items and saves the player.

## Owner steps

- Nothing is required for the Starter Kit: it is added on the next Lifesteal startup.
- Decide and add the rank supply kits (contents, cooldowns, which rank) as above.
- Optional: `config.yml > kits.rank-order.lifesteal` to order Rank Kits.

Checks: `./gradlew -I scripts/lifesteal-kit-check.init.gradle.kts lifestealKitCheck`.
