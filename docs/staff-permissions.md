# Staff Permissions Guide (issue #1383)

How Minecraft staff authority works in JoshyMC, for **Survival SMP and Lifesteal**. It builds
on the read-only Staff Permissions Audit (issues #1342 and #1353, Parts 1 to 4).

- **CURRENT** = what the code in this repository does now.
- **RECOMMENDED** = the safe set-up to aim for. Nothing here was applied to LuckPerms.
- **REQUIRES OWNER APPROVAL** = a policy change that only the Owner may decide.

Live LuckPerms groups and their permissions are **not in the repository**. Every statement about
which group holds which node is a recommendation until someone checks it with `lp group <g>
permission info` on each server. No LuckPerms command in this document has been run.

---

## 1. The Minecraft staff hierarchy

Highest first. This is **organizational authority**. It decides who may act on whom
(rank protection, §4). It does **not** mean a higher rank inherits every permission of the
ranks below it.

| # | Rank | Track | Role |
|---|---|---|---|
| 1 | Owner | Owner | Full ownership. Final say on ranks, permissions, configuration and high-risk operations. |
| 2 | Head Manager | Management | Overall staff and server management. Supervises Staff Manager and Community Manager. Gets extra administrative authority only when the Owner approves it. |
| 3 | Staff Manager | Management | Recruiting, onboarding, training, evaluations, activity monitoring, staff discipline, staff reports. |
| 4 | Community Manager | Management | Community engagement, feedback and suggestions, community activities (hosted events), community escalations, supervising community-facing moderation. |
| 5 | Lead Admin | Administration | Senior technical administration. Supervises admins. |
| 6 | Admin | Administration | General administration, player management, approved maintenance, investigations. |
| 7 | Jr Admin | Administration | Entry-level administration. Restricted high-risk operations. |
| 8 | Lead Mod | Moderation | Advanced moderation. Supervises moderators, reviews punishments, handles escalated reports. |
| 9 | Mod | Moderation | Standard moderation, disputes, temporary punishments, investigation tools. |
| 10 | Jr Mod | Moderation | Expanded moderation and reports. No bans. |
| 11 | Helper | Moderation | Player support, basic moderation, temporary mutes. |
| 12 | Trainee | Moderation | Learning. Warnings and staff chat only. |

**Not Minecraft staff:** Partnership Manager, Event Manager, Giveaway Manager and Build Manager
are Discord-only roles. They get no LuckPerms group. Media and Discord are special **player**
ranks, not staff ranks.

### The four kinds of authority

| Kind | Ranks | What it means |
|---|---|---|
| Operational moderation | Trainee to Lead Mod | Enforcing the rules on players: warnings, mutes, kicks, bans within their level, reports, Moderator Mode. |
| Technical administration | Jr Admin to Lead Admin | Running the server: the `/admin` panel, world and portal tools, crates, kits, shops, and (Lead Admin) reloads. These ranks build on Lead Mod's moderation. |
| Management | Community Manager, Staff Manager, Head Manager | Managing **people**: staff discipline (Staff Manager), community and events (Community Manager), both (Head Manager). Each gets **Lead Mod moderation plus role-specific nodes**, and **no** economy, item-generation, Creative, configuration or LuckPerms power by default. Ranked above Lead Admin, so they can discipline admins, but they don't inherit admin tools. |
| Owner | Owner | Everything, including the console-only operations below. |

---

## 2. How a staff member's authority is decided (CURRENT, after this change)

Three independent layers. An action needs all of the layers it uses.

1. **Permission nodes** (LuckPerms). For example `joshymc.ban` lets you run `/ban` at all.
2. **Punishment level** `joshymc.punish.<trainee|helper|jrmod|mod|leadmod|jradmin|admin|sradmin>`
   (all default `false`, each includes the ones below it). This sets **how strong** a punishment
   may be, for `/punish` rules **and now also** for `/ban`, `/tempban`, `/mute`, `/tempmute`,
   `/warn`, `/kick`, `/unban`, `/unmute`, `/unwarn`, `/punish <player> <ban|…>` and the `/admin`
   panel. Ops and the console count as Sr Admin.
3. **Staff rank protection** (new, `staff/StaffRankManager`). Nobody may punish, freeze,
   rank-change, `/sudo` or restore the inventory of **themselves** or of staff **at or above their
   own rank**. Equal ranks are refused unless the Owner sets
   `config.yml > staff-ranks.allow-equal-rank-actions: true`.

### Where a player's staff rank comes from

Never from display names, chat prefixes or tags. Only these sources count:

- LuckPerms groups the player holds, read as the explicitly granted `group.<name>` nodes that
  LuckPerms sets for every inherited group. They are mapped to ranks by
  `config.yml > staff-ranks.groups`. The defaults are the rank id and the same id without
  underscores, for example `jr_mod` and `jrmod`. **Edit these to match the live group names.**
- JoshyMC's own `player_ranks` records (ranks given through `/rank` or `/admin`).
- Ops and `*` holders count as **Owner**.
- Offline players: the last rank seen while they were online (`staff_rank_cache`, written on
  join and quit) or their JoshyMC record. Someone never seen as staff on that server counts as
  a player. A demoted player loses protection the next time they log in.

---

## 3. Verified security fixes (Deliverable A)

| # | Vulnerability (audit ref.) | Root cause | Correction | Files |
|---|---|---|---|---|
| 1 | **CRITICAL** `joshymc.vouchers` could run any console command (Part 3 C1) | `/voucher create <id> <price> …` and `/voucher setcommand` stored free text that runs as the console when the package is bought | Setting a reward command is **console only**: no permission grants it. `setprice`/`setrank` need the new `joshymc.vouchers.pricing` (default false), so a package can't be made nearly free. Every change is audited. **Existing packages keep their commands and still work**, so purchased and listed packages are unchanged. | `command/VoucherCommand.kt`, `staff/StaffActionRules.kt` (`VoucherAdminRules`) |
| 2 | **CRITICAL** portal `command` actions ran as the console (Part 3 C2) | `PortalManager.executeAction` used `dispatchCommand(console, …)` for any text a `joshymc.portal` holder set | The `command` action now runs **with the walking player's own permissions** (`player.performCommand`). A new `console` action keeps run-as-console behaviour, and **only the console** can set it (`portal action <name> console <cmd>`). tp/rtp/world/warp portals are unchanged. At startup, each existing `command` portal is logged so the Owner can re-set any that really need console authority. | `manager/PortalManager.kt`, `command/PortalCommand.kt` |
| 3 | **HIGH** `/admin` Manage Ranks could add any rank (including `owner`) to anyone, including yourself (Part 2.4, Part 4 A-H1) | No self check, no rank check; clicks not re-checked | Every add/remove click runs `RankAssignmentRules`: no self changes, no changes to staff at or above you, staff ranks only **below** your own, **Owner is console only**. Donor and player ranks still work. | `manager/AdminManager.kt`, `staff/StaffRanks.kt` |
| 4 | **HIGH** `/rank add|remove|promote|demote` had the same gap (Part 4 A-H1, A-H2, A-M1) | Same | Same rule for all four. Console use is now audited too. | `command/RankCommand.kt` |
| 5 | **HIGH** the legacy punishment commands bypassed the `/punish` caps (Part 2.2) | They only checked `joshymc.ban` etc. | They now need the matching punishment level: warn = Trainee, temp-mute = Helper, **permanent mute = Mod**, kick = Mod, temp-ban = Mod (≤7d) / Lead Mod and Jr Admin (≤30d) / Admin (any), **permanent ban = Admin**, unwarn = Jr Mod, unmute = Helper, unban = Mod. Plus self and rank protection. The `/admin` panel's ban/mute/kick/warn use the same rules when the reason is typed. | `command/PunishmentCommands.kt`, `command/PunishCommand.kt`, `manager/AdminManager.kt`, `staff/StaffRanks.kt` (`LegacyPunishAuthority`) |
| 6 | **HIGH** `/unban`, `/unmute`, `/unwarn` and the ban/mute list lifted `/punish` punishments without overturning them (Part 2.2) | They revoked every active row | They only lift **legacy** rows now (`offense IS NULL`). Rule punishments stay in force and the staff member is told to use `/punish overturn`, which marks the incident overturned. History and audit rows are untouched. | `manager/PunishmentManager.kt`, the commands above |
| 7 | **MODERATE** rank protection used `ranks.list` weights, allowed equal ranks, missed Jr Admin / Lead Admin / Head Manager / Staff Manager / Community Manager, and gave LuckPerms-only staff no protection (Part 2.1, Part 4 A-M2) | Weight from `player_ranks` only | One rule (`StaffProtection`) from LuckPerms groups + records + ops, with all 12 ranks, equal rank refused by default. Used by `/punish` too. | `staff/*`, `punish/RulePunishManager.kt` |
| 8 | **HIGH** `/admin` Set Balance got around the op-only rule of issue #1162 (Part 3 H2) | Called `setBalance` directly | Shown and usable for **operators only**, through `economyManager.adminSet` with the `admin_logs` row in the same transaction. | `manager/AdminManager.kt` |
| 9 | **CRITICAL by impact** `/admin` Set Gamemode could put anyone (or yourself) in Creative (Part 2.4, Part 3 C3) | Only `joshymc.admin` checked | Creative is skipped in the cycle unless the viewer could use `/gamemode creative` on that player (`joshymc.gamemode.creative`, plus `.others` for someone else). Staff at or above you can't be changed. | `manager/AdminManager.kt` |
| 10 | **HIGH** inventory snapshot restore: self-restore dupes, any snapshot id (Part 2.4) | No self/rank check; `restoreSnapshot` read any id | Restore refuses yourself and staff at or above you, only applies the target's **own** snapshot, and is audited with the target UUID. | `manager/AdminManager.kt` |
| 11 | **LOW → HIGH latent** `/admin` clicks and typed reasons weren't re-checked; subcommands only checked "any tier" (Part 2.4, 2.5) | Buttons only checked at build time | Every sensitive button re-checks its tier on click (`guardedItem`), reasons re-check tier and authority, and each `/admin` subcommand checks its own tier (freeze = moderate, snapshot/rollback/banlist/lagclear = admin). The base permission of `/admin` was **not** widened. | `manager/AdminManager.kt`, `command/AdminCommand.kt` |
| 12 | **CRITICAL** `/sudo` ran commands with the target's (e.g. the Owner's) permissions (Part 4 A-C1) | No limits | Players may only `/sudo` players ranked below them, never `lp`/`op`/`execute`/economy/rank/voucher/purchase and similar commands, and every use is audited. | `command/EssentialsCommands.kt`, `staff/StaffActionRules.kt` (`SudoRules`) |
| 13 | **CRITICAL by impact** Creative could copy or forge tokens, keys and vouchers (Part 3 C3) | No `InventoryCreativeEvent` handling | A Creative inventory action carrying any `joshymc:` data is cancelled unless the player holds `joshymc.creative.bypass` (default false). Vanilla items are unaffected. | `listener/CreativeValuablesListener.kt` |
| 14 | **HIGH** Credit and Rank Vouchers were unsigned (Part 3 C3) | Random UUID only | New vouchers carry an HMAC (same scheme as Fly Vouchers, per-server secret `premium_voucher_secret`). A wrong signature is always refused and alerted. **Migration:** vouchers made before this update have no signature and still redeem while `vouchers.accept-unsigned-legacy: true` (default), and each one is logged. Once those logs stop, the Owner turns it off. | `staff/PremiumVoucherSigner.kt`, `staff/PremiumVoucherRules.kt`, `manager/CreditVoucherManager.kt`, `manager/RankVoucherManager.kt` |
| 15 | **HIGH** thin audit trail (Part 3 H1, Part 4 A-L1) | Many gives only logged to console, or not at all | `admin_logs` rows (staff UUID or `CONSOLE`, target UUID and name, action, values, timestamp; never secrets) for `/eco give`, `/credits give`, `/tokens give`, `/crate givekey` (per player), `/crate force`, `/joshymc give`, `/rankvoucher give`, `/creditvoucher give`, `/voucher give` (all kinds), `/sellwand`, voucher package changes, portal actions, `/sudo`, rank changes from the console, unban/unmute from the panel, inventory restores. View with `/admin logs`. | `staff/AdminAudit.kt` + the commands |

### plugin.yml bundle changes

- `joshymc.staff.trainee` no longer includes `joshymc.staff.mute`. Trainees can't mute.
- `joshymc.staff.jrmod` no longer includes `joshymc.staff.ban`. Jr Mods can't ban.
- The level caps (fix 5) also bind every other bundle: holding `joshymc.ban` is no longer
  enough to ban without the matching `joshymc.punish.<level>`.

### Capabilities that changed (documented, not silent)

- Staff who were given **only** legacy nodes (`joshymc.ban`, `joshymc.mute`, …) and **no**
  `joshymc.punish.<level>` can no longer use those commands or the `/admin` panel punishments.
  Give each rank its `joshymc.punish.<level>` (§6). Ops and the console are unaffected.
- Staff can no longer punish, freeze, rank-change or restore staff of their own rank.
- Players can't set voucher reward commands or `console` portal actions. Use the console.
- `/voucher setprice` and `setrank` need `joshymc.vouchers.pricing`.
- `/admin` Set Balance is operator only.
- Players in Creative can't take or move JoshyMC items through the Creative inventory.
- Existing `command` portals run with the player's own permissions.

### Delegated commands: `/sudo`, NPCs and portal `command` actions (issue #1353)

All three run a command that one person wrote with somebody else's permissions and belongings
(the `/sudo` target, the player who clicks the NPC, the player who walks into the portal).
Before #1353, `/sudo` only blocked a short label list, and NPC and portal commands weren't
filtered at all. A `joshymc.npc` or `joshymc.portal` holder could set `pay <self> 1000000`,
and every player who clicked would pay them. An Owner who clicked an NPC running
`lp user <x> parent add owner` or `world delete <x>` would run it with Owner rights.

One Bukkit-free policy, `staff/DelegatedCommandRules`, now decides all three
(`staff/DelegatedCommands` resolves aliases and `plugin:` names through the command map). It
refuses:

| Refused | Examples |
|---|---|
| Staff commands: JoshyMC's staff commands by name, known permission/operator/server commands, and any other plugin's or vanilla command whose permission doesn't default to every player | `/lp`, `/op`, `/world`, `/joshymc`, `/gencave`, `/eco`, `/invsee`, `/god`, `/punish`, `/minecraft:give`, CoreProtect |
| Staff subcommands (first argument) | `admin`, `give`, `givekey`, `force`, `reload`, `set`, `take`, `reset`, `hearts`, … (`/crate givekey`, `/team admin`, `/lifesteal admin`) |
| Commands that move the user's belongings | `/pay`, `/creditspay`, `/trade`, `/trash`, `/withdrawheart`, `/tpaccept`, `/sethome`, `/delhome` |
| Arguments to market/team/claim commands (the bare menu is fine) | `/team transfer x`, `/claim trust x`, `/ah sell 1`, `/bounty add x 1`, `/coinflip create 1`, `/tokens sell 64`, `/bank withdraw all` |

- `/npc setcommand` and `/portal action <name> command …` refuse such a command when it's set,
  and NPC clicks/portal entries check it again when they run, so **commands stored before
  #1353 are covered too**. A refused run is logged to the console as
  `[DelegatedCommands] Refused …`, so check the log after updating and re-set any NPC or
  portal that relied on one.
- Menus, warps, kits, shops and similar player commands (`/spawn`, `/warp x`, `/kit`, `/shop`,
  `/advent`, `/pv`, `/crate preview x`, `/event join`) are unchanged.
- The console's own `/sudo` and console-only `console` portal actions aren't delegated, so
  they aren't limited.
- Audit rows: `SUDO` now stores the whole command line (amounts and recipients), and there
  are new `NPC_COMMAND`, `NPC_CREATE` and `NPC_DELETE` rows. `PORTAL_ACTION` now also stores
  the action data.

---

## 4. Punishment limits (CURRENT)

| Level | Warn | Temp-mute | Perm mute | Kick | Temp-ban | Perm ban | Remove warning | Unmute | Unban | Overturn `/punish` |
|---|---|---|---|---|---|---|---|---|---|---|
| Trainee | ✔ | – | – | – | – | – | – | – | – | – |
| Helper | ✔ | ✔ | – | – | – | – | – | ✔ | – | – |
| Jr Mod | ✔ | ✔ | – | – | – | – | ✔ | ✔ | – | warnings |
| Mod | ✔ | ✔ | ✔ | ✔ | ≤ 7 days | – | ✔ | ✔ | ✔ | warnings |
| Lead Mod / Jr Admin | ✔ | ✔ | ✔ | ✔ | ≤ 30 days | – | ✔ | ✔ | ✔ | warnings |
| Admin | ✔ | ✔ | ✔ | ✔ | any | ✔ | ✔ | ✔ | ✔ | warnings |
| Sr Admin (console, ops) | ✔ | ✔ | ✔ | ✔ | any | ✔ | ✔ | ✔ | ✔ | everything |

- `/punish` rules additionally have their own minimum level, escalate by tier, need evidence
  for bans, and need a confirmation (unchanged, issue #1298).
- Network-wide (`scope:network`) bans and mutes need `joshymc.punish.network`.
- Legacy unban/unmute/unwarn never lift a `/punish` punishment. Use `/punish overturn`.
- Everyone: no self-punishment, and no punishing staff of equal or higher rank.

---

## 5. Permission inventory: high-risk nodes (verified in code)

Gamemode scope: N = NETWORK (runs on both servers, each with its own database), S = Survival
only, L = Lifesteal only (`gamemode/GamemodeAssignments.kt`).

| Node (default) | Commands / GUI | Checks after this change | Risk | Minimum rank (RECOMMENDED) | Scope |
|---|---|---|---|---|---|
| `joshymc.vouchers` (op) | `/voucher` names/icons/enable/give | reward commands console only; pricing needs `.pricing`; audited | HIGH | Lead Admin | S |
| `joshymc.vouchers.pricing` (false) | `/voucher setprice`, `setrank` | audited | HIGH | **Owner approval** | S |
| `joshymc.portal` (op) | `/portal` | `console` actions console only; `command` runs as the player | MODERATE | Lead Admin | N |
| `joshymc.sudo` (op) | `/sudo` | lower-rank targets only, blocked commands, audited | HIGH | Owner (Head Manager with approval) | N |
| `joshymc.admin` (op) | `/admin` panel: heal, teleport, ban, gamemode, ranks, inventory, snapshots, ban list | per-button tier re-check, rank protection, Creative rule, op-only balance | HIGH | Admin | N |
| `joshymc.admin.moderate` (op) | panel kick, freeze | re-checked | MODERATE | Mod | N |
| `joshymc.admin.helper` (op) | panel warn, mute, history, logs, mute list | re-checked | LOW | Helper | N |
| `joshymc.rank` + `.add` / `.remove` (op) | `/rank add|remove` | rank assignment rule | HIGH | Admin (ranks below them only) | N |
| `joshymc.rank.staff` (op) | `/rank promote|demote` (ladder up to Lead Mod) | rank assignment rule | HIGH | Staff Manager | N |
| `joshymc.rank.check` (op) | `/rank check` | read-only | LOW | Helper | N |
| `joshymc.punish.<level>` (false) | `/punish`, legacy commands, panel | §4 | per level | that rank | N |
| `joshymc.punish.network` (false) | `scope:network`, `/punish review` | planner | HIGH | Head Manager | N |
| `joshymc.ban`, `.tempban`, `.unban`, `.mute`, `.tempmute`, `.unmute`, `.warn`, `.unwarn`, `.kick` (op) | legacy commands | level cap + protection | capped | match §4 | N |
| `joshymc.gamemode.creative` (op), `.others` (op) | `/gamemode`, panel | permission | CRITICAL on Survival | Head Manager (Survival) | N |
| `joshymc.creative.bypass` (false) | Creative inventory with JoshyMC items | listener | CRITICAL | **Owner only** | N |
| `joshymc.eco` (op) | `/eco give` (no cap) | audited; take/set op only | CRITICAL | Owner | S |
| `joshymc.credits` (op) | `/credits give` (≤1B) | audited | CRITICAL | Owner | S |
| `joshymc.tokens.give` (op) | `/tokens give` | audited | CRITICAL | Owner / Head Manager (approval) | S |
| `joshymc.creditvoucher.admin`, `joshymc.rankvoucher.admin` (op) | physical Credit/Rank Vouchers | signed + audited | CRITICAL | Owner | S |
| `joshymc.give` (op) | `/joshymc give` (incl. tokens) | audited | HIGH | Lead Admin | **N** |
| `joshymc.enchant` (op) | `/enchant` within vanilla limits (max level, item, conflicts) | permission | MEDIUM | Admin; deny on Lifesteal | **N** |
| `joshymc.enchant.unsafe` (false) | `/enchant` past vanilla limits, up to 255 (issue #1487) | audited (`ENCHANT_UNSAFE`); such gear earns no Lifesteal rewards | HIGH | explicit grant only | **N** |
| `joshymc.god` (op) / `joshymc.god.others` (op) | `/god` self / another player (issue #1487) | audited (`GOD_MODE`, `GOD_MODE_OTHER`); God Mode kills earn no Lifesteal rewards | MEDIUM | Admin | **N** |
| `joshymc.crate.admin`, `joshymc.crateeditor` (op) | `/crate givekey|force`, editor | audited | HIGH | Lead Admin | S |
| `joshymc.sellwand.give`, `joshymc.booster` (op) | sell wands, boosters (no multiplier cap) | sell wands audited | HIGH | Head Manager (approval) | S |
| `joshymc.spawners.admin` (op) | `/spawner give` | permission | HIGH | Lead Admin | S |
| `joshymc.createkit`, `.editkit`, `.deletekit` (op) | kits | permission | HIGH | Admin | N |
| `joshymc.reload` (op) | `/joshymc reload [hard]` | permission | MODERATE | Lead Admin | N |
| `joshymc.jgm.lock` / `.unlock` (false) | gamemode locks | literal confirm | HIGH | Owner | N |
| `joshymc.purchase.admin` (op) | `/purchase list|info` (manage needs op) | op + node | MODERATE | Admin (view) | N |
| `joshymc.invsee` (op) | `/invsee` (live, editable) | permission | MODERATE | Lead Mod | N |
| `joshymc.enderchest.others` / `.clear` (op) | editable ender chests | permission | MODERATE | Lead Mod / Admin | S |
| `joshymc.modmode` / `.edit` (op) | Moderator Mode | permission | LOW / MODERATE | Mod / Lead Mod | N |
| `joshymc.vanish`, `joshymc.hidestaff` (op) | vanish | permission | LOW | Mod | N |
| `joshymc.playerdata.reset` (op) | wipe a banned player | target must be banned | HIGH | Admin | N |
| `joshymc.claim.admin`, `joshymc.claim.bypass` (op) | claims | permission | MODERATE | Admin | S |
| `joshymc.event.host` (op) | hosted events | permission | LOW | Community Manager | S |
| `joshymc.event.rewards`, `.result.override` (op) | event payouts | permission | MODERATE | Community Manager | S |
| `joshymc.event.rewards.recover` (op) | payout recovery | permission | MODERATE | Admin | S |
| `joshymc.lifesteal.hearts.admin` / `.give` (op) | set/add hearts, Heart items | audited (`lifesteal_heart_audit`) | HIGH | Lead Admin | L |
| `joshymc.lifesteal.team.admin` (op) | team admin | confirm + audit | MODERATE | Admin | L |
| `joshymc.lifesteal.ranked.admin` (op) | arenas, test matches, Elo diagnostics | audit | HIGH | Lead Admin | L |
| `joshymc.lifesteal.progression.admin` (op) | season launch | literal confirm + audit | CRITICAL | Owner | L |
| `joshymc.lifesteal.progression.end-bypass` (false) | early End access | permission | MODERATE | Owner approval | L |
| `joshymc.lifesteal.combat.admin` (op) | combat-log diagnostics (read-only) | permission | LOW | Mod | L |
| `joshymc.transfer.bypass-cooldown` (op) | transfer cooldown | permission | LOW | Admin | N |

---

## 6. Staff permission matrix (Deliverable B)

Columns: **C** = current repository behaviour, **R** = recommended, **A** = requires Owner
approval. "Inherits" is the recommended LuckPerms parent. Current LuckPerms assignments are
unknown (not in the repo): verify them live.

| Rank | Inherits (R) | Moderation / punish level (R) | Admin GUI (R) | Management (R) | Economy / items (R) | Rank assignment | World / network / Lifesteal (R) |
|---|---|---|---|---|---|---|---|
| Trainee | default | `joshymc.punish.trainee`, `joshymc.staffchat`, `.staffchat.view`, `joshymc.staff.trainee` | – | – | none | none (C: rank protection applies) | none |
| Helper | trainee | `joshymc.punish.helper`, `joshymc.admin.helper`, `joshymc.history`, `joshymc.reports.view`, `joshymc.rank.check` | panel helper tier | – | none | none | none |
| Jr Mod | helper | `joshymc.punish.jrmod`, `joshymc.staff.jrmod` | helper tier | – | none | none | none |
| Mod | jr_mod | `joshymc.punish.mod`, `joshymc.staff.mod`, `joshymc.kick`, `joshymc.admin.moderate`, `joshymc.modmode`, `joshymc.vanish`, `joshymc.alts` | moderate tier | – | none | none | `joshymc.lifesteal.combat.admin` (L) |
| Lead Mod | mod | `joshymc.punish.leadmod`, `joshymc.staff.leadmod`, `joshymc.modmode.edit`, `joshymc.invsee`, `joshymc.enderchest.others` | moderate tier | supervises mods | none | none | – |
| Jr Admin | lead_mod | `joshymc.punish.jradmin` | **A:** `joshymc.admin` (full panel) | – | none | none | – |
| Admin | jr_admin | `joshymc.punish.admin`, `joshymc.playerdata.reset` | `joshymc.admin` | – | `joshymc.createkit`/`editkit`/`deletekit`, `joshymc.enchant` (deny on Lifesteal) | `joshymc.rank` + `.add`/`.remove` (C: only below Admin) | `joshymc.claim.admin`, `joshymc.lifesteal.team.admin` (L) |
| Lead Admin | admin | `joshymc.punish.sradmin` (**A**) | `joshymc.admin` | supervises admins | `joshymc.give`, `joshymc.crate.admin`, `joshymc.crateeditor`, `joshymc.spawners.admin`, `joshymc.vouchers` | as Admin | `joshymc.portal`, `joshymc.reload`, `joshymc.lifesteal.hearts.admin` (L), `joshymc.lifesteal.ranked.admin` (L) |
| Community Manager | lead_mod | `joshymc.punish.leadmod` | moderate tier | `joshymc.event.host`, `joshymc.event.rewards`, `joshymc.event.result.override`, `joshymc.announce`, `joshymc.chat.admin` | **none** (no eco/credits/vouchers/give) | none | **no Creative, no reload, no config, no LuckPerms** |
| Staff Manager | lead_mod | `joshymc.punish.leadmod`; **A:** `joshymc.punish.admin` for staff bans | moderate tier; **A:** `joshymc.admin` for snapshots/history | `joshymc.rank` + `joshymc.rank.staff` + `joshymc.rank.check` (promote/demote the moderation ladder; C: only below Staff Manager), `joshymc.punishment.notify` | **none** | ladder only | none |
| Head Manager | staff_manager + community_manager | `joshymc.punish.sradmin`, `joshymc.punish.network` | `joshymc.admin` | everything above | **A:** `joshymc.tokens.give`, `joshymc.gamemode.creative` (Survival) | `joshymc.rank.add`/`.remove` (below Head Manager) | **A:** Lead Admin technical nodes |
| Owner | head_manager (+ **A:** `*`) | all | all | all | all, incl. `joshymc.eco`, `joshymc.credits`, voucher admin, `joshymc.vouchers.pricing`, `joshymc.creative.bypass` | console for Owner | `joshymc.jgm.lock/unlock`, `joshymc.lifesteal.progression.admin`, `joshymc.sudo` |

**Restricted for everyone below Owner (R):** `joshymc.eco`, `joshymc.credits`,
`joshymc.creditvoucher.admin`, `joshymc.rankvoucher.admin`, `joshymc.vouchers.pricing`,
`joshymc.creative.bypass`, `joshymc.jgm.lock`/`unlock`, `joshymc.lifesteal.progression.admin`,
`joshymc.sudo`, LuckPerms (`luckperms.*`).

---

## 7. LuckPerms configuration plan (Deliverable C). DO NOT RUN WITHOUT OWNER APPROVAL

Principles:
- **Two tracks plus management**, not one chain: moderation (`trainee → … → lead_mod`),
  administration (`jr_admin → admin → lead_admin`, built on `lead_mod`), management
  (`community_manager`, `staff_manager` built on `lead_mod`; `head_manager` on both).
- **No wildcards** below Owner. Donor groups (scout…pioneer) and `media`/`discord` stay separate
  and never parent a staff group.
- **Server contexts** keep Survival and Lifesteal apart: each server sets `server:` in its
  LuckPerms `config.yml` (for example `survival` and `lifesteal`). Economy and Survival nodes are
  granted with `server=survival`, Lifesteal nodes with `server=lifesteal`.
- Before running anything: `lp listgroups` on **both** servers. Only create a group that
  doesn't exist, and if a group exists under another name, use that name and add it to
  `config.yml > staff-ranks.groups`.

```text
# 0. Inspect first (read-only)
lp listgroups
lp group <group> info
lp group <group> permission info
lp group <group> parent info

# 1. Groups that may not exist yet (create only the missing ones)
lp creategroup jr_admin
lp creategroup lead_admin
lp creategroup community_manager
lp creategroup staff_manager
lp creategroup head_manager

# 2. Weights (tab/prefix order only; JoshyMC protection uses the hierarchy, not weights)
lp group trainee setweight 60
lp group helper setweight 70
lp group jr_mod setweight 75
lp group mod setweight 80
lp group lead_mod setweight 85
lp group jr_admin setweight 86
lp group admin setweight 88
lp group lead_admin setweight 89
lp group community_manager setweight 91
lp group staff_manager setweight 92
lp group head_manager setweight 95
lp group owner setweight 100

# 3. Inheritance
lp group helper parent add trainee
lp group jr_mod parent add helper
lp group mod parent add jr_mod
lp group lead_mod parent add mod
lp group jr_admin parent add lead_mod
lp group admin parent add jr_admin
lp group lead_admin parent add admin
lp group community_manager parent add lead_mod
lp group staff_manager parent add lead_mod
lp group head_manager parent add staff_manager
lp group head_manager parent add community_manager
lp group owner parent add head_manager

# 4. Punishment levels (each level includes the ones below it)
lp group trainee permission set joshymc.punish.trainee true
lp group helper permission set joshymc.punish.helper true
lp group jr_mod permission set joshymc.punish.jrmod true
lp group mod permission set joshymc.punish.mod true
lp group lead_mod permission set joshymc.punish.leadmod true
lp group jr_admin permission set joshymc.punish.jradmin true
lp group admin permission set joshymc.punish.admin true
lp group head_manager permission set joshymc.punish.sradmin true
lp group head_manager permission set joshymc.punish.network true
# Requires Owner approval:
# lp group lead_admin permission set joshymc.punish.sradmin true
# lp group staff_manager permission set joshymc.punish.admin true

# 5. Moderation tools
lp group trainee permission set joshymc.staff.trainee true
lp group trainee permission set joshymc.staffchat true
lp group trainee permission set joshymc.staffchat.view true
lp group helper permission set joshymc.staff.helper true
lp group helper permission set joshymc.admin.helper true
lp group helper permission set joshymc.history true
lp group helper permission set joshymc.reports.view true
lp group helper permission set joshymc.rank.check true
lp group jr_mod permission set joshymc.staff.jrmod true
lp group mod permission set joshymc.staff.mod true
lp group mod permission set joshymc.kick true
lp group mod permission set joshymc.admin.moderate true
lp group mod permission set joshymc.modmode true
lp group mod permission set joshymc.vanish true
lp group mod permission set joshymc.alts true
lp group lead_mod permission set joshymc.staff.leadmod true
lp group lead_mod permission set joshymc.modmode.edit true
lp group lead_mod permission set joshymc.invsee true
lp group lead_mod permission set joshymc.enderchest.others true server=survival

# 6. Administration
lp group admin permission set joshymc.admin true
lp group admin permission set joshymc.playerdata.reset true
lp group admin permission set joshymc.rank true
lp group admin permission set joshymc.rank.add true
lp group admin permission set joshymc.rank.remove true
lp group admin permission set joshymc.createkit true
lp group admin permission set joshymc.editkit true
lp group admin permission set joshymc.deletekit true
lp group admin permission set joshymc.claim.admin true server=survival
lp group admin permission set joshymc.enchant true server=survival
lp group admin permission set joshymc.enchant false server=lifesteal
lp group admin permission set joshymc.lifesteal.team.admin true server=lifesteal
lp group lead_admin permission set joshymc.give true
lp group lead_admin permission set joshymc.reload true
lp group lead_admin permission set joshymc.portal true
lp group lead_admin permission set joshymc.vouchers true server=survival
lp group lead_admin permission set joshymc.crate.admin true server=survival
lp group lead_admin permission set joshymc.crateeditor true server=survival
lp group lead_admin permission set joshymc.spawners.admin true server=survival
lp group lead_admin permission set joshymc.lifesteal.hearts.admin true server=lifesteal
lp group lead_admin permission set joshymc.lifesteal.ranked.admin true server=lifesteal
# Requires Owner approval:
# lp group jr_admin permission set joshymc.admin true

# 7. Management (role-specific, no technical nodes)
lp group community_manager permission set joshymc.event.host true server=survival
lp group community_manager permission set joshymc.event.rewards true server=survival
lp group community_manager permission set joshymc.event.result.override true server=survival
lp group community_manager permission set joshymc.announce true
lp group community_manager permission set joshymc.chat.admin true
lp group staff_manager permission set joshymc.rank true
lp group staff_manager permission set joshymc.rank.check true
lp group staff_manager permission set joshymc.rank.staff true
lp group staff_manager permission set joshymc.punishment.notify true
lp group head_manager permission set joshymc.rank.add true
lp group head_manager permission set joshymc.rank.remove true
lp group head_manager permission set joshymc.admin true
# Requires Owner approval:
# lp group head_manager permission set joshymc.tokens.give true server=survival
# lp group head_manager permission set joshymc.gamemode.creative true server=survival

# 8. Explicit denials that must never be inherited by management
lp group community_manager permission set joshymc.eco false
lp group community_manager permission set joshymc.credits false
lp group community_manager permission set joshymc.vouchers false
lp group community_manager permission set joshymc.gamemode.creative false
lp group community_manager permission set joshymc.reload false
lp group staff_manager permission set joshymc.eco false
lp group staff_manager permission set joshymc.credits false
lp group staff_manager permission set joshymc.give false
lp group staff_manager permission set joshymc.gamemode.creative false

# 9. Owner-only nodes (keep them on the owner group, never on an inherited group)
lp group owner permission set joshymc.eco true server=survival
lp group owner permission set joshymc.credits true server=survival
lp group owner permission set joshymc.creditvoucher.admin true server=survival
lp group owner permission set joshymc.rankvoucher.admin true server=survival
lp group owner permission set joshymc.vouchers.pricing true server=survival
lp group owner permission set joshymc.jgm.lock true
lp group owner permission set joshymc.jgm.unlock true
lp group owner permission set joshymc.lifesteal.progression.admin true server=lifesteal
lp group owner permission set joshymc.sudo true
# Only if the Owner wants it (Creative item editing):
# lp user <owner> permission set joshymc.creative.bypass true
```

Removing a risky grant later uses `lp group <group> permission unset <node>`. Check the result
with `lp user <name> permission check <node>` on each server.

---

## 8. Survival and Lifesteal (Deliverable D, isolation)

- Every command has a scope (`GamemodeAssignments`). SURVIVAL commands (economy, vouchers,
  crates, shops, events) are refused on a dedicated Lifesteal server through its implied lock.
  LIFESTEAL commands (`/withdrawheart`, `/ranked`) are only registered where Lifesteal runs.
  NETWORK commands run on both servers.
- **High-risk NETWORK commands:** `/joshymc give` (can give `token`), `/enchant` with `joshymc.enchant.unsafe` (level 255),
  `/sudo`, `/admin`, `/rank`, `/gamemode`, `/portal`, `/punish` and the legacy punishments.
  They work wherever the node is granted. Keep them apart with LuckPerms `server=` contexts (§7),
  for example `joshymc.enchant false server=lifesteal`.
- Each server has its own SQLite database, so punishments, audit rows, rank records and the
  `staff_rank_cache` are per server. A ban on Survival doesn't ban on Lifesteal.
- Rank protection works the same on both servers, from that server's LuckPerms data.
- Combat rules are unchanged: `/sudo` can't run `/execute`, and the existing combat-teleport
  guards still apply to commands it runs as the target.

---

## 9. Testing (Deliverable E)

Automated (all passing):
- `./gradlew -I scripts/staff-permission-check.init.gradle.kts staffPermissionCheck`: 153
  checks for the hierarchy and group mapping, protection (lower, equal, self, non-staff,
  management versus administration), rank assignment (self, Owner, rank-too-high, donor ranks),
  legacy punishment caps, voucher/portal/sudo/Creative rules, voucher signatures and migration,
  plugin.yml bundles and defaults, config defaults, the source wiring of every fixed path, and
  the gamemode scopes of the network/Survival/Lifesteal commands.
- Unchanged and still passing: `rankPerkCheck`, `gamemodeScopeCheck`, `shutdownCheck`, `./gradlew build`.

Manual checks on a test server (with LuckPerms):
1. A Mod can't `/ban` (no Admin level), can `/tempban x 7d`, and can't `/tempban x 8d`.
2. Two Mods can't punish each other. A Lead Mod can punish a Mod. Nobody can punish an op.
3. An Admin in `/admin` → Manage Ranks can't add `admin`, `owner` or anything to themselves,
   and can add `lead_mod` to a Mod.
4. `/voucher setcommand` from a player is refused and works from the console. An existing
   Credit Shop package still delivers when bought.
5. A portal set with `command op {player}` doesn't op a normal player. `console` actions can only
   be set from the console.
6. In Creative, middle-click copying a token or crate key does nothing. Vanilla blocks copy normally.
7. A new Credit Voucher redeems. An old (pre-update) voucher redeems and logs "unsigned legacy".
8. With `/admin` open, removing `joshymc.admin` and then clicking Ban says "You no longer have permission".
9. `/unban` of a player banned with `/punish` leaves the ban and points to `/punish overturn`.
10. `/admin logs` shows `ECO_GIVE`, `CRATE_KEY_GIVE`, `RESTORE_INVENTORY` and the other new rows.

---

## 10. Outstanding decisions (Deliverable F)

Requires Owner approval:
1. Equal-rank actions (`staff-ranks.allow-equal-rank-actions`), default off.
2. Whether Lead Admin gets `joshymc.punish.sradmin` (overturns, tier overrides).
3. Staff Manager's ban authority for staff discipline (`joshymc.punish.admin`) and panel access.
4. Head Manager's extra technical nodes (tokens, Creative on Survival, Lead Admin tools).
5. Whether Jr Admin gets the full `/admin` panel.
6. When to set `vouchers.accept-unsigned-legacy: false`.
7. Who, if anyone, gets `joshymc.creative.bypass` and `joshymc.vouchers.pricing`.
8. Self-targeting of `/eco give`, `/credits give`, `/tokens give`, `/crate givekey` and caps on
   amounts (audit Part 3 H1). They are now audited, but still allowed for whoever holds the node.

Audit findings not fixed here (outside this issue's scope, or a separate behaviour change):
- `/invsee` and `/enderchest <player>` are editable (Part 2.6, Part 3 M2).
- Mutes don't block `/msg` and `/r` (Part 2.3).
- `/credits` base permission blocks `bal`/`baltop`/`pay` for players (Part 1 3b, Part 3 3.2).
- `/booster` and `/sellwand` multiplier caps (Part 3 H1, H4). (`/enchant` level 255 is fixed by
  issue #1487: vanilla limits unless `joshymc.enchant.unsafe`.)
- `/crate force` opens a crate without a key (now audited; Part 3 H3).
- Physical money vouchers and tokens carry no per-item id (Part 3 M1). Creative copying is
  blocked, but a separate duplication bug could still copy them.
- Parts 5 and 6 of the audit (server/world/events, the final hierarchy) were never posted. This
  document covers the hierarchy; world/event nodes are listed in §5 but weren't audited.

Live LuckPerms verification needed:
- The real group names on both servers (update `staff-ranks.groups` to match).
- Which groups hold `*`, `joshymc.admin`, `joshymc.vouchers`, `joshymc.portal`, `joshymc.sudo`,
  `joshymc.rank.*`, `joshymc.eco`, `joshymc.credits` or legacy punishment nodes **without** a
  `joshymc.punish.<level>`.
- That no group below Head Manager inherits `owner` or `admin`, and that no donor or
  media/discord group parents a staff group.
