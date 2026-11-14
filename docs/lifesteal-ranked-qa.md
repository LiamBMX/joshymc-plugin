# Ranked Team Wars: closed-beta testing guide (issue #1531)

Lifesteal only. This is the staff guide for testing Ranked Team Wars on a **dedicated test
server** before any decision to open them to players. It goes with three other files:

| File | What it is |
|---|---|
| [lifesteal-ranked-qa-matrix.md](lifesteal-ranked-qa-matrix.md) | Every test scenario, with an ID, the steps, the expected result from the current code, and fields to fill in |
| [lifesteal-ranked-bug-report.md](lifesteal-ranked-bug-report.md) | The bug report template |
| [lifesteal-ranked-release-gate.md](lifesteal-ranked-release-gate.md) | The release gate checklist and the decision record |

It builds on the implementation audit in [lifesteal-ranked-beta.md](lifesteal-ranked-beta.md)
(sections A–M) and the core loop checklist in [lifesteal-beta.md](lifesteal-beta.md). Those files
describe *what the code does*. This guide describes *how to prove it on a real server*.

> **Nothing in this toolkit has been run on a live server.** Writing a checklist is not testing.
> A scenario only passes when a tester ran it and attached evidence.

## 0. Ground rules

- **Never test on production.** Ranked stays `ranked-team-wars.enabled: false` on the live
  Lifesteal server until the release gate (separate file) is signed. Crash tests (`kill -9`),
  restarts mid-match, forced restore reviews and data resets happen on the test server only.
- **Hearts are real on the server they move on.** Ranked kills, forfeits and staff TEST matches go
  through the normal heart service (`HeartStore.transferPvp`). On the test server that's fine;
  that's the point.
- **Use the real commands.** Every command, permission and config key in this guide and the matrix
  is checked against the source by `rankedBetaReadiness` (section 15). If it says a reference is
  wrong, the docs are wrong; fix the docs, never invent a command.
- **Don't change gameplay while testing.** Only edit the config keys this guide names. Write down
  every change (key, old value, new value, time) in the session log (section 10).

## 1. Preparing a dedicated test server

1. A separate Paper 26.2 server running the **same JoshyMC build** you're testing (the jar from the
   PR/tag; write its version or commit in the session log). `./gradlew build` makes
   `jar/joshymc-1.0-SNAPSHOT-all.jar`.
2. Its own `plugins/Joshymc/` folder, so it has its **own** `data.db`. Never copy the production
   `data.db` onto it (that would mix real players' hearts, teams and Elo into test results).
3. Make it host Lifesteal: in `config.yml`, `gamemode-lock.server-gamemode: lifesteal` and
   `network.server-id: lifesteal` (see [lifesteal-server.md](lifesteal-server.md)). After the
   first start, `/jgamemode info` must show Lifesteal as the server gamemode and `lifesteal.yml`
   as valid. If `lifesteal.yml` is invalid, hearts, teams and ranked pause; fix that first.
4. Install the same companion plugins as production that touch ranked: LuckPerms (staff ranks),
   WorldGuard (arena regions), Grim if used in production, Geyser/Floodgate for the Bedrock tests.
   Note each version in the session log.
5. Leave `network.enabled: false` unless you're running the proxy tests (`/lobby`, `/survival`);
   a standalone test server doesn't need Velocity.
6. Before the first ranked test, stop the server and take a **baseline backup** of
   `plugins/Joshymc/` (section 13 uses it).

## 2. Enabling Ranked Team Wars safely

Ranked needs three things: a launched season past the ranked unlock, the master switch, and at
least one READY arena per size.

**Season / unlock** (`lifesteal.yml > progression`). On a fresh test server, shorten the schedule
so you don't wait 72 hours. The parser requires `grace ≤ ranked ≤ end`, so this is valid:

```yaml
progression:
  enabled: true
  grace-period-hours: 0
  ranked-team-wars-unlock-after-hours: 0
  end-unlock-after-hours: 168
```

Then `/joshymc reload` and `/lifesteal admin launch activate confirm` (needs
`joshymc.lifesteal.progression.admin`). A launched season can never be moved or reset, which is
another reason this is a test-server-only step. `/lifesteal progress` must show ranked unlocked.
To test the grace period or the Day 3 lock themselves (matrix MM-22), set non-zero hours on a
fresh `data.db` instead.

**Master switch.** Start with `ranked-team-wars.enabled: false`. Staff with
`joshymc.lifesteal.ranked.admin` can still build arenas, run TEST matches and open `/ranked`. Run
the TEST-match and closed-state tests first (matrix MM-01, MM-02, ST-12). Only then set
`ranked-team-wars.enabled: true` and `/joshymc reload` to test public queues.

**Matchmaking switch.** `ranked-team-wars.matchmaking.enabled: false` refuses new queues and
challenges but keeps staff TEST matches. Useful to freeze the queue while investigating.

**Helpful test settings** (record any change in the session log; defaults in brackets):

| Key | Why you might change it on the test server |
|---|---|
| `ranked-team-wars.countdown-seconds` [10] | keep it; the countdown tests rely on it |
| `ranked-team-wars.max-match-minutes` [20] | set 1–2 to test the time-limit cancel (EL-10) without waiting 20 minutes |
| `ranked-team-wars.elo.same-opponent-limit` [3] | keep 3 to test the farming limit (MM-19, EL-06) |
| `ranked-team-wars.elo.rate-challenges` [false] | keep false; EL-12 checks challenges stay unrated |
| `ranked-team-wars.disconnect-forfeit.heart-loss` [last-opponent] | test both values (DR-04, DR-05) |
| `heart-items.withdraw-eligibility.enabled` [true] | keep true for HT-12; turn off only to test withdrawing with brand-new test accounts, and say so |
| `pvp.same-victim-cooldown-minutes` [30] | keep 30; the cooldown tests count on it |
| `combat-logging.count-timeouts` [true] | keep; DR-07 relies on it |

Never change `hearts.starting` (10), `hearts.maximum` (20) or `hearts.minimum` (1): those are the
approved rules under test.

## 3. Required staff permissions

All of these are `default: op` unless stated. Grant them through LuckPerms to the tester accounts
that need them, so the hierarchy tests use real staff ranks, not op.

| Permission | Needed for |
|---|---|
| `joshymc.lifesteal.ranked.admin` | `/lifesteal admin arena ...`, `/lifesteal admin ranked ...` (also grants spectate), `/ranked` while it's closed |
| `joshymc.lifesteal.ranked.spectate` | `/lifesteal admin ranked spectate <match>` / `unspectate` only |
| `joshymc.lifesteal.hearts.admin` | `/lifesteal hearts get\|set\|add\|remove <player> [amount]`, `items`, `void`, `reviews` |
| `joshymc.lifesteal.hearts.give` | `/lifesteal hearts give <player> <amount>` (Heart items) |
| `joshymc.lifesteal.hearts.monitor` | `/lifesteal hearts monitor` (transfer monitoring flags) |
| `joshymc.lifesteal.team.admin` | `/team admin info\|disband\|transfer\|pvp\|progression` |
| `joshymc.lifesteal.progression.admin` | `/lifesteal admin launch ...` |
| `joshymc.lifesteal.combat.admin` | `/lifesteal combat status\|logs\|incident\|failures` |
| `joshymc.reload` | `/joshymc reload` |
| `joshymc.invsee` / `joshymc.invsee.edit` | `/invsee` (read-only / editable) for the staff tests |

Player-side permissions are `default: true`: `joshymc.lifesteal.ranked.use`,
`joshymc.lifesteal.team.use`, `joshymc.lifesteal.team.create`, `joshymc.lifesteal.withdraw`.
`joshymc.lifesteal.ranked.stats.others` is also `default: true` (anyone can read others' stats).

**Staff hierarchy.** Protection follows the 12 ranks in [staff-permissions.md](staff-permissions.md)
(Owner > Head Manager > Staff Manager > Community Manager > Lead Admin > Admin > Jr Admin >
Lead Mod > Mod > Jr Mod > Helper > Trainee), read from LuckPerms groups mapped in
`config.yml > staff-ranks.groups`. A staff member can only act on someone strictly below them
(`staff-ranks.allow-equal-rank-actions` is false); console is always allowed. For the staff tests
you need, at minimum: one **Admin** with `joshymc.lifesteal.ranked.admin`, one **Mod** without it,
one **Owner**-ranked account (or console), and ordinary players.

## 4. Required player accounts

| Need | Accounts |
|---|---|
| Every size up to 5v5 in one match | 10 players |
| Two matches at once (MM-12) | 4 players for two 2v2s (8), or more for larger sizes |
| Outsider / spectator / interference tests | +1 outsider player, +1 staff spectator |
| Hierarchy tests | Admin, Mod, Owner (or console) staff accounts |
| Bedrock | at least 1 Bedrock (Geyser/Floodgate) player, ideally in a mixed Java + Bedrock team |
| Shared connection (HT-13) | 2 accounts on one connection (ask; never log or share addresses) |

Write each account's **name and UUID** in the session log once (`/lifesteal hearts get <player>`
and `/team admin info <team>` help confirm who is who). Bug reports then refer to the test name;
no personal details, no IP addresses.

## 5. Required teams

Lifesteal teams (max 5, `teams.max-size`) are made with the normal team commands:
`/team create <name>`, `/team invite <player>`, then the invitee `/team accept`. Make at least
four teams (two matches at once) named so they're easy to read in logs, e.g. `QA_Red`, `QA_Blue`,
`QA_Green`, `QA_Gold`, with one leader each. Put the Bedrock player in one of them. For the
repeated-opponent tests (MM-19, EL-06/07) you also need spare players to rebuild a team under a
new name.

`/team admin info <team>` shows the members and the leader. Record team **names and ids**: Team
Elo and match records use the id, not the name.

## 6. Setting up test arenas

One arena per size is the minimum; **two** arenas that host 2v2 are needed for the
"two matches at once" test. Build them in the Lifesteal world, away from spawn, with no claims
inside. For each arena (all `/lifesteal admin arena ...`):

1. `create <id> [display name]` while standing in the arena's world.
2. `setcorner <id> 1` and `setcorner <id> 2` at opposite corners (at least a few blocks each way;
   the boundary is validated).
3. `setspawn <id> a` and `setspawn <id> b` inside the boundary, one or more per side (up to 5;
   players share spawns round-robin when there are fewer spawns than players).
4. `setspectator <id>` **outside** the boundary (a spectator point inside makes the arena INVALID).
5. `sizes <id> 2,3,4,5` (or `all`, or just the sizes this arena is for).
6. `enable <id>`, then `info <id>`: Readiness must be READY and Availability AVAILABLE.

`/lifesteal admin arena list` shows them all. Optionally put a WorldGuard region on each arena:
JoshyMC protects an arena only while a match or a queue proposal holds it, and idle arenas are
WorldGuard's job (matrix AS-17).

## 7. Preparing test inventories

There are **no ranked kits**: players fight with their own Lifesteal gear, which is snapshotted at
the start and set back at the end. Prepare a **known inventory** per tester and record it
*before* each match (screenshot of the full inventory screen, armor and off-hand included, plus
the XP level and active effects):

- a custom-enchanted custom item (e.g. a crate weapon) and a damaged netherite piece;
- a named shulker box with contents, a bundle with contents;
- a Heart item (`/lifesteal hearts give <player> 1`; note its serial with `/lifesteal hearts items <player>`);
- stackables at odd counts (e.g. 37 arrows, 13 golden apples), a trident (one with Loyalty);
- a full armor set and an off-hand item (totem or shield);
- some XP levels and a potion effect with time left.

Compare after the match against the screenshot, slot by slot. "Looks the same" is not evidence;
the screenshot pair is.

## 8. Preparing permanent hearts

Use `/lifesteal hearts set <player> <amount>` (audited) to put testers at the counts a test needs,
and `/lifesteal hearts get <player>` before and after every kill test. Typical setup:

| Tester | Hearts | For |
|---|---|---|
| normal | 10 | ordinary kills |
| floor | 1 | the 1-heart minimum (victim keeps it, killer gains nothing) |
| cap | 20 | the 20-heart maximum (killer at max gains nothing) |
| withdraw | 2+ | `/withdrawheart` |

Remember the **30-minute same-victim cooldown** is per directed pair (killer → victim) and shared
between open-world and ranked kills. Plan kill tests so a pair isn't reused within 30 minutes
unless the test is about the cooldown. Write the time of every heart-moving kill in the log.

## 9. Testing different team sizes

Run MM-01..MM-05 (TEST and public) at **2v2, 3v3, 4v4 and 5v5**. For a public match the leader
runs `/ranked queue <2v2|3v3|4v4|5v5> [teammates...]` (list `size - 1` teammates to play
yourself, or `size`), every picked player agrees with `/ranked accept` (or the menu), a second
team does the same, and once the match is found everyone accepts within
`ranked-team-wars.matchmaking.confirm-seconds` (30). `/ranked status` shows where the entry is.
For staff TEST matches: `/lifesteal admin ranked testmatch <teamA> <teamB> <2-5> [arena]`, then
`/lifesteal admin ranked testmatch confirm <rx-token>` within 60s, then every player
`/ranked accept rt-...` (the token from their chat message).

## 10. Recording expected match results

Keep one **session log** per test session (a shared doc or a GitHub issue comment):

- date, tester names, server build/commit, JoshyMC config changes made (key/old/new/time);
- for every match: the match id (from the start message or `/lifesteal admin ranked list`), kind
  (PUBLIC/CHALLENGE/TEST), size, teams (name + id), arena, players per side;
- before: hearts per player, Player Elo / Team Elo (`/ranked stats <player>`,
  `/ranked teamstats <team>`), inventory screenshots;
- during: who killed whom and when, disconnects, staff actions;
- after: the winner message, the heart summary lines, hearts per player, Elo per player/team,
  `/lifesteal admin ranked info <match>` (state, restoration, event log),
  `/lifesteal admin ranked rating match <match>` (RATED / UNRATED + reason).

Fill in the matrix's **Actual** and **Status** columns from this log.

## 11. Identifying unexpected outcomes

Treat any of these as a bug, even if it seems minor; report it with the template:

- an item that exists twice, or an item missing after a restore (inventory dupe/loss is always P0);
- a heart that moved when the expected result says it shouldn't, or didn't move when it should;
  a heart count outside 1..20; hearts that don't add up (one side +1 without the other −1);
- an Elo change on a TEST, cancelled, timed-out or challenge match; a rated match beyond the
  same-opponent limit; a rating change that differs between the two sides of one match;
- a match stuck in a state (`/lifesteal admin ranked list` still shows it long after it ended), an
  arena that stays RESERVED/ACTIVE with no match, a queue entry nobody can leave;
- a restricted action that worked (a refused command, teleport, block change or staff action that
  went through), or a normal action that's refused outside a match;
- a snapshot left PENDING/RESTORING/REVIEW (`/lifesteal admin ranked restores`) when nothing went wrong;
- any console error or stack trace during a test (attach it);
- a TPS drop during matches (note `/tps` before/after).

The expected result in the matrix comes from reading the current code. If the server does
something different and you believe the server is right, still report it: either the code or the
doc is wrong.

## 12. Collecting evidence for bug reports

- **Screenshots/video** of chat (the full message), inventory screens, health bar.
- **Command output**: `/lifesteal admin ranked info <match>`, `/lifesteal admin ranked rating match <match>`,
  `/lifesteal hearts get <player>`, `/lifesteal admin ranked queuelog`, `/lifesteal admin ranked restores`,
  `/lifesteal admin ranked reviews`.
- **Audit**: `/admin logs` (look for `RANKED_*` actions).
- **Server log**: `logs/latest.log` lines around the time (JoshyMC logs severe on failed writes).
  Remove player IP addresses from any log you paste (Paper logs them on login).
- **Database** (test server only, server stopped): a copy of `plugins/Joshymc/data.db` can be
  attached privately to a report or read with the readiness tool (section 15).

## 13. Resetting test data safely

There is deliberately **no** command that resets Elo, rating history, match history or seasons
(rating history is append-only and protected by triggers). Don't try to edit those tables by
hand; reset the whole test database instead:

1. `/stop` the **test** server (let it finish: shutdown restores everyone and cancels matches).
2. Move `plugins/Joshymc/data.db`, `data.db-wal` and `data.db-shm` aside (keep them if a bug
   report needs them).
3. Copy the baseline backup's files back (section 1, step 6), or start with no `data.db` for a
   fully fresh state (you'll redo the launch, teams and arenas).
4. Start the server and re-check `/jgamemode info`, `/lifesteal progress`,
   `/lifesteal admin arena list`.

Smaller resets that are fine while the server runs:

- hearts: `/lifesteal hearts set <player> <amount>` (audited);
- teams: `/team disband confirm` (leader) or `/team admin disband <team> confirm` (no team in a match);
- arenas: `/lifesteal admin arena delete <id> confirm`;
- queues: `/lifesteal admin ranked cancelqueue <entry|team> confirm`, `cancelproposal <proposal> confirm`;
- a stuck match: `/lifesteal admin ranked cancel <match> confirm` (restores everyone; hearts that
  moved stay moved).

Never restore a test `data.db` onto production, or a production one onto the test server.

## 14. Restoring the test environment

At the end of a session: cancel/finish every match (`/lifesteal admin ranked list` empty), check
`/lifesteal admin ranked restores` is empty, set `ranked-team-wars.enabled` back to what the next
session expects, put any changed config keys back (from the session log), `/joshymc reload`,
and take a new backup if the next session should start from here.

## 15. Read-only readiness tool

```bash
./gradlew -I scripts/ranked-beta-readiness.init.gradle.kts rankedBetaReadiness
# with a test server's files (copy them while that server is stopped):
./gradlew -I scripts/ranked-beta-readiness.init.gradle.kts rankedBetaReadiness \
    -PreadinessConfig=/path/to/test/plugins/Joshymc/lifesteal.yml \
    -PreadinessDb=/path/to/copy/data.db
```

It prints, without changing anything:

1. whether these QA docs exist, and every command, subcommand, config key, permission, check task
   and docs link they mention, checked against the source (**exit code 1** if one doesn't exist);
2. the `lifesteal.yml` ranked settings parsed by the plugin's own `LifestealConfigParser`,
   missing keys and disabled switches (`ranked-team-wars.enabled`, `matchmaking.enabled`, ...);
3. with `-PreadinessDb`: arenas validated by the server's own `RankedArenaRules` (world loading
   isn't checked offline), sizes without a READY arena, open matches, snapshots
   PENDING/RESTORING/REVIEW, FAILED ratings, the active season and the number of teams. The
   database is opened read-only (SQLite `open_mode` READONLY);
4. the automated check suites available for ranked and related systems.

It never says READY. It grants nothing, writes nothing and needs no credentials.

## 16. Automated suites (run before every live session)

Each is `./gradlew -I scripts/<file>.init.gradle.kts <task>`; all are Bukkit-free or SQLite-backed
and need no server. Run `./gradlew build` first.

| Task | Script | Covers |
|---|---|---|
| `lifestealRankedCheck` | `lifesteal-ranked-check` | arenas, lifecycle, snapshots, forfeits, death detection, environmental credit, monitoring |
| `lifestealRankedQueueCheck` | `lifesteal-ranked-queue-check` | queue, consent, proposals, challenges, start guard |
| `lifestealRankedTestMatchCheck` | `lifesteal-ranked-test-match-check` | staff TEST match confirmation and consent |
| `lifestealRankedEloCheck` | `lifesteal-ranked-elo-check` | Elo math, rating rules, farming limits, exactly-once rating |
| `lifestealRankedStatsCheck` | `lifesteal-ranked-stats-check` | leaderboards, statistics, divisions |
| `rankedInventoryCheck` | `ranked-inventory-check` | cursor/crafting placement, restore protocol, REVIEW |
| `rankedIntegrityCheck` | `ranked-integrity-check` | restricted abilities, staff actions, delegated commands |
| `rankedArenaGuardCheck` | `ranked-arena-guard-check` | block/fluid/piston/explosion interference |
| `rankedArenaAccessCheck` | `ranked-arena-access-check` | entry, relocation, outsider harm, pets, spawns |
| `rankedSpectateCheck` | `ranked-spectate-check` | spectate destination and combat tags |
| `lifestealHeartCheck` | `lifesteal-heart-check` | hearts, items, cooldown, eligibility |
| `heartTransferMonitorCheck` | `heart-transfer-monitor-check` | transfer monitoring (logging only) |
| `competitiveEligibilityCheck` | `competitive-eligibility-check` | god/vanish/creative/over-limit gear rewards |
| `lifestealTeamCheck` / `lifestealTeamCombatCheck` | `lifesteal-team-check` / `lifesteal-team-combat-check` | teams, friendly fire, ranked team-change refusals |
| `lifestealCombatLogCheck` | `lifesteal-combat-log-check` | disconnect classification |
| `staffPermissionCheck` / `rankSecurityCheck` | `staff-permission-check` / `rank-security-check` | staff hierarchy, self/others nodes |
| `combatTeleportCheck` | `combat-teleport-check` | teleport refusals while tagged/held |
| `shutdownCheck` | `shutdown-check` | shutdown order and database close |
| `rankedBetaReadiness` | `ranked-beta-readiness` | this toolkit's references + readiness summary |

A passing suite is **automated** evidence only; the matrix marks which scenarios still need a live
test.

## 17. Pending implementation dependencies

| Dependency | Status on `main` when this was written | Effect on testing |
|---|---|---|
| #1530 Ranked team roster and ownership integrity | **open, not merged** | `/team transfer` (`promote`) and `/team admin transfer` are **not** refused for a team in a queue or a match today (`LifestealTeamManager.transfer`/`adminTransfer` have no ranked check). Members can also still accept an invite into a team that's playing. Matrix TI-01..TI-04, TI-05 are **PENDING #1530**: run them after it merges and judge them by its final behavior, not by this doc. |
| #1465 forfeit heart recipient (`last-opponent`) | merged, **Owner decision open** | DR-04 tests the rule as implemented; the Owner still has to confirm it or switch to `none`. |
| Killer at 20 hearts gets nothing (`KILLER_AT_MAXIMUM = NO_TRANSFER`) | merged, blueprint decision open | HT-06 tests the current rule. |
| Bundle emptying in a match (`PlayerDropItemEvent` coverage) | unverified in code | IR-15 is live-only. |

Everything else referenced here (#1361–#1527) is merged on `main`. If a later PR changes a
behavior, update the matrix's expected result in the same PR.

## 18. Recommended live testing order

1. **Setup checks**: readiness tool, automated suites, `/jgamemode info`, arenas READY.
2. **Closed state**: ST-12 (players can't use `/ranked`), MM-01 TEST 2v2 (proves the lifecycle
   with nothing rated).
3. **Inventory restoration** (IR-*) in TEST matches, because every other test relies on it.
4. **Hearts in ranked** (HT-02..HT-06, HT-14/15), then the open-world heart tests.
5. **Public matchmaking** (MM-*) after setting `ranked-team-wars.enabled: true`, 2v2 → 5v5.
6. **Elo** (EL-*), reading results from the PUBLIC matches you just played.
7. **Arena and spectator** (AS-*) with an outsider and a staff spectator during TEST matches.
8. **Staff abilities** (ST-*) with each hierarchy level.
9. **Disconnects and restarts** (DR-*), last, since they need restarts and crashes.
10. **Team integrity** (TI-*): the non-pending ones any time; the PENDING ones after #1530 merges.
11. Re-run anything that failed after the fix, then fill in the release gate.
