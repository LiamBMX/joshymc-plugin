# Shared commands on the Lifesteal server (issue #1573)

## Why `/sell` said "That command is only available on Survival."

A dedicated Lifesteal server (`gamemode-lock.server-gamemode: lifesteal`, no world mapped to
`survival`) isolates Survival automatically (issue #1336). Every command whose scope in
`GamemodeAssignments` is `SURVIVAL` is then refused there by the command gate
(`GamemodeLockManager.GatedCommand`). `/sell`, `/balance`, `/home` and the other economy and
travel basics were all scoped `SURVIVAL` in issue #1329, before Lifesteal was its own server, so
they were refused even though they only use the Lifesteal server's own database and config.

## What changed

`gamemode/LifestealSharedCommands.SHARED` lists SURVIVAL commands that also run in Lifesteal **on a
server that hosts only Lifesteal** (so its economy and homes are Lifesteal's, the same rule as
`TutorialRules.economyBelongsTo`). Nothing else moves:

- Survival stays locked; `GamemodeIsolation.allowed` is unchanged. The exception is one extra
  step in `GamemodeLockManager.canUse`, so typed commands, aliases, `joshymc:` names, tab
  completion, the client's command list and dispatched/GUI commands all follow it.
- The commands keep their `SURVIVAL` scope, so in Lifesteal **worlds of a mixed server** (whose
  economy is Survival's) they stay refused, and the Survival server is unchanged.
- No permission is granted or changed: each command's own Bukkit permission still applies.

| Command (aliases) | Guard | Notes |
|---|---|---|
| `/balance` (`/bal`, `/money`) | none | this server's wallet |
| `/baltop` (`/balancetop`) | none | |
| `/worth` | none | prices from this server's `sell-prices.yml` |
| `/sell` | competitive | the normal `SellPriceManager` path; Lifesteal rank perks apply in Lifesteal worlds |
| `/pay` | competitive | refused in combat like bank deposits, so coins can't be shielded from PvP coin stealing |
| `/ah` (`/auction`, `/auctionhouse`) | competitive | |
| `/overflow` | competitive | withdraw-only; where AH deliveries go when an inventory is full |
| `/trade` | competitive | shift + right-click trading already worked on Lifesteal; also refuses combat itself |
| `/home` (`/h`), `/sethome` | competitive | Lifesteal rank `homes` perk sets the limit (issue #1428); teleports still go through `TeleportChecks` |
| `/delhome` | none | |

**Competitive guard**: on Lifesteal the command is refused while the player is combat-tagged, or
is held by, spectating, or waiting on an inventory restore from a Ranked Team Wars match. It
runs in the executor wrapper, so every route in is covered; a check that can't run refuses. The
existing protections still apply on top: the typed-command combat list (`CombatListener`), the
ranked listener (only private messages while held), `CombatTeleportGuard`, the End lock and the
ranked arena access rules.

Already working on Lifesteal before this change (NETWORK or unassigned): `/msg`, `/reply`,
`/ignore`, `/discord`, `/rules`, `/help`, `/kit` (Lifesteal kits only), `/spawn`, `/tpa`,
`/tpaccept`, `/tpdeny`, `/team`, `/bounty`, `/quests`.

## Still restricted on Lifesteal, and why

| Commands | Reason |
|---|---|
| `/fly`, `/repair` (`/fix`), `/back` | the issue's blacklist (see below) |
| `/invest`, `/stock`, `/market` | coins in stocks are a second, fee-free bank outside the Protected Bank's rules; `/market` is off on a dedicated Lifesteal server |
| `/orders`, `/order` | the `orders` feature never starts on a dedicated Lifesteal server |
| `/tokens`, `/credits`, `/creditspay`, `/cshop`, `/sellwand` | Survival currencies, Credit Shop rank packages and a Survival custom item |
| `/pv`, self `/enderchest`, `/trash`, `/hat`, `/craft`, `/anvil`, `/smithing`, `/stonecutter`, `/condense`, `/nightvision` | Survival rank perks; remote storage is an undecided Lifesteal design (team progression keeps its Storage category off). Staff `/enderchest <player>` is shared since issue #1599 (read-only) |
| `/pwarp`, `/top`, `/resource` | player warps are Survival's; `/top` is an escape; the resource world doesn't run on Lifesteal |
| `/phome`, `/customenchant`, the rest of `/event`, `/voucher` and `/enderchest` | staff tools Lifesteal doesn't need yet (Survival homes, Survival gear, hosted events, Credit Shop packages, Fly Vouchers) |
| everything else SURVIVAL | depends on Survival systems (claims, crates, cosmetics, gambling, events, quests, milestones, ...) |

## Issue #1599: staff commands and approved player utilities

### Player utilities (in `SHARED`, competitive guard)

| Command | Notes |
|---|---|
| `/warp` (`/warps`) | this server's own warps (set by Lifesteal staff). `TeleportChecks` (frozen, combat tag incl. ranked hold via `CombatTeleportGuard`, cooldown); on Lifesteal the destination chunk is loaded off the main thread without generating terrain, then the combat check and `SafeSpots` run again before the teleport. The End progression lock and ranked arena access refuse the teleport event itself |
| `/rtp` (`/wild`, `/randomtp`) | unchanged executor: `TeleportChecks`, the 3 s warmup re-checks the combat tag every tick, its own safe-spot search, never in/into the End without `joshymc.rtp.end` (and the End lock still refuses before the End milestone) |
| `/shop` | the server shop **minus competitive PvP supplies in Lifesteal worlds** (`LifestealSharedCommands.LifestealShopRules`): no potions, spawners, weapons, armor, shields, bows/tridents/maces, End Crystals, Respawn Anchors, Obsidian/Crying Obsidian, Glowstone, Ender Pearls, Chorus Fruit, Golden Apples, Totems, XP bottles, TNT, arrows, rockets, cobwebs, wind charges, Elytra, enchanted books, diamonds/netherite. Hidden from the menu (empty categories too) and refused again at the buy click, together with the combat/ranked guard, since a shop screen can stay open into a fight. Applies to whatever the live shop.yml contains |

`/sell`, `/balance` and `/baltop` were already shared (issue #1573) and are unchanged.

### Staff commands (`ADMIN_SHARED`)

They keep their SURVIVAL scope (Survival and mixed servers unchanged) and **every check of their own
executor still runs** (permission nodes, `CratePermissions`, staff protection, ranked refusals,
`AdminAudit`). On a dedicated Lifesteal server the gate adds: only the listed forms run (anything else:
"On Lifesteal only ... is available"), tab completion offers only those forms, and some need a staff
rank (`staffRankManager.rankOf`: explicitly granted staff LuckPerms groups, ops = Owner; donor and
Lifesteal ranks never count). The console always passes; command blocks never pass a rank floor.

| Command | Forms on Lifesteal | Permission (unchanged) | Staff rank floor |
|---|---|---|---|
| `/setwarp`, `/delwarp`, `/editwarp` | all | `joshymc.setwarp` / `.delwarp` / `.editwarp` (op) | Admin+ |
| `/eco` | all (`give`; `take/remove/set/reset` stay op/console only) | `joshymc.eco` (op) | Owner |
| `/crate` | all; players keep `menu/browse/help/list/preview` and the browser | each admin subcommand's own `CratePermissions` node | Admin+ for every admin subcommand |
| `/crateeditor` | all | `joshymc.crateeditor` (op), re-checked per click | Admin+ |
| `/event` | only `inventory list\|info\|apply\|resolve` (listed only for holders of `joshymc.event.inventory.recover`) | `joshymc.event.inventory.recover` (op) | none |
| `/enderchest` | only `<other player>`, opened as a **read-only copy** (audited `ENDERCHEST_VIEW`); self-use and `clear` stay Survival (listed only for holders of `joshymc.enderchest.others`) | `joshymc.enderchest` + `.others` (op) | none |
| `/voucher` | only `give <player> chattag_<id> [amount]` and `tag debug <id> [player]`; Fly Vouchers, Credit Shop packages and every editing subcommand stay Survival | `joshymc.vouchers` (op) | none |

Lifesteal `/setwarp` refuses a spot a player can't safely stand on. `/crate givekey` and chat-tag
`/voucher give` skip a player held by a ranked match or waiting for its inventory restore
(`RankedIntegrity.StaffAction.ITEM_GRANTS`), since the restore would replace the item.

Data stays the Lifesteal server's own: warps, balances, crates.yml, `crate_*` tables, Ender Chests and
event snapshots are read from this server's config and data.db; nothing is copied from Survival.
Lifesteal's crates (Supply, Warrior, Soul, Relic, Victory, Heartless, Event) are created with
`/crateeditor` and placed with `/crate setlocation`, like on Survival.

### Retesting on the Lifesteal server

1. As a regular player: `/shop` (no PvP & Gear items except the pickaxe, no crystals/pearls/obsidian),
   `/sell`, `/bal`, `/baltop`, `/warp`, `/rtp` work; while combat-tagged `/warp`, `/rtp`, `/shop` are
   refused; `/fly`, `/back`, `/repair`, `/claim`, `/pv`, `/eco`, `/setwarp`, `/event`, `/enderchest` are
   refused/not tab-completed; `/crate` opens the browser only.
2. As an Admin (staff group mapped in `staff-ranks.groups`, with the nodes): `/setwarp test` at a safe
   spot, `/warp test`, `/editwarp test`, `/delwarp test`; `/crate setlocation <type>`, `/crate display`,
   `/crateeditor`, `/crate givekey <type> <player>`, `/crate rewards review`.
3. As a Jr Admin or lower with the same nodes: the commands above say they need the Admin rank.
4. As the Owner (or console): `/eco give <player> 100`; as an Admin with `joshymc.eco`: refused.
5. Recovery: `/event inventory list`, `/enderchest <player>` (items can't be moved), `/voucher give
   <player> chattag_<id>`; `/voucher give <player> fly:1h` and `/event start` are refused.
6. On the Survival server everything behaves as before.

## Issue #1601 phase 1: cosmetics and crates

### How cosmetic ownership is stored (checked before changing access)

| Cosmetic | Owned through | Equipped choice stored in |
|---|---|---|
| Chat tags (`/tag`, `/tags`) | the tag's own permission node, or a `chat_tag_unlocks` row (redeemed Chat Tag Voucher) | `player_tags` |
| Chat colors (`/chatcolor`) | `joshymc.chatcolor` / `joshymc.chatcolor.special` | `chat_colors` |
| Kill effects (`/killeffect`) | `joshymc.killeffect.<effect>` | `player_kill_effects` |
| Nicknames (`/nick`) | `joshymc.nick` (+ `.others`, `.realname`, `.bypassfilter`, `.noprefix`; already NETWORK, unchanged) | `nicknames` |

All tables are in **this server's own** `data.db`; Survival and Lifesteal share nothing. So:

- Permission-based ownership (every chat color, every kill effect, permission chat tags) follows the
  player to Lifesteal **if** LuckPerms grants that node there (global, or `server=lifesteal`).
- Chat tags unlocked by redeeming a voucher **on Survival** are rows in Survival's database and are
  not visible on Lifesteal. Nothing copies them: a sync needs an explicit, Owner-approved mechanism
  (see Owner decisions). Vouchers redeemed on Lifesteal unlock there and persist across
  reconnects/restarts like on Survival.
- The equipped tag/color/effect is per server (a player picks them again on Lifesteal once).

### What changed

- `/tag` (`/tags`, `/chattag`), `/chatcolor` (`/cc`), `/killeffect` (`/ke`) are in `SHARED` with no
  competitive guard: they only choose among the player's own unlocked cosmetics. Same permissions
  as before (`joshymc.tag`, `joshymc.chatcolor`, `joshymc.killeffect` + the per-cosmetic nodes),
  same SURVIVAL scope, so mixed servers and Survival are unchanged. Typed during a ranked match they
  are still refused by the ranked command rule. `/cosmetics` (the hub with trails, glow, gadgets,
  etc.) and other unselected cosmetics stay Survival only.
- **Kill effects are cosmetic only.** Firework Pop spawned a real firework with effects, whose
  explosion damages everything within a few blocks (also on Survival). Its firework is now tagged
  before it spawns, never saved with the chunk, and its damage is cancelled at LOWEST in every
  gamemode. All other effects are particles, sounds and `strikeLightningEffect` (no damage).
- **Crates never grant permanent hearts.** A crate reward that is a Lifesteal Heart item, or a
  shulker box/bundle holding one, makes the crate refuse to open before a key is used
  (`crateProblem`), and `/crateeditor` refuses to add one (audited `CRATE_EDITOR_REWARD_ADD`
  REJECTED `heart_item`). Before, a snapshot of a Heart item would hand out copies of one serial.
- Crate models: the bundled `crates.custom-models.gamemodes` is now `[survival, lifesteal]`. An
  existing config.yml keeps its list, so **the live Lifesteal server needs `lifesteal` added by
  hand** (then `/crate models refresh`).

Already working before this change (kept, regression-checked): chat tag and chat color rendering in
Lifesteal chat (CommunicationsManager reads them in every gamemode), Chat Tag Voucher redemption
(refused while a ranked snapshot is pending), kill effects playing on Lifesteal kills, `/nick`
(NETWORK), crate opening/keys/recovery, `/crate` + `/crateeditor` staff access (issue #1599).

### Tracking

| Feature | Before | After | Permission | Live testing required |
|---|---|---|---|---|
| `/tag`, `/tags` | refused ("Survival only") | works | `joshymc.tag` + per-tag node or unlock | yes |
| `/chatcolor` | refused | works | `joshymc.chatcolor`, `.special` | yes |
| `/killeffect` | refused | works | `joshymc.killeffect` + `joshymc.killeffect.<id>` | yes |
| `/nick` | working (NETWORK) | unchanged | `joshymc.nick*` | yes |
| Cosmetic display (tags/colors in chat) | working | unchanged | as above | yes |
| Kill effect playback | working, Firework Pop dealt damage | cosmetic only, all gamemodes | as above | yes |
| Chat Tag Vouchers | working | unchanged | `/voucher give` staff share (#1599) | yes |
| Legacy cosmetics | permission-based carry over via LuckPerms; Survival DB unlocks don't | unchanged (Owner decision) | — | yes |
| `/crate`, `/crateeditor`, recovery | working (#1599) | unchanged | `CratePermissions` nodes, Admin+ | yes |
| Crate rewards | Heart items could be added | refused | — | yes |
| Custom crate models | Survival worlds only (default) | bundled default includes Lifesteal; live config edit needed | `joshymc.crate.display` | yes |
| Supply/Warrior/Soul/Relic/Victory/Heartless/Event crates | not configured | not configured (Owner content) | — | yes |

### Owner decisions / configuration still needed

1. **The seven Lifesteal crates' contents.** Create them on the Lifesteal server with
   `/crateeditor` (ids e.g. `supply`, `warrior`, `soul`, `relic`, `victory`, `heartless`, `event`):
   rewards, weights, key items and prices are the Owner's; nothing was invented. Models for them
   need art (a separate request) or reuse an existing pack model with `/crate models set`.
2. **Survival voucher-unlocked chat tags on Lifesteal:** keep them per server (current), or approve
   a one-time export/import of `chat_tag_unlocks` (insert-or-ignore, so no duplicate grants).
3. LuckPerms: grant the cosmetic nodes players should keep on Lifesteal (e.g.
   `lp group <rank> permission set joshymc.killeffect.<id> true server=lifesteal`, or globally).

## The command blacklist

| Command | How it is enforced on Lifesteal |
|---|---|
| `/fly` | SURVIVAL scope, never shared: refused for everyone (staff included, as before) |
| `/repair`, `/fix` | SURVIVAL scope, never shared (`/fix` is `/repair`'s alias, same executor) |
| `/back` | SURVIVAL scope, never shared |
| `/heal`, `/feed` | self-use refused outside Survival for everyone (issue #1521); others = `joshymc.heal.others` / `joshymc.feed.others` (staff, protection + ranked checks) |
| `/god` | self-use in Lifesteal needs a **staff rank** (explicit staff LuckPerms group, ops = Owner; donor and Lifesteal ranks never are), whatever nodes a purchased rank carries; turning it off always works; others = `joshymc.god.others`. Kills in God Mode still earn nothing (issue #1487) |
| `/near` | not a JoshyMC command, see below |

Refused blacklisted commands say "/fly is disabled on Lifesteal." instead of "only available on
Survival". Lifesteal ranks can't carry `joshymc.repair*`, `joshymc.back` or `joshymc.god.*` either
(added to `LifestealRankRules.forbiddenNodeReason`, next to the existing fly/heal/feed/god nodes).

## External plugins

JoshyMC doesn't own `/near` (or any other plugin's `/fly`, `/god`, `/heal`, `/feed`, `/repair`,
`/back`). If EssentialsX (or similar) is installed on the Lifesteal server, either:

- LuckPerms: negate the plugin's nodes in the Lifesteal context for player groups, e.g.
  `lp group default permission set essentials.near false server=lifesteal` (and `essentials.fly`,
  `essentials.god`, `essentials.heal`, `essentials.feed`, `essentials.repair`, `essentials.back`
  if granted anywhere); or
- JoshyMC: on the **Lifesteal server's** config.yml, `gamemode-lock.commands: { near: survival }`
  makes the gate refuse and hide that command there (Survival is locked), aliases and `plugin:`
  forms included.

Neither is applied automatically; live configs aren't touched.

## Checks

`./gradlew -I scripts/lifesteal-shared-command-check.init.gradle.kts lifestealSharedCommandCheck`
(dedicated/mixed/Survival scenarios for every command and alias, the blacklist, purchased-rank
nodes, the combat/ranked guard, the gate wiring, regression of NETWORK/Lifesteal/unassigned
commands, and since issue #1599 the staff shares: forms, tab filtering, rank floors, visibility, the
Lifesteal shop filter against the bundled shop.yml, warp/rtp wiring). Live testing on a Lifesteal
server is still to be done.
