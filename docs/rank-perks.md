# Purchasable rank perks and permissions (issue #1381)

An audit of what JoshyMC itself does for the purchasable ranks, on Survival and Lifesteal.

**What this can and can't confirm.** The live LuckPerms groups (their nodes, parents, contexts,
prefixes) are not in this repository. Everything below marked **CURRENT** is what the plugin code
and the bundled `config.yml`/`plugin.yml`/`kits.yml`/`tags.yml` do. Which nodes each LuckPerms group
really holds can only be read on the servers (`/lp group <rank> permission info`, `/lp group <rank>
parent info`, `/lp tree joshymc`). Nothing here is a confirmed live group assignment.

Checks: `./gradlew -I scripts/rank-perk-check.init.gradle.kts rankPerkCheck`.

## A. Rank inventory

### Survival

| # | Rank | LuckPerms group (expected) | JoshyMC tier id | How it's obtained |
|---|---|---|---|---|
| 1 | Scout | `scout` | `scout` | Store / Rank Voucher / Credit Shop; free once per gamemode via `/freerank` |
| 2 | Pathfinder | `pathfinder` | `pathfinder` | Store / Rank Voucher / Credit Shop |
| 3 | Lumberjack | `lumberjack` | `lumberjack` | Store / Rank Voucher / Credit Shop |
| 4 | Trailblazer | `trailblazer` | `trailblazer` | Store / Rank Voucher / Credit Shop |
| 5 | Voyager | `voyager` | `voyager` | Store / Rank Voucher / Credit Shop |
| 6 | Ranger | `ranger` | `ranger` | Store / Rank Voucher / Credit Shop |
| 7 | Pioneer | `pioneer` | `pioneer` | Exclusive. The code has no "not purchasable" flag: a Pioneer Rank Voucher, Credit Shop package or `/purchase fulfill … rank pioneer` would grant it. Keeping it exclusive is a store/staff decision. |

The order lives only in `DonorTiers.ORDER`. A player's tier is `rankManager.currentDonorTier(uuid)`:
the highest of JoshyMC's `player_ranks` donor records and, while online, their live `group.<tier>` /
`joshymc.rankperk.<tier>` nodes (ops and `*` holders only count explicitly granted nodes, so staff
never look like Pioneer). Grants are one `lp user <uuid> parent add <tier>`, never `parent set`.

**Special ranks** (`media`, `discord`) are not donor tiers (`DonorTiers.isTier` is false), never
take part in tier comparisons or upgrades, and their kits are the Special Kits category in `/kit`.
Their eligibility (Media application, Discord link group `discord.linking.verified-group`) is
separate and untouched.

**Staff ranks** (`ranks.list`: owner, admin, lead_mod, mod, jr_mod, helper, trainee, vip, member,
default) are JoshyMC display ranks. None of them is a donor tier.

### Lifesteal

**Since issue #1428** Lifesteal has its own rank system (`lifesteal/ranks/`, `lifesteal-ranks.yml`,
`docs/lifesteal-ranks.md`), separate from `DonorTiers` (which stay Survival-only). It ships only the
two approved names, Rogue (free via `/freerank` on Lifesteal) and Legacy (staff grant only), with no
perks, requirements or store packages until the Owner approves them. `FreeRanks.starterRank(LIFESTEAL)`
is still null because Rogue isn't a donor tier; `/freerank` on Lifesteal routes to the Lifesteal rank
system instead.

## B. Perk inventory (CURRENT)

| Perk | How it's decided | Per-rank values in the repo |
|---|---|---|
| Auction House extra listings | `rank-perks.<tier>.auction-extra-listings` for the highest `joshymc.rankperk.<tier>` the player has | Scout 2, Pathfinder 5, Lumberjack 8, Trailblazer 12, Voyager 16, Ranger 20, Pioneer 25 (+ `auction.max-listings-per-player`) |
| Buy Order extra orders | `rank-perks.<tier>.orders-extra-orders` (same perk) | 2 / 5 / 8 / 12 / 16 / 20 / 25 (+ `orders.default-active-order-limit` 5) |
| Sell multiplier (/sell, Sell Wand) | `rank-perks.<tier>.sell-multiplier` (same perk) | 1.05 / 1.10 / 1.15 / 1.20 / 1.25 / 1.30 / 1.40 |
| Rank kits | `/kit`, kit node `joshymc.kit.<kit>` (kits.yml `permission`); Rank Kits listed in `kits.rank-order.survival` | Kit contents and cooldowns are in the server's `kits.yml` (the bundled one ships example kits, not tier kits) |
| Kit cooldown bypass | `joshymc.kit.bypass.<kit>`, `joshymc.kit.bypass.*`, `joshymc.kit.cooldown.bypass` (op) | none for donors in the repo |
| Homes | `homes.limits-by-rank.<rank>` (now also `<tier>`, see E), else `homes.max-per-player` 3; `joshymc.sethome.unlimited` (op) | no tier entries bundled |
| Player warps | `warps.limits-by-rank.<rank>` (now also `<tier>`), else `warps.player-max-per-player` 3 | no tier entries bundled |
| Player vaults | `/pv` needs `joshymc.pv` (op by default); count = highest `joshymc.pv.<n>` up to `vaults.max` (10), else 1 | set in LuckPerms only |
| Flight | `joshymc.fly` (op) = permanent `/fly`; temporary flight = LuckPerms timed `joshymc.fly` node, which is also what Fly Vouchers grant (issue #1408; `/flytime` shows it); `joshymc.fly.pvp` / `.others` op | set in LuckPerms only |
| Monthly Credits | `/mcr` needs `joshymc.mcr` (default false), 500 Credits / 30 days | meant for Ranger (+ Pioneer by inheritance) |
| Sign item | `/signitem` needs `joshymc.sign.item` (default false), once a week | set in LuckPerms only |
| Nicknames | `/nick` needs `joshymc.nick` (op); colors/filter/others are separate op nodes | set in LuckPerms only |
| Chat color | `/chatcolor` `joshymc.chatcolor`, special colors `joshymc.chatcolor.special` (op) | set in LuckPerms only |
| Chat tags | each tag's `permission` in tags.yml, or a Chat Tag Voucher unlock | bundled tags have `permission: null` (free) |
| Kill effects / cosmetics | each effect's permission; hub `joshymc.cosmetics` (true) | crate/voucher unlocks |
| Convenience commands | `/hat`, `/craft`, `/anvil`, `/smithing`, `/stonecutter`, `/enderchest`, `/repair hand|all`, `/heal`, `/feed` (all op by default) | set in LuckPerms only |
| Chat prefix | LuckPerms prefix of the group (JoshyMC's own rank tags are `ranks.list`) | set in LuckPerms only |

Bonuses from `rank-perks` never stack: only the single highest eligible tier counts.

## C. Verified defects

1. **Donor tiers were ignored by home and player-warp limits.** `homes.limits-by-rank` and
   `warps.limits-by-rank` were looked up only for JoshyMC `ranks.list` ranks
   (`RankManager.getPlayerRankIds`). Donor tiers live in LuckPerms and are deliberately not in
   `ranks.list`, so a `limits-by-rank.voyager: 8` entry had no effect for any Voyager. **Fixed.**
2. **`/heal <player>` and `/feed <player>` needed only the self-use node.** Anyone given
   `joshymc.heal`/`joshymc.feed` (the self cooldown makes them look like a donor perk) could heal
   or feed every other player, and the 3-minute cooldown only applies to self-use. **Fixed** with
   `joshymc.heal.others` / `joshymc.feed.others` (op).

## D. Findings that need a decision (REQUIRES APPROVAL, not changed)

| # | Finding | Recommendation |
|---|---|---|
| 1 | Upgrading a tier through JoshyMC removes the lower tier group JoshyMC recorded (`completeDonorGrant`). A higher group that doesn't inherit the lower ones loses their kit nodes and other perks. | Make each LuckPerms tier group a parent of the next (`pathfinder` → parent `scout`, …, `pioneer` → parent `ranger`) and give each group only its own new nodes. Check with `/lp group pioneer parent info`. |
| 2 | `joshymc.kit.*` (a natural "all kits" wildcard) also grants `joshymc.kit.bypass.*` and `joshymc.kit.cooldown.bypass`, i.e. no kit cooldowns. | Never give donor groups `joshymc.kit.*`; grant `joshymc.kit.<kit>` per kit. (Renaming the bypass nodes would silently strip staff bypasses, so they were left alone.) |
| 3 | `getRankPerkBonus` uses a plain `hasPermission`, so ops and `*` holders get Pioneer's AH/Buy Order bonus and 1.40 sell multiplier. The donor-tier resolver already ignores wildcards. | Decide whether staff should keep that economy bonus. If not, count only explicit `joshymc.rankperk.*` nodes for wildcard holders (same rule as `DonorTiers.liveTiers`). |
| 4 | Chat tags `[Voyager]` and `[Ranger]` in tags.yml are free (`permission: null`) and look exactly like donor ranks. | Rename them or put them behind a permission. Players may already wear them, so this needs approval. |
| 5 | Donor grants (`grantDonorTier`: vouchers, Credit Shop, `/purchase fulfill … rank`) never check the gamemode. Rank vouchers and the Credit Shop are SURVIVAL commands, but `/purchase` is NETWORK, so a store package pointed at the Lifesteal server would add a Survival tier group there. | Only fulfill Survival rank packages on the Survival server. If LuckPerms is shared between servers, give tier nodes a `server=survival` context so Survival perks never apply on Lifesteal. |
| 6 | Pioneer has no code-level "exclusive" flag. | Don't create Pioneer vouchers/packages; if that must be enforced, add an explicit exclusive-tier rule (needs approval). |
| 7 | The bundled `kits.yml` has example kits (knight, warrior, samurai, champion, paladin) whose names don't match `kits.rank-order.survival`, so on a fresh install they are listed after the (missing) tier kits. | Name the live tier kits after the tiers, or set `category: rank` and the right order. |
| 8 | Lifesteal has no rank system in the plugin (section A). **Implemented in issue #1428** (`docs/lifesteal-ranks.md`); the ranks between Rogue and Legacy and every perk value still need approval. | Kept out of `DonorTiers`, which is Survival-only. |

## Permission matrix

**CURRENT** = what the plugin code does. Live LuckPerms nodes are not verifiable from the repo.

| Rank | Tier node (CURRENT) | AH / Orders / Sell (CURRENT) | Kits, homes, vaults, fly, cosmetics | Security (CURRENT) | RECOMMENDED | REQUIRES APPROVAL |
|---|---|---|---|---|---|---|
| Scout | `group.scout`, `joshymc.rankperk.scout` | +2 / +2 / ×1.05 | LuckPerms only (`joshymc.kit.scout`, `joshymc.pv.<n>`, …) | no staff node reachable by default | parent: none | D3, D4 |
| Pathfinder | `group.pathfinder`, `joshymc.rankperk.pathfinder` | +5 / +5 / ×1.10 | LuckPerms only | same | parent `scout` | D3 |
| Lumberjack | `group.lumberjack`, `joshymc.rankperk.lumberjack` | +8 / +8 / ×1.15 | LuckPerms only | same | parent `pathfinder` | D3 |
| Trailblazer | `group.trailblazer`, `joshymc.rankperk.trailblazer` | +12 / +12 / ×1.20 | LuckPerms only | same | parent `lumberjack` | D3 |
| Voyager | `group.voyager`, `joshymc.rankperk.voyager` | +16 / +16 / ×1.25 | LuckPerms only | same | parent `trailblazer` | D3, D4 |
| Ranger | `group.ranger`, `joshymc.rankperk.ranger` | +20 / +20 / ×1.30 | + `joshymc.mcr` (intended) | same | parent `voyager` | D3, D4 |
| Pioneer (exclusive) | `group.pioneer`, `joshymc.rankperk.pioneer` | +25 / +25 / ×1.40 | inherits Ranger's `joshymc.mcr` (intended) | same | parent `ranger` | D6 |
| Media / Discord (special) | not tiers | none | Special Kits | separate | never parent a tier | none |
| Lifesteal ranks (Rogue, Legacy) | `group.rogue` / `group.legacy` (Lifesteal server only) | `lifesteal-ranks.yml` perks (none bundled) | rank kits + perks, Lifesteal worlds only (`docs/lifesteal-ranks.md`) | admin/staff/heart/ranked nodes refused | | D8 |

Every rank: never `*`, `joshymc.*`, `joshymc.kit.*`, `joshymc.admin*`, `joshymc.staff.*`,
`joshymc.punish.<level>`, `joshymc.eco`, `joshymc.give`, `joshymc.vouchers`,
`joshymc.rankvoucher.admin`, `joshymc.crate.admin`, `joshymc.rank*`, `joshymc.jgm.*`,
`joshymc.*.others`, `joshymc.*.bypass`, `luckperms.*`.

## Security (CURRENT, from plugin.yml)

- Every `joshymc.rankperk.<tier>` is `default: false` with no children.
- No `default: true` node is a staff/admin node or grants one through `children` (the check
  scans every node). `joshymc.punish` is true only as the base node: every type and level is
  checked separately and is op/false.
- Staff bundles (`joshymc.staff.*`, `joshymc.admin.helper`, `joshymc.punish.<level>`) are
  `default: false`/op and are never children of a donor node.
- Combat: `/heal` refuses combat-tagged targets and every player teleport goes through
  `CombatTeleportGuard` (#1356). No donor node bypasses either.

## Gamemode isolation (CURRENT)

- Rank perk commands are SURVIVAL in `GamemodeAssignments` (`/rankvoucher`, `/voucher`, `/cshop`,
  `/ah`, `/orders`, `/sell`, `/home`, `/sethome`, `/pwarp`, `/pv`, `/fly`, `/flytime`, `/chatcolor`,
  `/tag`, `/mcr`, `/signitem`, `/hat`, `/craft`, `/anvil`, `/enderchest`, `/repair`, …).
- `/kit` is NETWORK but every kit belongs to one gamemode and only that gamemode's kits are
  shown or claimable (#1379).
- Each server has its own SQLite database; donor records (`player_ranks`) never cross servers.
  Only LuckPerms (if shared) can carry a group across; use contexts (D5).

## Java and Bedrock

Every donor check is by UUID or live permission, never by name, so Floodgate (Bedrock) players are
resolved exactly like Java players. `grantDonorTier` addresses LuckPerms by UUID.

## E. Implemented fixes

- `manager/RankLimits.kt` (Bukkit-free): home and player-warp limits now also consider the
  player's current donor tier. It can only raise a limit: nothing changes unless an admin adds a
  `limits-by-rank.<tier>` entry (none are bundled), and a tier entry never lowers a higher rank.
- `/heal <player>` / `/feed <player>` need `joshymc.heal.others` / `joshymc.feed.others`
  (op). `/heal <own name>` stays a self-heal. Tab completion only suggests players to holders.
  **Staff who heal/feed others through an explicit `joshymc.heal`/`joshymc.feed` LuckPerms node
  (not op, not a wildcard) need the new `.others` node added.**

## F. Audit #1505 P1 fixes (issue #1521)

### P1-1: `joshymc.fly` no longer exempts players from the anticheat fly check

`AntiCheatManager.checkFlight` used to skip the check for anyone holding `joshymc.fly`, whether
or not they had actually turned flight on, so a donor rank or a timed Fly Voucher node was also a
fly-hack exemption. The check now goes through the Bukkit-free `manager/FlightCheckRules`, which
only looks at **server-side flight state** (`allowFlight`, `isFlying`, elytra gliding) and the
explicit staff node `joshymc.anticheat.fly.bypass` (op, never a donor node, no longer described as
implied by `joshymc.fly`).

- `/fly` (permanent `joshymc.fly` or Fly Voucher time) sets `allowFlight`, so legitimate flight is
  still exempt while it's on. Creative/Spectator, ModMode, spawn flight and claim-flight talismans
  set it too.
- When timed flight runs out, `FlightTimeManager` clears `allowFlight` after the landing grace, so
  an expired voucher can't keep an exemption. Restores on join/world change/gamemode switch only
  happen through `FlightRestore` with `restoreBlock(player) == null` (needs `joshymc.fly` or timed
  flight, Survival not locked away, not combat-tagged/arena/event/ranked).
- Survival flight is stripped on arriving in a non-Survival world (`stripOutsideSurvival`).
- Limitation: a *permanent* `joshymc.fly` node removed in LuckPerms while the player is online and
  flying leaves `allowFlight` on until they toggle `/fly`, reconnect or change gamemode (vanilla
  resets it and the restore refuses). JoshyMC can't strip it on its own because other systems
  (talismans, ModMode, spawn flight, other plugins) also set `allowFlight`. Use `/fly <player>`.

### P1-2: self `/heal` and `/feed` are Survival only, enforced in the plugin

`/heal` and `/feed` stay NETWORK commands (staff use them on every server), but using them **on
yourself** is a Survival donor perk. The executors now ask the Bukkit-free
`gamemode/SurvivalSelfPerks` with the target's gamemode (`gamemodeLockManager.gamemodeOf`):
self-use outside Survival is refused with "This command is only available on Survival.", before
the cooldown is used. No permission is an input, so global LuckPerms groups, a shared LuckPerms
database, `joshymc.heal.bypass` and wildcards (`*`, op) can't override it. Aliases, `joshymc:heal`,
`/sudo`, NPC and portal commands all run the same executor.

- Survival self-use, its 3-minute cooldowns and `.bypass` are unchanged.
- Healing/feeding **another** player still needs `joshymc.heal.others` / `joshymc.feed.others`
  (staff), and still refuses combat-tagged targets (`/heal`) and ranked participants.
- The `/admin` panel's Heal button is an audited Admin-tier staff tool and is unchanged.
- Ordinary food and natural regeneration are untouched (only the two commands changed).

Checks: `./gradlew -I scripts/rank-security-check.init.gradle.kts rankSecurityCheck`.

### LuckPerms contexts (recommended, not applied by the plugin)

The plugin doesn't know whether production LuckPerms storage is shared, and it never changes
groups or parents. Donor grants (`grantDonorTier`) and the Discord verification group add global
parent nodes. The code-level gates above work even when donor permissions are global. To also keep
other Survival perks off Lifesteal when storage is shared:

1. Give each server a distinct `server:` in its LuckPerms `config.yml` (e.g. `survival`,
   `lifesteal`).
2. Put Survival-only perk nodes on the tier groups with that context, e.g.
   `/lp group scout permission set joshymc.heal true server=survival`, then unset the global one
   with `/lp group scout permission unset joshymc.heal`. Don't move players' parents.
3. Keep player parents (tier, Media, Discord) as they are; only the groups' own nodes get the
   context. Verify with `/lp user <name> permission check joshymc.heal` on each server.
4. Never give donor groups `joshymc.anticheat.*`, `joshymc.*` or `*`.
