# Lifesteal Daily & Weekly Quests (issue #1425)

Lifesteal only. Every player gets **3 personal Daily Quests** and **3 personal Weekly Quests**,
advanced by legitimate Lifesteal gameplay, claimed by hand in `/quests`, with rerolls.

- Daily quests reset every day at **00:00 America/New_York**; Weekly quests every **Monday 00:00
  America/New_York** (both configurable, daylight saving time included).
- Quests can be done in any order. Daily and Weekly progress are independent: one action counts for
  every matching quest on both boards.
- The Survival quests (`quest-cycle.yml`, `QuestCycleManager`) are untouched. `/quests` is routed by
  where the player is (`command/QuestCommandRouter`, like `/team` and `/bounty`): in Lifesteal it
  opens the Lifesteal quests, everywhere else the Survival quests, with exactly the same scope and
  permission checks as before. No progress or table is shared between the two.

## Where it runs

Like the Lifesteal bounties, the quests only run where this server's `economy` table is Lifesteal's:
the dedicated Lifesteal server (`networkIdentityManager.hostedGamemodes == {LIFESTEAL}`,
`TutorialRules.economyBelongsTo`). On a server that also hosts Survival the quests stay off (logged),
so a quest can never pay Survival coins. Activity only counts in Lifesteal worlds.

## Files and code

| Part | Where |
|---|---|
| Settings and quest pools | `plugins/Joshymc/lifesteal-quests.yml` (bundled: `src/main/resources/lifesteal-quests.yml`) |
| Model, parser | `lifesteal/quests/QuestModel.kt`, `QuestConfig.kt` (Bukkit-free) |
| Cycles, selection, rerolls, PvP rules, display, layout | `lifesteal/quests/QuestRules.kt` (Bukkit-free) |
| Database | `lifesteal/quests/LifestealQuestStore.kt` (Bukkit-free) |
| Manager, listener, menu, command | `LifestealQuestManager`, `LifestealQuestListener`, `LifestealQuestMenu`, `LifestealQuestCommand` |
| Checks | `./gradlew -I scripts/lifesteal-quest-check.init.gradle.kts lifestealQuestCheck` |

## Reused systems

- **PvP eligibility**: the bounty system's `BountyRules.eligibility` via
  `lifestealBountyManager.pvpFacts(victim, attacker)`: no self, NPCs, events, PvP arenas, Ranked
  Team Wars matches or arenas, teammates, recent teammates, allies or unknown team data.
- **PvP anti-farming**: the Milestones' `CombatRules.pvp` (same-victim cooldown, hourly cap, idle
  victim) and `CombatRules.connection` (AltManager's shared connections; a failed lookup never counts).
- **Placed blocks**: the Milestones' `PlacedBlockTracker` (chunk PDC markers), with its own key
  `joshymc:lifesteal_quest_placed` so the two never share a cache.
- **Harvest rules**: `quest/HarvestRules.countsForEventQuest` (no-growth-stage plants only when not
  player-placed; everything else only fully grown).
- **Exploration, idle and rate limits**: the Milestones' `ExplorationRules`, `LoopDetector`,
  `ActivityClock`, `RateWindow`, `FishingRules`; plus `/afk` (`AFKManager`).
- **Reward parsing and delivery**: `PurchaseRewards.parse` for reward lines and
  `PurchaseFulfillmentManager.enqueue` for delivery (Coins/Credits deposited even offline, items
  delivered on join and by the 1-minute sweep, never dropped, held while in the event world). The
  player sees "quest reward" in the delivery messages.
- **Teams**: `lifestealTeamManager` (`isInTeam`, `teamOf`, `roster`).
- **Credits**: `creditsManager.withdraw` (conditional UPDATE) inside the reroll transaction.
- **GUI**: `CustomGui` / `GuiStyle` (GuiManager cancels menu clicks, blocks shift/number/double
  clicks into the menu, 200 ms click cooldown).

## Quest types (objectives)

| Category | Objective | Counts | Notes |
|---|---|---|---|
| Combat | `player_kill` | legitimate eliminations | bounty eligibility + cooldown + hourly cap + alt check |
| Combat | `unique_player_kill` | different players eliminated | each victim once per quest (`lifesteal_quest_unique_victims`) |
| Combat | `pvp_damage` | hearts of damage dealt | target in hearts; capped per victim per hour; idle players and alts excluded |
| Combat | `bounty_claim` | paid Lifesteal bounty claims | the bounty's own rules (team, cooldown) + alt check |
| Combat | `pvp_victory` | won fights | a legitimate kill of a player who hit you within `victory-window-seconds` |
| Survival | `mine_ore` | naturally generated ores (`*_ORE`, Ancient Debris) | correct tool; placed/moved ores never count; `match` |
| Survival | `harvest_crop` | grown crops, natural no-growth plants | `match` (e.g. `WHEAT`, `SUGAR_CANE`) |
| Survival | `catch_fish` | catches | one per hook, minimum interval, not idle; `match` (`COD`, `TREASURE`, `JUNK`, ...) |
| Survival | `kill_mob` | hostile mobs | own melee/projectile kill, allowed spawn reasons only, per-minute cap; `match` entity types |
| Survival | `explore` | blocks travelled on foot | no vehicles, elytra, flight, riptide, teleports, loops or impossible speeds |
| Survival | `gather_block` | natural blocks in `match` (resource collection) | `match` required; placed and generator blocks never count |
| Teamwork | `team_assist` | assists | you hit the victim within `assist-window-seconds` before a teammate's legitimate kill |
| Teamwork | `team_pvp_kill` | eliminations with a teammate nearby | |
| Teamwork | `team_mob_kill` | hostile mobs with a teammate nearby | |
| Teamwork | `team_mine_ore` | ores with a teammate nearby | |
| Teamwork | `team_explore` | blocks travelled with a teammate nearby | |

"Teammate nearby" = an online, eligible, non-idle teammate in the same world within `team-radius`.
Teamwork quests are only assigned to players in a Lifesteal team (`team: required`); players without
a team get the solo alternatives instead (`team: none`). Ranked Team Wars participation is never
needed: Ranked matches and arenas don't count at all.

Two quests with the same objective (or the same `group`) are never on one board in one cycle.

## Rewards

Reward lines use the store-delivery syntax: `coins <amount>`, `credits <amount>`,
`cratekey <crate id> <quantity>`, `chattagvoucher <voucher tag id> <quantity>` (chat tag vouchers
are the cosmetic unlocks that exist). Crates and chat tags are checked against this server when the
file loads; a quest with an unknown one is skipped (logged). Ranks, rank/credit/money vouchers,
tokens, hearts and commands can't be quest rewards. The bundled pools pay 10,000–25,000 Coins for
Daily quests and 50,000–150,000 Coins for Weekly quests (one weekly also gives 25 Credits); these
are starting values.

Each quest's name, text, objective, target and rewards are **snapshotted** when it is assigned, so
editing the file mid-cycle never changes (or duplicates) a reward someone already holds.

## Claims

A completed quest shows "Click to claim!". Claiming is one transaction (`LifestealQuestStore.claim`)
that re-checks owner, state, the **current** cycle (a quest of an ended cycle can never be claimed)
and progress, moves the quest to CLAIMED with a conditional update (two clicks, two menus or two
threads can't claim twice) and writes one `lifesteal_quest_payouts` row per reward. The rows are then
handed to the store-delivery queue with reference `lsquest:<payout id>` (UNIQUE, so a repeat finds
the existing delivery). A crash between the claim and the queue leaves rows without `delivery_id`;
the startup/minute sweep and the player's next join queue them. CLAIMED is final (trigger).

## Rerolls

Each quest that is not complete has a reroll button below it. The confirmation screen remembers the
quest and the price it showed; the store refuses (never charges) when either changed. Inside one
transaction it re-checks owner, state (claimed and completed quests can't be rerolled), cycle, the
free/paid counts and price, picks a replacement that was never on the player's board this cycle and
shares no objective or group with the other quests (eligibility included), takes the Credits
(rolled back if anything after fails), marks the old quest REROLLED (final) and stores the history in
`lifesteal_quest_rerolls` (UNIQUE old quest). Defaults: 1 free Daily reroll per day, then 25 Credits
(3 per day); Weekly rerolls 75 Credits (2 per week). A team-only quest held after leaving the team
rerolls for free without using a reroll (`free-when-ineligible`).

## Persistence

Tables (data.db, `gamemode = 'lifesteal'` on every quest row):

| Table | Contents |
|---|---|
| `lifesteal_quest_assignments` | player UUID, gamemode, period, cycle id + end, slot, quest id, snapshot (name, text, category, objective, match, team rule, group, target, rewards), progress, state (ACTIVE/COMPLETED/CLAIMED/REROLLED), timestamps, version. Partial UNIQUE (player, cycle, slot) for live quests. Triggers: CLAIMED/REROLLED final, progress never lower. |
| `lifesteal_quest_rerolls` | reroll history: old/new quest, Credits, free/ineligible flags |
| `lifesteal_quest_payouts` | claimed rewards and their delivery id |
| `lifesteal_quest_unique_victims` | victims counted per quest |
| `lifesteal_quest_pvp_kills` | counted kills (cooldowns survive restarts) |
| `lifesteal_quest_audit` | assignments, claims, rerolls, refused kills |

Progress is kept in memory per online player and saved every 30 seconds, on quit, on shutdown and
immediately when a quest completes; saves never lower progress and never touch a claimed or rerolled
quest. Boards are created lazily when a player loads in (or on the first check after a reset).

## Configuration (`lifesteal-quests.yml`)

| Key | Meaning |
|---|---|
| `enabled` | false = no new progress, claims or rerolls; stored quests are kept; claimed rewards still delivered |
| `timezone` | IANA zone for both resets (default `America/New_York`) |
| `daily.reset-time`, `daily.quests-per-cycle` | `"HH:mm"`, 1–3 |
| `weekly.reset-day`, `weekly.reset-time`, `weekly.quests-per-cycle` | e.g. `MONDAY`, `"00:00"`, 1–3 |
| `rerolls.<daily\|weekly>.free-per-cycle`, `.credit-cost`, `.max-paid-per-cycle` | reroll limits and costs |
| `rerolls.free-when-ineligible` | free reroll for team quests after leaving the team |
| `anti-abuse.*` | PvP cooldown/hourly cap/shared connections/damage cap, victory and assist windows, team radius, idle time, mob spawn reasons and rate, fishing interval, correct tool, exploration speed |
| `gui.title`, `gui.reroll-title` | menu titles |
| `messages.*` | completion (and `completion-enabled`), claim, reroll, disabled texts |
| `pools.daily.<id>`, `pools.weekly.<id>` | the quest templates (see the file's header for every field) |

A missing scalar uses the bundled value (warning). A wrong scalar pauses the quests (severe; nothing
stored is touched). A broken template only skips that template. Pools are never merged from the
bundled file once the server's file has a `pools` section, so deleted quests stay deleted.
Apply changes with `/joshymc reload`.

## Commands and permissions

| Command | Permission |
|---|---|
| `/quests` (in Lifesteal) | `joshymc.lifesteal.quests.use` (default true) |
| `/quests admin status`, `/quests admin view <player>` | `joshymc.lifesteal.quests.admin` (op), read-only |

## Remaining integration dependencies

- **Cosmetic unlocks** other than chat tags: no cosmetic (trail/kill effect/...) has a safe grant
  API or voucher item in the store-delivery queue, so they aren't quest rewards yet. Adding one means
  a new `PurchaseRewards.Kind` (and adding it to `QuestRewardKinds.ALLOWED`).
- **Crate keys and chat tags** must exist on the Lifesteal server; the bundled pools use only Coins
  and Credits so nothing is invented.
- **Matching names** in `match:` (materials, entity types, fish classes) aren't checked against the
  game's registries; a typo simply never matches.
- **Bounty claims** count when the bounty payout is made (immediately, or later by the bounty sweep).

## Manual tests

1. On a Lifesteal server: `/quests` shows 3 Daily + 3 Weekly quests, timers, rewards and rerolls.
2. Mine natural ores: MINE_ORE progresses; place an ore and mine it: no progress.
3. Kill a player: PLAYER_KILL progresses; kill them again within 30 min: nothing. Teammates/allies,
   events, arenas and Ranked never count.
4. Complete a quest: completion message; claim it: rewards arrive (Coins in `/bal`), second click
   refused. Restart mid-way: nothing lost or paid twice.
5. Reroll: first daily free, next asks for Credits; without Credits refused; claimed quest can't be
   rerolled.
6. Set `enabled: false`, `/joshymc reload`: `/quests` says the quests are off; set back: progress kept.
7. In Survival: `/quests` opens the unchanged Survival quests.
