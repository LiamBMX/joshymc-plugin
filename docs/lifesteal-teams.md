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
- No team bonuses, banks, homes, claims, chat, rewards, leaderboards or PvP rules. **Friendly fire
  and teammate heart stealing are not changed**: the blueprint defines no team PvP rule, so
  teammates hurt and steal hearts from each other exactly like anyone else. That policy belongs to a
  dedicated follow-up; the heart system has no team rule and was not modified.

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
