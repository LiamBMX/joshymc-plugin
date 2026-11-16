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
| `/shop` | `shop.yml` sells PvP supplies (Ender Pearls, Golden Apples, End Crystals, Obsidian) and custom spawners for coins: unsafe for Lifesteal combat balance until a Lifesteal catalog is approved |
| `/invest`, `/stock`, `/market` | coins in stocks are a second, fee-free bank outside the Protected Bank's rules; `/market` is off on a dedicated Lifesteal server |
| `/orders`, `/order` | the `orders` feature never starts on a dedicated Lifesteal server |
| `/tokens`, `/credits`, `/creditspay`, `/cshop`, `/sellwand` | Survival currencies, Credit Shop rank packages and a Survival custom item |
| `/pv`, `/enderchest`, `/trash`, `/hat`, `/craft`, `/anvil`, `/smithing`, `/stonecutter`, `/condense`, `/nightvision` | Survival rank perks; remote storage is an undecided Lifesteal design (team progression keeps its Storage category off) |
| `/warp`, `/pwarp`, `/rtp`, `/top`, `/resource` | `/warp` holds Survival warps (e.g. the PvP world); `/rtp` picks worlds by the Survival layout; `/top` is an escape |
| `/eco`, `/phome`, `/setwarp`, `/delwarp`, `/editwarp`, `/crateeditor`, `/customenchant` | staff/admin only |
| everything else SURVIVAL | depends on Survival systems (claims, crates, cosmetics, gambling, events, quests, milestones, ...) |

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
commands). Live testing on a Lifesteal server is still to be done.
