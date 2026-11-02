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
