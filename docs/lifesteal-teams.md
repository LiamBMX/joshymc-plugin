# Lifesteal teams (issue #1346)

The Lifesteal team system: create a team, invite players, accept/decline, leave, remove members,
transfer leadership and disband, with persistent membership for later Lifesteal systems (Ranked
Team Wars and team Elo are **not** part of this). Code: `src/main/kotlin/com/liam/joshymc/lifesteal/teams/`.

It runs only where the server hosts Lifesteal (`networkIdentityManager.hostedGamemodes`, see
`docs/lifesteal-server.md`) and only for players in a Lifesteal world. **The Survival team system
(`TeamManager`, `TeamCommand`, the `teams`/`team_members`/`team_invites`/... tables) is untouched**:
Lifesteal teams have their own tables and never read or change Survival team data.

## Rules (blueprint v1.1)

- At most **5 players** per team, leader included (`teams.max-size`, 1..5; it can be lowered, never
  raised). Enforced by the store inside every join transaction **and** by a database trigger.
- A player is in **one team at most** (primary key on the membership row).
- Roles: one **leader** and plain **members**. The blueprint defines no officers, so there are none;
  `/team promote <player>` is the same as `/team transfer <player>`.
- Only the leader invites, removes members, transfers leadership and disbands.
- The leader can't leave while leading: transfer leadership first, or disband (a leader alone in
  their team is pointed to `/team disband`). There is no automatic succession (the blueprint
  defines none).
- Invitations expire after `teams.invite-expiration-seconds` (default **60**).
- No team bonuses, banks, homes, claims, chat, rewards or leaderboards. Team PvP (friendly fire
  and teammate heart stealing) is the separate section below (issue #1348).

## Team PvP (issue #1348)

The blueprint v1.1 defines **no** team combat rule, so the policy is two settings in
`lifesteal.yml > teams.friendly-fire`, with the conservative defaults:

| Key | Default | |
|---|---|---|
| `enabled` | **false** | false = teammates can't damage each other; true = they can, like anyone |
| `heart-transfers` | **false** | Only used while `enabled` is true: whether killing a teammate can steal a heart. **A gameplay decision still needing approval**; with false, teammates never farm hearts off each other |

`heart-transfers: true` with friendly fire off has no effect (one warning at load). Wrong values
(not true/false) make `lifesteal.yml` invalid like every Lifesteal setting. A file from before this
change loads both defaults with one warning. Players can't change either value; `/team info`, the
`/team` help and the team menu show the current policy, read live, so they follow a reload.

Code: `TeamCombatRules` + `TeamCombatMemory` (Bukkit-free), `LifestealTeamCombatListener`,
`LifestealTeamManager.combatRelation/heartCheck/combatPolicy`. Nothing new is stored in the
database; the heart system keeps its own transactions.

### Who counts as a teammate

Only the stored membership (the manager's cache of `lifesteal_team_members`, by UUID; never
names, chat prefixes or scoreboard teams):

- **Teammates**: in the same team right now (leaders and members alike; a leadership transfer
  changes nothing). A player who just joined counts as soon as the change has committed.
- **Recent teammates**: one of them left, was removed (also while offline) or the team was
  disbanded within the last **combat-tag duration** (`config.yml > combat.tag-duration-seconds`, the
  existing CombatManager value; no new duration). They are treated exactly like teammates, so an
  arrow fired before a team change, a lingering cloud or a kill right after `/team leave` is judged
  by the relationship the fight started with. The memory is bounded (4 entries per player), swept
  every second, survives a quick disconnect/rejoin and is cleared on reload.
- **Unknown**: the team data failed to load (`/joshymc reload` log: "Could not load Lifesteal
  teams"). That is never taken as "different teams": hits go through as ordinary PvP (nobody is
  invented as a teammate) but **no heart is transferred** for any kill until teams load again.
- With `teams.enabled: false` there are no teams in effect, so nobody is a teammate.

Membership changes are applied to the cache on the main thread right after their transaction
commits (at most one tick later), so damage events never query the database.

### Damage

`LifestealTeamCombatListener` is registered only where the server hosts Lifesteal and only judges
victims in a Lifesteal world. The attacker is the player the game credits for the damage,
`damageSource.causingEntity` (the same attribution the heart system uses), falling back to the
damager / projectile shooter / primed TNT source. With friendly fire off, damage from a
(recent) teammate is cancelled at HIGHEST (ignoreCancelled), after claims, PvP toggles and arenas:

| Source | Attribution | Teammate result |
|---|---|---|
| Melee (incl. sweeping, mace smash) | attacker | cancelled |
| Bows, crossbows, tridents, snowballs, eggs, wind charges | shooter | cancelled |
| Fireworks (crossbow or hand) | owner | cancelled |
| Harming splash potions | thrower | damage cancelled, and every harmful effect held back (`setIntensity(0)`) |
| Lingering potions | the cloud's thrower | teammates removed from the cloud's targets while it has a harmful effect |
| Flaming arrows / combustion | shooter | not set on fire (later fire ticks have no attacker to judge) |
| Player-lit TNT | igniter | cancelled |
| End Crystals | the player who broke it | cancelled |
| Fall, lava, void, fire ticks, mobs, beds, respawn anchors, TNT minecarts, lightning | none | **never touched** |

Potions with only beneficial or neutral effects still reach teammates. Hits from enemies or players
without a team are never touched by this listener. Self-damage is never touched.

**Limitations** (no attacker is invented): tamed wolves and other pets are credited to the pet, not
its owner; beds, respawn anchors, TNT minecarts, lava/fire placed by a player, and dispenser
projectiles have no causing player in Paper; a projectile's owner can change (deflection by a wind
charge or a hit), and the current owner is the one judged.

### Combat tags

A cancelled friendly hit never reaches CombatListener's MONITOR tagging (ignoreCancelled), so it
creates no combat tag; hits from enemies tag exactly as before. Combat-logging NPCs, `/spawn`
blocks and the transfer blockers are unchanged. While a player involved is combat-tagged these are
refused: **accepting** an invitation (the joining player), **leaving**, **kicking** (the leader or
the target) and **disbanding** (any member online and tagged). Inviting and leadership transfers
don't change who is a teammate and stay allowed; staff `/team admin` actions are allowed too (the
recent-teammate window still applies). Joining or leaving never clears a combat tag.

### Heart stealing

`LifestealHeartManager.onDeath` asks `lifestealTeamManager.heartCheck(killer, victim)` at death
time and passes the answer into the existing heart transaction. Teammates (or recent ones) without
`friendly-fire.enabled` **and** `heart-transfers` both true, or unknown membership, get the outcome
`TEAMMATES` / `TEAM_UNKNOWN`: no heart moves, no cooldown starts, the death's dedupe key is used
(the same death can't be processed twice) and it is audited as `PVP_REJECTED`. Both players are
told. Everything else is unchanged: the 1-heart minimum, the 20-heart maximum and the 30-minute
same-victim cooldown, which lives on the (killer, victim) pair, so leaving, rejoining or
recreating teams can't clear it.

### Abuse review

| Attempt | Result |
|---|---|
| Teammates killing each other for hearts | No transfer (default policy) |
| Leave/kick/disband, then kill the ex-teammate | Refused while tagged; otherwise the kill within the combat-tag time is still "teammates"; after it, the ordinary rules (cooldown included) apply |
| Join the attacker's team mid-fight to become immune | Accepting is refused while tagged |
| Team switching / invitation cycling / disband and recreate to reset the cooldown | The cooldown is per player pair, untouched by teams |
| Alt accounts | No account linking or IP rules (out of scope); the per-pair cooldown and the 1-heart minimum still apply |
| Database failure | Unknown membership: no heart transfers |
| Repeated death events | The existing UNIQUE dedupe key |

### Admin

`/team admin pvp <player> <player>` (`joshymc.lifesteal.team.admin`, read-only) shows the policy,
both teams, the relation (teammates / recent / not / unknown), whether a hit between them is
blocked, whether a kill passes the team check, and both same-victim cooldowns. It shows no other
player data and can't change anything; the policy only changes in `lifesteal.yml`.

### Java and Bedrock

Everything is keyed by UUID (Floodgate UUIDs for Bedrock players) and ordinary Paper events, so
Java and Bedrock players follow the same rules in any mix. No packets, Geyser or Floodgate settings
are touched, and max health is still set only by the heart system.

## Alliances and rivalries (issue #1405)

Teams can form **alliances** (both leaders agree) and declare **rivalries** (one-sided labels).
Lifesteal only: the code is in `lifesteal/teams/` (`TeamRelationStore`, `TeamRelationRules`,
`LifestealTeamRelations`, `TeamRelationsMenu`, `AllianceChatCommand`), reached through the Lifesteal
side of `/team` and `/ac`. Survival teams, commands and permissions are untouched.

### Rules

| | Alliance | Rivalry |
|---|---|---|
| Per team | **one** active alliance (`lifesteal_team_alliance_slots.team_id` PRIMARY KEY) | **one** declared rival (partial UNIQUE index); any number of teams may declare *you* |
| Created by | the source leader sends a request, the target's **current** leader accepts | the declaring team's current leader; no approval, the target is told |
| Ended by | either team's current leader, no approval (`/team ally leave`) | only the declaring team's current leader (`/team rival remove`) |
| Cooldown | **24 h for both teams** after a leader ends it (`dissolution-cooldown-hours`); never after a disband | none |
| Gives | friendly-fire protection, no heart stealing between allies, `/ac` chat | nothing (label only: no Coins, hearts, rewards, kill/death statistics, scores or Elo) |

- Alliance and rivalry between the same two teams can't coexist, in either direction: a request is
  refused (and an accept re-refused) while any rivalry exists between them, and a rivalry against an
  ally is refused. An alliance is **never** dissolved automatically to make room for a rivalry: end
  it first (normal rules, both teams get the cooldown), then declare. Alliance cooldowns don't
  restrict rivalries. Database triggers refuse both combinations and self-relationships too.
- Requests have a random id, source and target team ids, sender, `created_at` and `expires_at`
  (UTC millis, default +24 h, `request-expiration-hours`). Expiry is judged from the stored time, so
  requests keep counting down while the server is offline. A request reserves nothing: accepting
  re-checks that both teams exist, the acceptor leads the target **now**, the request is still
  pending and unexpired, neither team has an alliance or cooldown and there's no rivalry. Accepting
  invalidates every other pending request involving either team. Requests end as ACCEPTED,
  DECLINED, CANCELLED, EXPIRED or INVALIDATED (alliance, rivalry or disband) and are kept as history.
- Anti-spam: one pending request per (source, target); a request to a team that already asked you
  points you to `/team ally accept`; at most **3** pending outgoing requests per team; after a
  request was declined or cancelled, the same team can't be asked again for **10 minutes**. The
  existing 300 ms / one-at-a-time action throttle applies too.
- Accepting is refused while any member of either team is combat-tagged, so an alliance can't be
  formed mid-fight to end a legitimate fight (and its heart) retroactively. Ending one is always
  allowed and takes effect immediately.
- Leadership is read live in every transaction: a former leader can't accept, decline, cancel,
  dissolve, declare or withdraw anything; the new leader can at once. A transfer keeps the alliance,
  rivalries, pending requests and the cooldown exactly as they were.

### Friendly fire and hearts

Allies are decided from the **current** membership cache and the **current** alliance only
(`TeamCombatRules.Relation.ALLIES` via `withAlliance`); there is no grace window, so protection ends
the instant an alliance (or a membership) ends. With `alliances.friendly-fire: false` (default) the
existing `LifestealTeamCombatListener` cancels an ally's damage exactly as it does a teammate's
(melee, projectiles by shooter, fireworks, harming/lingering potions, combustion, TNT and End
Crystals, judged by `damageSource.causingEntity`), even when team friendly fire is on; fall, lava,
fire ticks, mobs and other environment damage are never touched, and hits between unrelated teams
are unchanged. Cancelled hits create no combat tag. The Combat NPC of a logged-out ally is protected
the same way.

Allies **never** steal hearts from each other: `heartCheck` returns `ALLIES` → `HeartRules.Decision.Allies`
(no heart moved, no cooldown, deduplicated, audited `PVP_REJECTED` / `ALLIES`), even with alliance
friendly fire on. Lifesteal bounties treat allies like teammates (no claim). Teammate rules,
limits and cooldowns are unchanged.

### Ranked Team Wars

Inside a match the roster sides decide first (`rankedSides` runs before any alliance check), so allied
teams can be matched (nothing in matchmaking reads alliances or rivalries) and fight normally: hits
land, hearts are at risk under the normal heart rules, inventory snapshots/restores are unchanged.
The override covers only the two players of the **same** match; a participant and an ally outside
that match are still protected, and the moment the match ends the ordinary alliance protection is back.
Rivalries are never read by matchmaking, individual Elo or team Elo.

### Alliance chat

`/ac <message>` (alias `/alliancechat`; JoshyMC had no `/ac` before; it's only registered where
Lifesteal is hosted, LIFESTEAL scope; if another plugin also claims `/ac`, `/joshymc:ac` always
works). Sent as `[Alliance] [Team] Name: message` to the online members, in Lifesteal, of the
sender's team and its current ally, never anyone else and never public chat. Recipients are
recomputed from the relationship cache right before delivery. It follows the rules a public message
meets: Lifesteal-scoped mutes (`isMutedIn`), the global chat mute (`joshymc.chat.mute.bypass`), the
profanity filter (`joshymc.chat.profanity.bypass`), the repeated-message rule of
`chat.anti-spam` (the message is refused; no automatic mute from `/ac`), and `/ignore`. Messages are
logged to the console like chat. Lifesteal teams have **no** team chat, so there is nothing to leak.

### Land and permissions

Alliances grant nothing else: no claims, containers, block access, team management, invitations,
banks, storage or permissions. Nothing outside `lifesteal/teams/` reads alliances (checked), no
permission group changes and no membership or ownership changes.

### Disbanding

`LifestealTeamStore.disband` calls `TeamRelationStore.cleanupDisbanded` **inside the disband
transaction**: the alliance ends as `ENDED_DISBANDED` with **no** cooldown for the survivor (it can
ally again at once), the disbanded team's cooldown row is deleted, its outgoing and every incoming
rivalry become REMOVED, and pending requests involving it INVALIDATED. Affected teams are told. A
second disband/cleanup finds nothing. At startup, `repairOrphans` cleans any relationship that still
references a team that isn't ACTIVE (crash or manual edit), and requests that ran out during
downtime are expired.

### Notifications

Only the affected teams' online members are told (request received/sent, accepted, declined,
cancelled, expired, alliance formed/ended + cooldown, rivalry declared/withdrawn, removals after a
disband); nothing is broadcast. **Offline limitation**: there is no offline notification store for
teams, so offline members aren't told later, except that a leader joining is reminded of pending
incoming requests. Everything is visible in `/team info` and the menu.

### Commands

| Command | Who | |
|---|---|---|
| `/team ally` | member | Alliances & Rivalries menu |
| `/team ally <team>` (`/team ally request <team>`) | leader | Send a request |
| `/team ally accept [team]` / `deny [team]` (`decline`) | target leader | Team may be left out with exactly one request |
| `/team ally cancel [team]` | source leader | |
| `/team ally leave [confirm]` (`end`, `dissolve`) | either allied leader | Asks first; starts the cooldown |
| `/team rival <team>` (`/team rival declare <team>`) | leader | |
| `/team rival remove` (`withdraw`) | declaring leader | |
| `/ac <message>` | members of allied teams | |

Team names are matched exactly (case-insensitive), never by prefix. Keywords win over team names: a
team literally called `accept` is asked with `/team ally request accept`. Tab completion only
offers teams the keyword can act on. No new permission: everything uses `joshymc.lifesteal.team.use`,
leadership is the only authority, and there is no admin bypass. `/team admin info <team>` adds the
pending requests; `/team admin pvp` shows "allies".

### GUI and profiles

The `/team` main menu now has six entries centered in the middle row with the middle slot empty
(the `SettingsLayout` rule): Your Team 10, Members 11, Invitations 12, Management 14,
**Alliances & Rivalries 15**, Available Teams 16. The relations screen (27 slots): Alliance 10
(green; leader: end it / pick a team), Incoming 11, Outgoing 12, Declared Rival 14 (red; leader:
withdraw / pick a team), Rivals Against You 15, Alliance Cooldown 16; Back/Close as usual. Requests
(36 slots): incoming row 1 (leader: left-click accept, right-click decline), outgoing row 2 (leader:
click to cancel); members see them read-only. Clicking a team in **Available Teams** now opens its
public **profile** (36 slots) instead of printing `/team info`: leader, member heads (leader first,
gold), Alliance (green), Declared Rival (red, "(mutual)" only when both declared) and Rival of (red,
declared *against* it); a leader of another team also gets Request/Accept/Cancel/End Alliance (20)
and Declare/Withdraw Rival (24). `/team info [team]` shows the same lines (plus the team's own
cooldown for its members). Chat formatting is unchanged.

Every click re-reads the team by id and calls the same `LifestealTeamRelations` action as the
command (current leader and state re-checked, then again in the store transaction), so a stale menu
can't accept an expired request or act with outdated leadership; shared GuiManager rules keep items
from being moved. Open menus aren't refreshed live; they show the new state when reopened.

### Storage (same `data.db`, created where Lifesteal teams run; no migration of existing tables)

| Table | |
|---|---|
| `lifesteal_team_alliances` | `id`, `team_a`/`team_b` (sorted team ids), `status` ACTIVE/DISSOLVED/ENDED_DISBANDED, `created_at`, `request_id`, `ended_at`, `ended_by`, `end_reason` |
| `lifesteal_team_alliance_slots` | `team_id` PRIMARY KEY, `alliance_id`: the one-alliance guard |
| `lifesteal_team_alliance_requests` | `id`, `source_team`, `target_team`, `sender`, `created_at`, `expires_at`, `status`, `resolved_at`, `resolved_by`; UNIQUE (source, target) while PENDING |
| `lifesteal_team_alliance_cooldowns` | `team_id` PRIMARY KEY, `until`, `alliance_id`, `started_at` (a longer cooldown is never shortened) |
| `lifesteal_team_rivalries` | `id`, `declarer_team`, `target_team`, `declared_by`, `declared_at`, `status` ACTIVE/WITHDRAWN/REMOVED, `ended_at`, `ended_by`, `end_reason`; one ACTIVE per declarer |

Every change is one transaction with conditional updates and is audited in the existing
`lifesteal_team_audit` (`ALLY_*`, `RIVAL_*`, `RELATIONS_REMOVED_DISBANDED`). Changes run on the team
worker thread, never the main thread; the manager's relationship cache is replaced on that thread
right after each commit, so damage events and `/ac` never query the database. With
`alliances.enabled: false` or `rivalries.enabled: false` nothing is deleted.

### Configuration (`lifesteal.yml > teams`)

| Key | Default | Valid |
|---|---|---|
| `alliances.enabled` | true | false = no protection, no `/ac`, no alliance changes (data kept) |
| `alliances.request-expiration-hours` | 24 | 1..168 |
| `alliances.dissolution-cooldown-hours` | 24 | 0..720 |
| `alliances.friendly-fire` | false | true = allies can damage each other (still no heart stealing) |
| `alliances.chat-enabled` | true | `/ac` |
| `rivalries.enabled` | true | false = no new declarations/withdrawals (labels still shown) |

A file without these keys loads the defaults with one warning; a wrong value makes `lifesteal.yml`
invalid like every Lifesteal setting.

### Tests

`./gradlew -I scripts/lifesteal-alliance-check.init.gradle.kts lifestealAllianceCheck`
(`scripts/LifestealAllianceCheck.kt`, real SQLite in temp folders): settings; request sending,
authorization, duplicates, reverse requests, anti-spam, decline/cancel, 24 h expiry and the sweep;
acceptance, one alliance per team, invalidation, the slot PRIMARY KEY and triggers; dissolution by
either leader, double dissolution, the 24 h cooldown both ways and its exact end; rivalries
(one-sided, one per team, independent slots, only the declarer withdraws, no cooldown, no rewards or
stats in the code); exclusivity in both directions and the triggers; disband cleanup (no survivor
cooldown, nothing orphaned, idempotent) and startup repair; leadership transfers; restarts
(alliances, requests with their deadlines, rivalries, cooldowns, downtime expiry); concurrency
(simultaneous accepts, dissolutions, accept vs rivalry, accept vs disband, duplicate requests);
ally damage/potion/heart rules incl. the real heart store and ranked sides; chat recipients;
profiles; and source checks for the Lifesteal-only wiring, mutes/filter/ignore in `/ac`, GUI
re-checks, land isolation and Survival isolation.

### Manual tests (need a Paper server; not done yet)

1. Teams A, B, C (leaders LA, LB, LC; member MA). MA `/team ally B`: refused. LA `/team ally B`:
   both teams told; LB sees [Accept]/[Decline] and the typed commands. LB accepts: both teams
   "now allied". `/team info A` shows Alliance in green; `/team` → Alliances & Rivalries shows it.
2. MA hits MB with sword, bow, harming splash, lit TNT: no damage, no tag, action bar hint. A zombie,
   lava and fall still hurt MB. MA hits MC (team C): normal.
3. `/ac hi` from MA: A and B members see `[Alliance] [A] MA: hi`; C sees nothing. Mute MA: refused.
4. LA `/team ally C`: refused (one alliance). LB `/team ally leave confirm`: both told + 24h
   cooldown; MA now damages MB; `/ac` refused; `/team ally C` refused with the time left. Restart:
   the cooldown is still there.
5. LA `/team rival B` (right after ending the alliance): works; B is told; `/team info B` shows
   "Rival of: A" and B's own Declared Rival stays None. LB `/team rival remove`: "no rival".
   LA `/team ally B` later: refused (rivalry).
6. LA transfers leadership to MA: LA can no longer accept/withdraw; MA can. Cooldown unchanged.
7. A allied with B; LA disbands A: B told, B can `/team ally C` immediately.
8. Ranked test match A vs B while allied: hits land, a kill moves a heart, inventories restore;
   after the match MA can't hurt MB again.
9. Survival server: `/ac` doesn't exist, `/team` Survival unchanged, no alliance tables.

## Commands

`/team` (aliases `/t`, `/teams`) is one command on both gamemodes. `TeamCommandRouter` sends a
sender in Lifesteal (a Lifesteal world, or the console of a Lifesteal server) to the Lifesteal
system and everyone else to the Survival `TeamCommand`, which keeps its SURVIVAL scope (refused
outside Survival while Survival is locked, same message as the gamemode gate) and its
`joshymc.team` permission. In Lifesteal it never falls back to Survival teams, even while Lifesteal
teams are paused.

| Command | Who | |
|---|---|---|
| `/team` | player | Team menu (GUI) |
| `/team create <name>` | player, `joshymc.lifesteal.team.create`, not in a team | Creates a team, you lead it |
| `/team invite <player>` | leader | Target must be online, in Lifesteal, teamless; the team must have room |
| `/team accept [team]` / `deny [team]` (`join`, `decline`) | invited player | The team may be left out with exactly one invitation |
| `/team leave` | member | Leaders must transfer or disband first |
| `/team kick <player>` (`remove`) | leader | Works for offline members |
| `/team transfer <player> [confirm]` (`promote`) | leader | Without `confirm` it asks first |
| `/team disband [confirm]` | leader | Without `confirm` it asks first; `teams.allow-disbanding` |
| `/team info [team]`, `/team list [page]` | anyone (also console) | From memory, no database query |
| `/team admin info <team>` | `joshymc.lifesteal.team.admin` | Adds the team id, version, creator, pending invitations |
| `/team admin disband <team> [confirm]` | admin | Logged and audited (`ADMIN_DISBAND`) |
| `/team admin transfer <team> <player> [confirm]` | admin | Logged and audited (`ADMIN_TRANSFER`) |
| `/team admin pvp <player> <player>` | admin | Team PvP diagnostics (read-only, issue #1348) |

Chat buttons (`[Accept]`, `[Confirm]`) also print the typed command, because Bedrock clients can't
click chat.

### Permissions

| Node | Default | |
|---|---|---|
| `joshymc.lifesteal.team.use` | true | Every Lifesteal team feature |
| `joshymc.lifesteal.team.create` | true | `/team create` |
| `joshymc.lifesteal.team.admin` | op | `/team admin ...` only. Never makes anyone a leader and never skips the team rules (5 players, one team, members only) |

`/team`'s own Bukkit permission is `joshymc.team;joshymc.lifesteal.team.use` (either one); the
router then checks the permission of the system that applies. Survival permissions are unchanged.

## GUI

`/team` in Lifesteal: **Your Team**, **Team Members** (heads, leader in gold, online status; the
leader can click a member to make them leader or remove them), **Team Invitations** (left-click
accept, right-click decline; a leader also sees who they invited), **Team Management** (leader:
invite / transfer / disband; member: leave; no team: create) and **Available Teams** (paged). Back
and close buttons use `GuiStyle`. Destructive actions have a confirm screen.

No button is trusted: every click calls the same `LifestealTeamManager` action as the command,
which checks permission, the Lifesteal world, leadership and the current state again, and the store
checks once more inside its transaction. Clicks can't move items (shared `GuiManager` rules).

## Storage

In the Lifesteal server's own `data.db` (`LifestealTeamStore`, Bukkit-free). Tables are only
created where Lifesteal runs.

| Table | |
|---|---|
| `lifesteal_teams` | `id` (random UUID, never changes; future team Elo keys on it), `name` (display), `canonical_name` (lower case, UNIQUE among ACTIVE teams), `leader` (UNIQUE among ACTIVE teams), `status` ACTIVE/DISBANDED, `created_at`, `created_by`, `version`, `disbanded_at`, `disbanded_by` |
| `lifesteal_team_members` | `uuid` PRIMARY KEY, `team_id`, `joined_at`. Triggers refuse a member of a non-ACTIVE/unknown team, a sixth member, and moving a member between teams |
| `lifesteal_team_invites` | (`team_id`, `target`) PRIMARY KEY, `inviter`, `created_at`, `expires_at` |
| `lifesteal_team_membership_history` | team, player, joined/left time, reason (LEFT, KICKED, DISBANDED) |
| `lifesteal_team_audit` | every change, with actor and target |

Players are identified only by UUID (Floodgate UUIDs for Bedrock players, so Java and Bedrock
players share teams); names are only resolved for display, through `PlayerResolver` and the online
player list (never `Bukkit.getOfflinePlayer(String)`).

**Retention**: disbanded teams keep their row, history and audit; nothing is deleted
automatically. A disbanded team's name can be used again by a new team (with a new id).

### Consistency and concurrency

Every change is one transaction that re-reads the current rows, decides, writes, and moves the
team row on with `WHERE version = <read version>` (a stale write throws and rolls everything back).
The database lock serializes transactions, so two players taking the last slot, two disbands or
two transfers run one after the other and the second sees the first's result. Accepting re-checks
the invitation (exists, not expired), the team (active, has room) and the player (in no team).

Changes run on one worker thread (`JoshyMC-LifestealTeams`), never on the main thread; results come
back to the main thread, which updates the in-memory cache (older snapshots never replace newer
ones) and notifies the players involved. One change per player at a time and at most one per
300 ms. Shutdown waits up to 5 s for changes already handed to the worker, before the database
closes. Membership lives in the database, not the session, so reconnects can't duplicate or lose
it; quitting only drops the throttle state.

## For other systems

`plugin.lifestealTeamManager` (memory only, safe on the main thread):
`teamOf(uuid)`, `team(id)`, `teamByName(name)`, `isInTeam(uuid)`, `sameTeam(a, b)`, `isLeader(uuid)`,
`hasFreeSlot(teamId)`, `roster(teamId)`, `maxSize`, `active`. A future matchmaker validates a ranked
roster against `roster(teamId)` and keys team ratings on the stable team id; nothing here is tied to
a match.

## Configuration (`lifesteal.yml > teams`)

| Key | Default | Valid |
|---|---|---|
| `max-size` | 5 | 1..5 |
| `enabled` | true | true/false (off: everything refused, data kept) |
| `invite-expiration-seconds` | 60 | 10..3600 |
| `allow-creation` | true | true/false |
| `allow-disbanding` | true | true/false (admins can still disband) |
| `name-min-length` / `name-max-length` | 3 / 16 | 1..16, min ≤ max |
| `reserved-names` | [] | list of extra protected names |
| `friendly-fire.enabled` | false | true/false (see "Team PvP") |
| `friendly-fire.heart-transfers` | false | true/false, only used with friendly fire on |

A file from before this change (only `max-size`) loads with one warning. A wrong value makes
`lifesteal.yml` invalid as a whole (as for every Lifesteal setting): teams (and hearts) pause,
stored data is kept, nothing is guessed. Survival settings are never touched.

### Team names

Letters, digits and `_`, length per config, display capitalization kept, compared by the
lower-case canonical form. Refused: the server's banned words (`banned-words.yml`, also with `_`
removed or read as spaces), and staff/system identities (`TeamNames`): names containing admin,
staff, moderator, joshy, console, official, operator, developer, mojang, anticheat, or equal to
owner, helper, server, system, support, manager, mod, op, dev, lifesteal, survival, ... ; checked
ignoring case and `_` and with look-alike digits (`Adm1n`, `St4ff`).

## Testing

Automated: `./gradlew -I scripts/lifesteal-team-check.init.gradle.kts lifestealTeamCheck`
(`scripts/LifestealTeamCheck.kt`, Bukkit-free, real SQLite in temp folders): settings and
validation, names, creation/duplicates/invalid names/creation while in a team, invitations
(duplicate, self, expiry, deny, sweep), full teams, leave/kick/transfer/disband, offline (UUID-only)
membership, restarts, the database triggers, concurrency (final slot, many accepts, duplicate
creation, concurrent disbands/transfers, mixed changes), rollback on failures and stale writes,
a closed database, Bedrock UUIDs, and source checks for Survival isolation, permissions, GUI
authorization and session cleanup. `gamemodeScopeCheck` covers the `/team` routing scope.

Team PvP (issue #1348): `./gradlew -I scripts/lifesteal-team-combat-check.init.gradle.kts lifestealTeamCombatCheck`
(`scripts/LifestealTeamCombatCheck.kt`): the friendly-fire settings (defaults, validation, an old
file), same-team / different-team / no-team / self / recently joined / recently removed /
disbanded / unknown relations, melee and projectile decisions with friendly fire on and off,
environmental damage, harmful and helpful potions, every combat-tag refusal, teammate heart
refusals in the real heart store (audited, deduplicated, no cooldown, hearts unchanged), normal
enemy steals, victims at 1 heart, killers at 20, the cooldown surviving team changes, a closed
database, Java/Bedrock UUIDs, the recent-teammate memory (expiry, bounds, sweep, disconnects), and
source checks for the Lifesteal-only registration, event priorities, combat tagging and display.

### Manual tests (need a Paper server; not done yet)

Nothing here has been run on a real server or with real Bedrock clients, so full compatibility
isn't claimed. On a Lifesteal test server (and with a Survival test server next to it):

1. Java player A: `/team create Wolves`; `/team` opens the menu; the leader is shown in gold.
2. A: `/team invite B` (Java) and `/team invite .C` (Bedrock via Floodgate); both get the invitation
   with the typed command; a second invite says "already has a pending invitation" with no new
   message to the target. Wait 60 s: both sides are told it expired; accepting then fails.
3. B and C accept (C through the GUI on Bedrock). Fill the team to 5, then a sixth accept is refused.
4. B leaves; A kicks C while C is offline; C rejoins: no team. Restart the server: team intact.
5. A `/team leave` is refused; `/team transfer B` asks, `confirm` transfers; A leaves; B disbands
   with confirm; members online are told; hearts, inventory and balance unchanged.
6. Staff: `/team admin info|transfer|disband` with and without `joshymc.lifesteal.team.admin`.
7. Survival server: `/team` still opens the Survival team GUI, Survival teams unchanged, no
   `lifesteal_team*` tables in its `data.db`.

Team PvP (issue #1348), also not yet run on a live server:

8. A and B in one team, C in another, D teamless (mix Java and Bedrock). A hits B with a sword,
   bow, crossbow, trident, firework crossbow, harming and poison splash, lingering poison, lit TNT
   and an End Crystal: B takes no damage or effect, nobody is combat-tagged, A sees the action-bar
   hint. A splashes B with healing: it heals. The same attacks on C and D work and tag normally.
9. B jumps into lava / falls / gets hit by a zombie next to A: normal damage.
10. A fights C (tagged): `/team leave`, `/team accept`, `/team kick`, `/team disband` involving a
    tagged player are refused; `/team transfer` works. After the tag ends they work.
11. A leaves the team and immediately shoots / kills B: blocked / no heart. After 15 s, A kills B:
    a heart moves. Repeat within 30 minutes: cooldown.
12. Set `teams.friendly-fire.enabled: true`, `/joshymc reload`: `/team info` and the menu show "On",
    A can hurt and kill B, but no heart moves; add `heart-transfers: true`: the kill steals a heart.
13. Survival server: team PvP, damage and combat tagging unchanged; no Lifesteal friendly-fire
    messages.
