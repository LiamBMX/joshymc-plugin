# Lifesteal ranks, rank perks, rank kits and progression (issue #1428)

Lifesteal only. Everything here runs only on a server that hosts Lifesteal, and every perk, kit and
permission it hands out applies only while the player is in a Lifesteal world. Survival ranks
(the `DonorTiers` Scout → Pioneer), Survival kits, Survival perks and `/freerank` on Survival are
unchanged.

Checks: `./gradlew -I scripts/lifesteal-rank-check.init.gradle.kts lifestealRankCheck`.

## Rank hierarchy used

The repository only has two approved Lifesteal rank names:

| Order | Rank | Source of the name | How it's obtained (shipped) |
|---|---|---|---|
| 1 | **Rogue** | issue #1295 (`/freerank` on Lifesteal gives Rogue) | Free, once per player (`/freerank`) |
| 100 | **Legacy** | issue #1379 ("the Lifesteal rank kit hierarchy, ending with Legacy") | Staff grant only (`/lsranks admin grant`) |

No other Lifesteal ranks, perk values, requirements, prices, kit contents or store packages are
defined anywhere in the repository, so **none were invented**. `lifesteal-ranks.yml` ships Rogue and
Legacy with no perks, no requirements and not for sale. Adding the approved ranks between them (any
`order` from 2 to 99) and their values is a config change only.

## Decisions still needed (REQUIRES APPROVAL)

1. **The ranks between Rogue and Legacy**: names, order, colors, descriptions.
2. **How Legacy is obtained**: store (`acquisition.store: true`), progression (requirements), or
   staff only (current).
3. **Perk values per rank** (homes, vaults, sell bonus, AH listings, Buy Orders, kit cooldown
   reduction). All default to 0.
4. **Progression requirements** (playtime hours, PvP kills, different players killed) and whether
   they count per season.
5. **Kit contents** of each rank kit (`/createkit <rank>` on Lifesteal). No kit items ship, because
   no Lifesteal kit balance review exists and the issue forbids handing out PvP gear unreviewed.
6. **Store packages**: which ranks Tebex sells, with which reference placeholder (see below).
7. **Which Survival-scoped convenience commands Lifesteal gets.** `/home`, `/sethome`, `/pv`, `/ah`,
   `/orders`, `/sell`, `/chatcolor`, `/tag`, `/cosmetics` are SURVIVAL-scoped (`GamemodeAssignments`),
   so a dedicated Lifesteal server refuses them. Their Lifesteal perks are wired (see below) but only
   take effect once the Owner moves the command to NETWORK/LIFESTEAL.
8. **Seasonal reset policy**: none exists yet (no season rollover is implemented). Ownership is
   therefore permanent and the code can't wipe it (see "Seasons").
9. **`/ranks`** is already an alias of the staff `/rank` command (both servers), so the Lifesteal
   command is **`/lsranks`** (aliases `/lifestealranks`, `/lranks`). Repurposing `/ranks` would
   change the staff command and needs a decision.

## Configuration (`lifesteal-ranks.yml`)

Created on the first start of a server that runs Lifesteal; never on Survival. Parsed by the
Bukkit-free `lifesteal/ranks/LifestealRankConfig`. Any error makes the whole file invalid: rank
perks, rank kits and new grants pause, and owned ranks are kept. `/lsranks admin problems` lists the
errors, `/lsranks admin reload` re-reads it.

| Key | Meaning |
|---|---|
| `enabled` | Master switch (off = no perks, kits or grants; ownership kept) |
| `luckperms.server-context` | Empty = global group nodes. Set this server's LuckPerms `server` name when LuckPerms is shared with Survival |
| `disabled-perks` | Perks turned off for every rank without editing values |
| `kits.refuse-while-combat-tagged` | Refuse Lifesteal kit claims in combat (default true) |
| `ranks.<id>.display-name / color / order / description / enabled` | Identity, hierarchy position (unique order), availability |
| `ranks.<id>.group` | The LuckPerms group added (default = id). Staff groups (`staff-ranks.groups`), Survival donor tiers, `ranks.list` ids, `media`, `discord`, the Discord verified group and `default` are refused |
| `ranks.<id>.acquisition.free / progression / store` | How it may be obtained (staff can always grant an enabled rank) |
| `ranks.<id>.requirements` | `playtime-hours`, `pvp-kills`, `unique-victims` |
| `ranks.<id>.requirements-scope` | `lifetime` or `season` (PvP since the season's launch) |
| `ranks.<id>.permissions` | JoshyMC player nodes, applied by JoshyMC itself in Lifesteal worlds |
| `ranks.<id>.kits` | Kit names (kits.yml, `gamemode: lifesteal`) for this rank and higher |
| `ranks.<id>.perks` | See "Benefits" |
| `ranks.<id>.cosmetics` | `prefix` (shown in `/lsranks`), `chat-color`, `icon`, `unlocks` (text lines) |

Individual kits are turned off with `enabled: false` in kits.yml (Lifesteal kits only); individual
ranks with `enabled: false`; individual perks with `disabled-perks`. Nothing deletes player data.

## Hierarchy and inheritance

A player's **current rank** is the highest-order enabled rank they own. They get the perks,
permissions and kits of every enabled rank at or below it (`LifestealRankRules.inherited`), so higher
ranks always include the lower ranks' benefits. Numeric perks take the largest value among those
ranks (they never stack). Ownership is cumulative: an upgrade adds the higher rank and keeps the lower
ones (and their LuckPerms groups), so no LuckPerms parent chain is needed and nothing is ever removed
by an upgrade. A disabled rank stops counting; the next owned enabled rank takes over.

## Benefits

| Perk | Range | Where it applies (Lifesteal worlds only) |
|---|---|---|
| `homes` | 0-100 | `/sethome` limit = max(`homes.max-per-player`, perk) |
| `vaults` | 0-54 (capped at `vaults.max`) | `/pv` count = max(`joshymc.pv.<n>`, perk) |
| `sell-bonus-percent` | 0-25 | Sell multiplier (all sell paths, via `RankManager.getRankPerkBonus`) |
| `auction-extra-listings` | 0-100 | Auction House listing cap |
| `order-extra-orders` | 0-100 | Buy Order cap |
| `kit-cooldown-reduction-percent` | 0-50 | Lifesteal rank kit cooldowns (never below 1 minute) |

In a Lifesteal world the Lifesteal perk **replaces** the Survival rank perk (`getRankPerkBonus`), so
a Survival donor group on a shared LuckPerms gives no Lifesteal bonus. `permissions` are applied with
a Bukkit permission attachment only while the player is in a Lifesteal world (removed on leaving,
quitting, reload and shutdown), never written to LuckPerms.

### Pay-to-win prevention

Refused by name in `perks:` (the file becomes invalid): hearts / max hearts / heart limit, damage,
damage reduction / defense / resistance, invulnerability / god, illegal enchants, combat-tag bypass,
Elo / rating / ranked bonuses, heart-steal immunity. Ranks never touch hearts, `HeartStore`, combat
tags, Ranked matches or Elo. Rank kits can't be claimed in combat (configurable), during a Ranked
match, in the event world or in Mod Mode.

Refused in `permissions:` (`LifestealRankRules.forbiddenNodeReason`, re-checked when applied):
wildcards, negations, `op`, every non-JoshyMC node (`luckperms.`, `minecraft.`, `bukkit.`, other
plugins), and JoshyMC admin/staff/punishment/economy-admin/credits-admin/heart/ranked/progression/
combat/gamemode/rank/rank-perk/kit/event nodes, anything with `admin`, `others`, `bypass`, `staff`,
`punish`, `override`, `recover`, and the combat-relevant self-help commands (`heal`, `feed`, `god`,
`fly`, `speed`, `enchant`, `vanish`, `tp`, ...).

## Rank kits and the kit menu

`/kit` keeps its three categories (issue #1379): **Starter Kits**, **Rank Kits**, **Special Kits**
(`category: special` in kits.yml for event/special-rank kits). On Lifesteal, Rank Kits are the kits
listed under each rank's `kits`, in hierarchy order (when `config.yml > kits.rank-order.lifesteal` is
empty). Each icon shows the kit name, required rank ("or higher"), number of item stacks, effective
cooldown and Ready / remaining / Locked; right-click previews (read-only, items can't be taken).

Eligibility for a Lifesteal **rank** kit is decided only by the rank hierarchy, never by a permission
node, so a shared or inherited LuckPerms node can't unlock it. Other Lifesteal kits keep their kits.yml
permission. Every Lifesteal kit claim (`KitManager.claimLifestealKit`):

1. re-checks the kit, eligibility and cooldown at execution (GUI clicks included);
2. refuses while dead, in Mod Mode, in a Ranked match, in the event world or combat-tagged;
3. simulates the delivery on a copy of the inventory and refuses unless **everything** fits
   (nothing is dropped or discarded, nothing is used up);
4. reserves the cooldown in one conditional statement on `kit_cooldowns`
   (`LifestealRankStore.reserveKitClaim`) **before** giving anything, so a double click, alias,
   reconnect or second server thread can't claim twice, and a crash can only lose a claim;
5. gives the items and saves the player.

Cooldowns live in `kit_cooldowns` (persist through restarts). Survival kits keep their old claim path.

## Progression

Requirement types (`LifestealRankConfig.RequirementType`): `playtime-hours` (this server's playtime
table, always lifetime; note AFK time counts), `pvp-kills` and `unique-victims` (only Lifesteal PvP
kills that **moved a heart**: the heart system records `TRANSFERRED` only after grace, teammates and
allies, the same-victim cooldown and the victim's minimum passed, so self-kills, team farming and
repeat kills on one player don't count; `unique-victims` counts each player once). Ranked matches are
never required. A statistic that can't be read never counts. A progression rank needs at least one
requirement (otherwise the file is invalid). Players claim it in `/lsranks` (or `/lsranks claim
<rank>`); the requirements are re-read on the server at that moment.

## Grants, upgrades and LuckPerms

Every grant or revoke (`LifestealRankStore`):

1. is recorded `PENDING` in `lifesteal_rank_ops` **before** LuckPerms is asked, in one transaction
   that re-reads ownership (`LifestealRankRules.decideGrant`: system/rank enabled, offered for that
   source, not already owned, no higher rank owned = no downgrade, nothing pending). A partial UNIQUE
   index allows one PENDING change per player; `reference` is UNIQUE (a repeat is a Duplicate);
2. then adds/removes **exactly one** parent node by UUID through the LuckPerms API
   (`LuckPermsGroups.setGroup`, works offline, global or `server=<context>`). Never `parent set`,
   never another group: staff, special, Discord-linked and network groups are untouched;
3. is marked `COMPLETED` with the ownership row (`lifesteal_rank_ownership`) only after LuckPerms
   saved. LuckPerms unavailable = stays PENDING (retried every minute); a LuckPerms error counts an
   attempt, FAILED after 5 (`/lsranks admin retry`). A change PENDING at a crash is retried (adding
   one group is idempotent). A pending grant for a rank removed from the file is cancelled, never sent.

Every step is written to the append-only `lifesteal_rank_audit` (triggers refuse UPDATE/DELETE).

## Store fulfillment

New `/purchase fulfill` type: `lsrank <rank>`, e.g.
`purchase fulfill {id} lsrank legacy {transaction}`. It is only accepted on a server that hosts
Lifesteal, for an enabled rank with `acquisition.store: true` (otherwise the line is REJECTED and
recorded, nothing granted). The purchase row (`purchase_deliveries`, unchanged dedupe rules) drives a
rank change with reference `purchase:<row id>`, so store retries, requeues after a crash and the
1-minute sweep always find the same change. The row is COMPLETED only once LuckPerms saved the group;
it works while the buyer is offline (no online-only "success"). Buying a rank already owned, or lower
than one owned, FAILS the row with a note and grants nothing. Upgrades are cumulative (the lower rank
stays). No new fulfillment command was added. Rank Vouchers and the Credit Shop stay Survival-only.

## `/freerank` on Lifesteal

Now gives the free Lifesteal rank (the lowest enabled rank with `acquisition.free`, Rogue) through the
same pipeline, reference `freerank:<uuid>`: once per player ever, even after a revoke. Survival
`/freerank` (Scout) is unchanged.

## Commands and permissions

| Command | Permission |
|---|---|
| `/lsranks` (GUI), `/lsranks info`, `/lsranks claim <rank>` | `joshymc.lifesteal.ranks.use` (true) |
| `/lsranks info <player>` | `joshymc.lifesteal.ranks.view.others` (op) |
| `/lsranks admin grant\|revoke <player> <rank> [reason]`, `history <player>`, `pending`, `failed`, `retry <id>`, `cancel <id> <reason>`, `reload`, `problems` | `joshymc.lifesteal.ranks.admin` (op) |

LIFESTEAL scope, only registered where Lifesteal is hosted. Admin grant/revoke refuse yourself and go
through `staffRankManager.protectionProblem` (only players strictly below you; console always), and
are logged to `admin_logs` (`AdminAudit`) and `lifesteal_rank_audit`. The GUI never sells or assigns:
its only button claims an earned progression rank, re-checked on the server.

## Seasons

There is no season rollover in the repository yet. Ownership rows can't be deleted (trigger) and
nothing season-related writes them, so seasons never reset purchased or earned ranks. `season`
requirement scope only changes which PvP kills count toward *new* progression claims. Permanent
cosmetic unlocks belong to the rank and stay with ownership. Seasonal rewards don't exist yet.

## Tables

`lifesteal_rank_ownership`, `lifesteal_rank_ops`, `lifesteal_rank_audit` (created only where
Lifesteal is hosted); kit claims reuse `kit_cooldowns`.
