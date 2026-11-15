# Lifesteal closed beta: release candidate, staging, restart and deployment guide (issue #1558)

Lifesteal only. This is the **one process** staff follow to build a Lifesteal beta build, prove it
on a staging server, deploy it once, keep it running through a long test session, and update it in
planned maintenance windows. It changes no gameplay, config default or database schema.

| File | What it is |
|---|---|
| this file | release candidate checklist, staging plan, restart checklist, soak test, severity, cadence, deployment/rollback, go/no-go |
| [lifesteal-beta-templates.md](lifesteal-beta-templates.md) | copy-paste templates: release candidate record, bug report, known issues, soak log |
| [lifesteal-beta.md](lifesteal-beta.md) | core loop status and the 15-step closed beta checklist (reused in section 2) |
| [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) | the Ranked QA Toolkit: test server setup, ranked tests, evidence, resets (reused, not repeated) |
| [lifesteal-ranked-qa-matrix.md](lifesteal-ranked-qa-matrix.md) | every ranked scenario by ID (IR-, HT-, MM-, EL-, AS-, ST-, DR-, TI-) |
| [lifesteal-ranked-release-gate.md](lifesteal-ranked-release-gate.md) | the separate decision to turn on `ranked-team-wars.enabled` |

> **Nothing in this guide has been run yet.** No staging session, soak test or restart test has
> been recorded. Every measurement field below is empty on purpose: fill it from a real session,
> never from an estimate.

## 0. Ground rules

- **Production is never a test server.** Crash tests, `kill -9`, forced restore reviews, data
  resets and launch scheduling experiments happen on the staging server only.
- **Only release builds go to production.** A jar built on someone's machine says
  `1.0-SNAPSHOT`; it is never deployed (section 1.4).
- **One deploy per maintenance window.** Merged PRs don't go live one by one (section 6).
- **Backups before every deploy, with the server stopped** (section 1.7).
- **Don't change gameplay to make a test pass.** Change only the config keys the test names, and
  write every change in the session log.
- **Privacy.** Bug reports and logs posted anywhere shared use Minecraft names and UUIDs only;
  never IP addresses, connection hashes, emails or Discord details (Paper logs addresses on login:
  cut those lines out).

## 1. Release candidate (RC) checklist

A release candidate is **one exact, already-released tag** picked for the beta. It is not "the
latest main". Fill in the RC record in [lifesteal-beta-templates.md](lifesteal-beta-templates.md)
section 1 as you go.

### 1.1 How builds are made here (facts, so nobody guesses)

- Every PR merged to `main` is auto-merged after CI and **auto-released**: the Release workflow
  builds the merged commit with `-PreleaseVersion=1.0.N`, tags `v1.0.N` and publishes
  `joshymc-1.0.N.jar` plus `joshymc-1.0.N.jar.sha256` on GitHub Releases. So there is a new
  release for almost every PR; most of them are *not* beta candidates.
- CI (`ci.yml`) on a PR only runs `./gradlew build`. **The check suites under `scripts/` are not
  run by CI**; they are run by hand on the candidate commit (1.3).
- The optional Paper startup smoke test only runs when the PR had the `paper-smoke-test` label
  (or the Release workflow was dispatched with it). It boots a disposable Paper 26.2 server and
  checks that JoshyMC enables; it does not test gameplay.
- A local `./gradlew build` makes `jar/joshymc-1.0-SNAPSHOT-all.jar`, whose plugin version is
  `1.0-SNAPSHOT`.
- There is no schema version number in `data.db`: tables are created with `CREATE TABLE IF NOT
  EXISTS`, columns added with `ALTER TABLE ... ADD COLUMN`, and some tables get triggers. Every
  migration is forward-only (section 7).

### 1.2 Choose the commit

1. Pick the tag (`v1.0.N`). Record its commit SHA (`git rev-list -n 1 v1.0.N`).
2. List the PRs merged since the **currently deployed** tag:
   `git log --oneline v1.0.<deployed>..v1.0.N`. Every one of them goes into the RC record with a
   one-line "what it changes for Lifesteal" (many will be "Survival only, no effect").
3. **Required merged PRs**: every fix the previous session's known-issues list marks
   "fix required for next RC" (P0/P1) must be in that range. If one isn't merged yet, there is no
   candidate; wait or pick a later tag.
4. Nothing in the range may be a draft, revert-pending or "do not deploy" PR. If one is, pick a
   tag before it.

### 1.3 Required checks on that exact commit

Check out the tag in a clean clone (`git checkout v1.0.N`, `git status` clean) and run:

1. `./gradlew build` passes (the same command CI ran).
2. The Lifesteal suites from [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) section 16, plus the
   ones for the systems this beta uses. Each is `./gradlew -I scripts/<file>.init.gradle.kts <task>`:
   `lifestealCheck`, `lifestealHeartCheck`, `heartTransferMonitorCheck`,
   `competitiveEligibilityCheck`, `lifestealTeamCheck`, `lifestealTeamCombatCheck`,
   `lifestealAllianceCheck`, `lifestealTeamProgressionCheck`, `lifestealCombatLogCheck`,
   `lifestealCombatNpcCheck`, `lifestealProgressionCheck`, `lifestealBankCheck`,
   `lifestealBountyCheck`, `lifestealEconomySecurityCheck`, `lifestealQuestCheck`,
   `lifestealProfileCheck`, `lifestealRankCheck`, `lifestealSocialCheck`,
   `eventInventoryCheck`, `combatTeleportCheck`, `staffPermissionCheck`, `rankSecurityCheck`,
   `gamemodeScopeCheck`, `shutdownCheck`, `networkIdentityCheck`, `networkTransferCheck`.
3. If Ranked is part of this beta: every ranked suite in that table (`lifestealRankedCheck`,
   `lifestealRankedQueueCheck`, `lifestealRankedTestMatchCheck`, `lifestealRankedEloCheck`,
   `lifestealRankedStatsCheck`, `rankedInventoryCheck`, `rankedIntegrityCheck`,
   `rankedArenaGuardCheck`, `rankedArenaAccessCheck`, `rankedSpectateCheck`, `rankedTeamLockCheck`)
   and `rankedBetaReadiness`.
4. Write each task and PASS/FAIL in the RC record. A failing suite is a stop until someone has
   explained it in the record (for example a check that was already failing on `main` for a
   reason that doesn't touch Lifesteal, with its issue number).

A passing suite is automated evidence only. It never replaces the staging plan.

### 1.4 Artifact and version identification

1. Download `joshymc-1.0.N.jar` and `joshymc-1.0.N.jar.sha256` from the GitHub release of that tag.
   **Never** use a jar from `jar/` on a developer machine or one passed around in Discord.
2. Verify: `sha256sum -c joshymc-1.0.N.jar.sha256` must print `OK`. Record the hash.
3. On the server, after start, Paper's version command for the plugin (`version joshymc` in the
   console) must show exactly `1.0.N`. **If it shows `1.0-SNAPSHOT` or any other number, stop the
   server and put the previous jar back**: that is an unverified development build.
4. Keep exactly one JoshyMC jar in `plugins/`. Two JoshyMC jars (old + new) means Paper picks one
   and you don't know which.

### 1.5 Configuration snapshot

Config files are server files, not part of the jar. Before every deploy, with the server stopped,
copy the whole `plugins/Joshymc/` folder's `*.yml` files into the backup (1.7) and record in the
RC record:

- which files you changed by hand since the last RC, with key / old / new;
- the Lifesteal switches as they will run: `lifesteal.yml > ranked-team-wars.enabled`,
  `lifesteal.yml > ranked-team-wars.matchmaking.enabled`, `lifesteal.yml > progression.enabled`,
  `lifesteal.yml > heart-items.withdraw-eligibility.enabled`, `config.yml > auto-restart.enabled`
  and `config.yml > auto-restart.interval-hours`;
- `config.yml > gamemode-lock.server-gamemode` (must be `lifesteal`) and
  `config.yml > network.server-id`.

On startup JoshyMC backfills keys that are new in the bundled `config.yml` into the server's copy,
so `config.yml` can change by itself on the first start of a new build. After the first staging
start, diff the server's `.yml` files against the pre-deploy copies and record any added keys and
their values; that is the config production will get.

`rankedBetaReadiness` with `-PreadinessConfig=<path to the staging lifesteal.yml>` parses it with
the plugin's own parser and lists missing keys and disabled switches, without starting a server.

### 1.6 Resource pack compatibility

The resource pack is **inside the JoshyMC jar** and served by the plugin itself
(`config.yml > resource-pack.port`). On startup the plugin replaces
`plugins/Joshymc/resourcepack.zip` when the bundled pack differs and logs
`Resource pack server started on port <port> (hash: <sha1>)`.

- Record that hash from the staging log. If it differs from the deployed build's, players will
  download the pack again on join: mention it in the maintenance announcement.
- On staging, a Java client must load the pack with no error, and custom items, crate models,
  the sidebar logo and GUI icons must show. A Bedrock client must show the Geyser icons (the
  Bedrock pack is regenerated by the plugin; see [bedrock-custom-items.md](bedrock-custom-items.md)).
- The pack port must be reachable from players' clients on production exactly as before. A
  firewall change is never part of a plugin deploy.

### 1.7 Server backup

Taken **every time**, right before the deploy, with the server **stopped** (a running server's
SQLite files are mid-write; JoshyMC checkpoints and closes the database on a clean shutdown):

1. Announce, then stop the server normally (`stop`). Wait until the process has exited. Check the
   end of `logs/latest.log` for the JoshyMC shutdown lines and no `SEVERE` from the database
   shutdown.
2. Copy, keeping the folder names, into a dated backup folder outside the server directory:
   - `plugins/Joshymc/` whole (`data.db`, `playerdata.db`, any `-wal`/`-shm` files, every `.yml`,
     `resourcepack.zip`, `bedrock/`);
   - every world folder (player inventories and **ender chests** are vanilla player data inside
     the main world's `playerdata/`; JoshyMC doesn't store them);
   - the companion plugins' data that Lifesteal depends on: LuckPerms (its database or files),
     WorldGuard regions, Geyser/Floodgate config;
   - the old `joshymc-*.jar` (needed for rollback).
3. Check the copy is readable: open a **copy** of `data.db` with `sqlite3` and run
   `PRAGMA integrity_check;` (must print `ok`).
4. Record the backup path, time and size in the RC record.

### 1.8 Deployment procedure (staging first, then production)

1. Staging gets the RC first and passes sections 2–4. Production never gets a jar staging hasn't run.
2. In the planned window: announce (time, expected length, "your items and hearts are safe") at
   least once in advance and again a few minutes before.
3. Stop production normally and take the backup (1.7).
4. Remove the old JoshyMC jar from `plugins/` (keep it in the backup), copy in the verified
   `joshymc-1.0.N.jar`. Change no other plugin in the same window unless that change is in the RC
   record and was on staging too.
5. Apply only the config changes listed in the RC record.
6. Start. Watch the console until "Done". Then the post-deploy checks:
   - Paper's version command shows `1.0.N` (1.4);
   - `/jgamemode info` shows Lifesteal as the server gamemode and `lifesteal.yml` valid;
   - no `SEVERE`/stack trace from JoshyMC during startup (search `logs/latest.log`);
   - `/lifesteal progress` shows the season phase you expect;
   - one staff account joins: hearts (`/lifesteal info`), team, bank (`/bank balance`) and
     inventory as before; the resource pack loads;
   - `/bounty admin check` reports escrow consistent; `/lifesteal admin ranked restores` empty;
     `/event inventory list` shows nothing in REVIEW.
7. Open the server. Record the time and any finding in the RC record.

If any post-deploy check fails and it isn't clearly cosmetic, roll back (section 7) **before**
players join, while no new data has been written.

## 2. Staging server validation

### 2.1 Staging server

Set it up exactly like the Ranked QA Toolkit's test server
([lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) section 1): separate Paper 26.2 server, its own
`plugins/Joshymc/` and `data.db`, Lifesteal as the server gamemode, the same companion plugins and
versions as production, and a **baseline backup** after setup. Never copy production's `data.db`
onto it. The staging server runs the RC jar from 1.4, not a local build.

Shorten the season schedule there as the toolkit's section 2 describes, so grace, Ranked and the
End can be tested without waiting days. A launched season can't be moved or reset, so use a fresh
`data.db` for each schedule you want to test.

### 2.2 Testing sequence

Run in this order; each step's "pass" is the expected result already written in the linked doc.
Record PASS / FAIL / NOT RUN and evidence per step in the session log.

| # | Area | What to do | Reuse |
|---|---|---|---|
| S1 | Initial startup | Fresh staging `data.db`. Start, read the whole startup log for JoshyMC `SEVERE`/stack traces. `/jgamemode info`. Stop and start again: the second start must be just as clean (migrations that run twice are a common restart bug). | 1.8 step 6 |
| S2 | New player joining | An account that has never joined: starting hearts, tutorial, `/lifesteal info`, Starter Kit, resource pack. | [lifesteal-beta.md](lifesteal-beta.md) E1, E2 |
| S3 | Existing player reconnecting | Join, change inventory and hearts, quit, rejoin; then again after a restart. | E11 |
| S4 | Hearts | Kill, same-victim cooldown, 1-heart minimum, 20-heart maximum, withdraw/redeem, copies refused. | E7–E9; matrix HT-* for detail |
| S5 | PvP | Grace period, combat tag, refused teleports while tagged, friendly fire, combat-log NPC. | E5, E6, E10; [lifesteal-combat-npc.md](lifesteal-combat-npc.md) |
| S6 | Banking | `/bank deposit` / `/bank withdraw`, fees, refused while tagged, PvP coin stealing on a kill. | [lifesteal-bank.md](lifesteal-bank.md) |
| S7 | Bounties | Place, add to, claim by a kill, expire and refund; `/bounty admin check` consistent after each. | [lifesteal-bounties.md](lifesteal-bounties.md) |
| S8 | Teams | Create, invite/accept/decline, kick, transfer, disband, team PvP, alliances if enabled. | E3, E4; [lifesteal-teams.md](lifesteal-teams.md) |
| S9 | Quests | Daily and Weekly assigned, progress, claim, reward delivered once, reroll. | [lifesteal-quests.md](lifesteal-quests.md) |
| S10 | Achievements | Progress counts, claim delivers once, `/achievements admin status`. | [lifesteal-profiles.md](lifesteal-profiles.md) |
| S11 | Ranked queue | Only if Ranked is in this beta. Closed state first (players refused), then public queue. | toolkit section 18 steps 2 and 5; matrix ST-12, MM-* |
| S12 | Ranked matches | TEST, then PUBLIC at 2v2 → 5v5, Elo result. | toolkit section 18 steps 2, 5, 6; matrix MM-*, EL-* |
| S13 | Inventory restoration | Ranked snapshot restores and event world inventories. | matrix IR-*; [event-inventory.md](event-inventory.md) live checklist |
| S14 | Spawn and dimension restrictions | Spawn protections, End locked before +168h and open after, portals, `/lobby` and `/survival` if behind the proxy. | E12, E15; [lifesteal-progression.md](lifesteal-progression.md) |
| S15 | Server restart recovery | Section 3 in full (normal restarts), then 3.3 (crash tests). | matrix DR-* |

For ranked detail (arenas, team sizes, evidence, resets), follow the toolkit; this guide only
decides *when* ranked testing happens in the sequence.

## 3. Restart safety

### 3.1 Restart verification checklist

Before the restart, record a baseline for at least three test accounts (one online, one offline,
one mid-activity) and for the server; after it, compare. "Looks the same" is not evidence;
the before/after pair is.

| Item | Record before | Check after (pass = identical, unless the "after" column says otherwise) |
|---|---|---|
| Player inventories | screenshot of the full inventory (armor, off hand), or `/invsee <player>` for offline/other players | slot by slot identical |
| Ender chests | `/enderchest [player]` screenshot | identical |
| Heart balances | `/lifesteal hearts get <player>` | identical; health bar matches |
| Heart cooldowns | time of the last kill per killer → victim pair (30 min) | a repeat kill inside the window still moves nothing (the cooldown is stored, it survives restarts) |
| Bank balances | `/bank balance` (player) or `/bank admin view <player>` | identical, wallet and bank |
| Bounty escrow | `/bounty admin check` total; `/bounty admin pending` | same total, still "consistent"; pending payouts/refunds get paid by the sweep, never twice |
| Achievement progress | `/achievements` screenshot of a partly done tier; `/achievements admin status` counts | identical; nothing CLAIMING left stuck after a minute |
| Quest progress | `/quests` page | equal or higher (progress is saved every 30s and on stop; a normal stop loses nothing) |
| Team membership | `/team admin info <team>` | same members, leader, alliances |
| Ranked Elo | `/ranked stats <player>`, `/ranked teamstats <team>` | identical (only if ranked ran) |
| Ranked match history | `/ranked history` | identical; no match still open after the restart |
| Pending rewards | `/purchase list open`; bounty pending payouts; quest/achievement rewards not yet received | delivered once after the restart or still pending, never duplicated, never gone |
| Pending inventory restoration | `/lifesteal admin ranked restores`; `/event inventory list` | a normal stop restores everyone; both empty after the restart (REVIEW only after a crash, 3.3) |
| Combat NPC cleanup | a combat-log NPC standing at the stop | respawns for its remaining time, or settles as survived if expired; no duplicate NPC, owner gets their items once |
| Database integrity | — | after the stop, on a **copy** of `data.db`: `PRAGMA integrity_check;` prints `ok`; no JoshyMC `SEVERE` in either log |

Useful read-only SQL on a **copy** of a stopped server's `data.db` (`sqlite3 -readonly copy.db`):

```sql
PRAGMA integrity_check;
SELECT status, COUNT(*) FROM lifesteal_ranked_snapshots GROUP BY status;
SELECT state, COUNT(*) FROM lifesteal_combat_npcs GROUP BY state;
SELECT state, COUNT(*) FROM purchase_deliveries GROUP BY state;
```

After a clean stop no snapshot should be PENDING/RESTORING/REVIEW for a finished match, and every
purchase delivery should be COMPLETED, PENDING (waiting for the player) or a FAILED/INTERRUPTED row
staff already know about.

### 3.2 Normal restarts on staging

Run the checklist for each of these, on staging:

1. A plain `stop` with nobody online.
2. `stop` with testers online and doing ordinary things (inventory open, mid-trade, at the bank).
3. `stop` with one player combat-tagged (expect no counted combat log, no NPC; see
   [lifesteal-combat.md](lifesteal-combat.md)) and one combat-log NPC already standing.
4. `stop` during a ranked TEST match (expect: cancelled, everyone restored, no Elo; matrix DR-*).
5. `stop` while a player is in the event world (expect their own inventory back on the next join).
6. The scheduled restart path (`/jrestart`, the same countdown `auto-restart` uses), to see the
   warning and that it ends in a clean stop.

### 3.3 Controlled hard-crash tests (disposable staging data only)

**Only on staging, only with a `data.db` you're willing to throw away, never on production.** A
crash test on production means real players can lose the last seconds of play, and the recovery
paths (REVIEW rows, INTERRUPTED deliveries) need staff work.

1. Restore the staging baseline backup (toolkit section 13), so the test starts from known data.
   Take an extra copy of `plugins/Joshymc/` as "before crash".
2. Set up the state to test: a ranked match in each stage (toolkit DR-11..DR-16), a standing
   combat-log NPC, a player in the event world, a bounty about to pay, a reward delivery in progress.
3. Crash the **staging** Java process: `kill -9 <pid>` (Linux), or end the process in the host
   panel. Never `kill -9` a production server.
4. Start it again and record what each recovery path did:
   - ranked: startup recovery cancels/completes open matches; snapshots left RESTORING from the
     old boot go to REVIEW (`/lifesteal admin ranked restores`), staff settle them with
     `/lifesteal admin ranked restore <player> apply confirm` or `resolve`
     ([lifesteal-ranked-beta.md](lifesteal-ranked-beta.md) section L);
   - event world: anything ambiguous is REVIEW (`/event inventory list`), never applied
     automatically ([event-inventory.md](event-inventory.md));
   - combat NPC: respawned from the last save for its remaining time ([lifesteal-combat-npc.md](lifesteal-combat-npc.md));
   - purchase/event/quest deliveries: a row left PROCESSING becomes INTERRUPTED and is **never**
     auto-resent (`/purchase list open`, then `/purchase retry` only after checking the player
     didn't get it);
   - crate rewards: RESERVED rows become REVIEW (`/crate rewards review`).
5. Compare every player against the pre-crash record. Loss of the last few seconds of vanilla
   activity (blocks mined after the last world save) is expected from a crash; **an item that
   exists twice, a heart that moved one way only, or an inventory gone for good is a P0**.
6. Throw the crashed staging data away (restore the baseline) before the next test.

## 4. Stability soak test

### 4.1 Session design (proposed targets, not proof the server handles them)

- 2–4 hours of continuous uptime on the RC, on staging first; a closed-beta session on production
  only after a clean staging soak.
- 5–10 testers to start (at least one Bedrock player); add more only after a clean session.
- `config.yml > auto-restart.interval-hours` is 12 by default, so a 4 hour soak doesn't hit the
  scheduled restart; check `config.yml > auto-restart.enabled` and the interval in the RC record so
  a restart in the middle isn't a surprise.
- A script of activity for the session lead to call out, roughly every 15 minutes:
  - ordinary PvP fights (several pairs, some to the death, some ending in a combat log);
  - team activity (invites, a kick, a transfer, an alliance request if enabled);
  - ranked matches (TEST, then PUBLIC if this beta has Ranked), at least one per size used;
  - frequent joins and quits (each tester relogs at least every 30 minutes, some mid-combat);
  - inventory churn (crafting, chests, shulker boxes, item frames, dropping and picking up);
  - bank deposits/withdrawals, bounties placed and claimed;
  - controlled world transitions (Nether, End once unlocked, event world, `/lobby` and back if
    behind the proxy).

### 4.2 What to record

Use the soak log in [lifesteal-beta-templates.md](lifesteal-beta-templates.md) section 4. Every
15 minutes:

| Measurement | How |
|---|---|
| TPS | `/tps` (1m, 5m, 15m) |
| MSPT | Paper's `mspt` console command; a profiler plugin such as spark if installed |
| Memory | Paper's `/tps` doesn't show it: use the host panel, or a profiler plugin; record used / max heap |
| Players online | count at the time |

Continuously (session lead + one staff member watching the console):

- errors and exceptions (every JoshyMC stack trace: time, first line, what players were doing);
- database failures (`SEVERE` lines about failed writes, "NOT saved", rolled-back transactions,
  `/bank admin failed`, `/bounty admin failures`, `/lifesteal combat failures`);
- task failures ("Task ... generated an exception" in the log);
- player disconnects (who, when, the kick reason; timeouts vs. kicks);
- inventory inconsistencies (any tester who says "I lost / gained an item");
- heart inconsistencies (each heart change has a matching opposite change; `/lifesteal hearts recent`);
- ranked match failures (stuck states, `/lifesteal admin ranked list` long after a match, restores
  left in `/lifesteal admin ranked restores`).

Write down what was measured, when and how. If something wasn't measured, the field says
"not measured", never a guess. A soak test is clean only when the log shows no P0/P1 finding and
staff agree the TPS/MSPT they recorded are acceptable.

## 5. Bug severity

| Level | Definition | Examples | Response |
|---|---|---|---|
| **P0** | Duplication, severe data corruption, unrestricted exploits | items or hearts created from nothing; a Heart item redeemed twice; coins/escrow duplicated; database unreadable; a player-usable command that bypasses staff protection | stop testing the affected system; disable it or roll back (section 7); emergency fix allowed |
| **P1** | Inventory loss, broken persistence, serious ranked failure, frequent crashes | an inventory not restored; hearts/bank/team not kept across a restart; a ranked match stuck or rated wrongly; repeated crashes | blocks the next RC; fix goes in the next planned release |
| **P2** | Broken feature with a manageable workaround | a menu button that doesn't work but the command does; a quest that doesn't count one activity | collected, fixed in a grouped RC |
| **P3** | Cosmetic or minor usability | wording, colours, GUI layout, a missing tab completion | collected, fixed when convenient; never blocks the beta |

For ranked findings, [lifesteal-ranked-release-gate.md](lifesteal-ranked-release-gate.md)
section 1 also applies (Elo created or destroyed is P0, a broken competitive rule is P1 there).
When the two scales disagree, use the higher severity.

Every report uses the template in [lifesteal-beta-templates.md](lifesteal-beta-templates.md)
section 2 (ranked bugs may use [lifesteal-ranked-bug-report.md](lifesteal-ranked-bug-report.md)
instead). Security-relevant P0s (a working exploit or dupe) are reported **privately** to the
Owner/admins first, never in a public channel or a public GitHub issue, until fixed.

## 6. Deployment cadence

- **Don't deploy every merged PR.** Auto-releases keep happening on `main`; production stays on
  the last approved RC until the next window.
- **Collect noncritical bugs** (P2/P3) in the known-issues list during the session; don't restart
  for them.
- **Group fixes into a release candidate**: once the P1s from a session have merged fixes, pick
  the tag that contains all of them (section 1.2).
- **Validate the candidate on staging** (sections 2–4, at least S1–S3, the areas the fixes touch,
  and the restart checklist).
- **Deploy during a planned, announced maintenance window**, one deploy per window.
- **Emergency restarts are only for**: an active P0 (dupe, exploit, data corruption) or a security
  issue, or a server that can't keep running. Even then: stop cleanly, back up, deploy a verified
  release jar (never a local build), and record it in the RC record afterwards.
- A config-only change that a feature reads on `/joshymc reload` still goes through staging if it
  touches Lifesteal gameplay; turning a feature *off* to contain a bug can be done immediately.

**Disabling instead of rolling back.** Nonessential systems may be off for the beta. The first
response to a broken system is usually its own switch, not a rollback: for example
`lifesteal.yml > ranked-team-wars.enabled` (closes `/ranked` to players),
`lifesteal.yml > ranked-team-wars.matchmaking.enabled` (no new queues), and the per-system
`enabled` keys in `lifesteal.yml` and the other Lifesteal config files (see each system's doc).
Turning a system off never deletes its stored data.

## 7. Rollback

Two ways back, in order of preference. **Never assume the old jar understands the new database.**

### 7.1 Decide which rollback is possible

Find what the bad release changed in the database:

```bash
git diff v1.0.<good>..v1.0.<bad> -- src/main/kotlin | grep -E '^\+.*(CREATE TABLE|ALTER TABLE|CREATE TRIGGER|CREATE (UNIQUE )?INDEX|DROP |UPDATE .* SET|DELETE FROM)'
```

- **No schema change** in the range: the old jar can usually run on the current `data.db`
  (7.2). Still test it on staging with a copy of the current data first if there is any time.
- **New tables/columns only**: the old jar ignores them, so it usually runs; data written into
  those new tables/columns since the deploy is ignored until you go forward again. Test on staging
  with a copy first.
- **New triggers, changed constraints, rewritten rows, data migrations** (status values renamed,
  rows moved to a new table, a trigger the old code would violate): the old jar **may fail** or
  write data the new schema refuses. Don't run the old jar on that database; prefer a fix-forward
  (7.4), or restore the backup (7.3) knowing what is lost.

### 7.2 Jar rollback, keeping the current data (preferred)

1. Announce, stop production normally (players' data is saved by the clean stop).
2. Take a backup of the **current** state (1.7): this is the newest player data, keep it.
3. Swap the jar back to the last good release jar (checksum-verified, 1.4). Put back the old RC's
   config files only for keys the bad release added *and* the old jar would misread; otherwise
   leave config alone.
4. Start, run the post-deploy checks (1.8 step 6) against the old version number.
5. Record it and open a P0/P1 issue for whatever forced the rollback.

Player data written while the bad build ran stays (unless 7.1 said it can't be read).

### 7.3 Full restore from the pre-deploy backup (last resort)

Restoring the backup from 1.7 throws away **everything** players did since that backup:
inventories, hearts, bank, bounties, team changes, ranked results, purchases delivered. Only do it
when the current data is corrupt or the bad release wrote data that can't be trusted.

1. Stop the server. Keep a copy of the current (bad) state for investigation, privately.
2. Restore together, from the same backup: `plugins/Joshymc/` (all `.db` files with their
   `-wal`/`-shm`, all `.yml`), the world folders (player inventories/ender chests), LuckPerms and
   WorldGuard data. Never mix a backup's `data.db` with newer world player files: hearts, bank and
   ranked snapshots in `data.db` must match the inventories in the world.
3. Put back the old jar, start, run the post-deploy checks.
4. Store purchases made since the backup are in Tebex's own records; compare them with
   `/purchase list` and re-queue missing ones with the documented `/purchase fulfill` command
   ([store-fulfillment.md](store-fulfillment.md)). Tell affected players what was rolled back.

### 7.4 Fix forward

When neither rollback is clean, keep the bad build stopped (or the broken system switched off,
section 6) and ship a fix as a new release, validated on staging with a copy of the current
production data. This keeps all player data.

## 8. Beta entry criteria and go/no-go

### 8.1 Entry criteria (a closed-beta candidate must have all of these)

- No unresolved known P0 defects.
- No known repeatable item duplication.
- No known irreversible inventory loss.
- No broken core heart persistence.
- No known serious ranked settlement exploit, if Ranked is enabled (the ranked release gate
  decides `ranked-team-wars.enabled` separately).
- No recurring startup failure (S1 clean on two consecutive starts).
- Acceptable **measured** stability during staging (a clean soak log, section 4).
- Backups taken and the recovery instructions (section 7) read by the staff on shift.
- A current known-issues list ([lifesteal-beta-templates.md](lifesteal-beta-templates.md) section 3).

Nonessential features may be off for the beta; list them in the known-issues file. Cosmetic and
P3 issues don't block it.

### 8.2 Go/no-go checklist

Filled in by the Owner (or whoever they name) before each beta deploy. Every line needs a link
or a note as evidence; an empty evidence field is a NO.

| # | Check | Yes/No | Evidence |
|---|---|---|---|
| GO-1 | RC record complete: tag, commit, merged PR list, jar SHA-256 verified (1.2–1.4) | ☐ | |
| GO-2 | `./gradlew build` and the required suites pass on that exact commit (1.3) | ☐ | |
| GO-3 | Config snapshot recorded, including keys the new build backfilled (1.5) | ☐ | |
| GO-4 | Resource pack hash recorded, Java and Bedrock clients checked on staging (1.6) | ☐ | |
| GO-5 | Staging sequence S1–S15 run on this RC (or the areas the fixes touch, with the rest from the previous RC) with no open P0/P1 | ☐ | |
| GO-6 | Restart checklist passed for the normal restarts (3.2) | ☐ | |
| GO-7 | Crash tests (3.3) run on staging for this RC, or for an earlier RC with no recovery-path change since | ☐ | |
| GO-8 | Soak log clean, with measured TPS/MSPT/memory staff accept (section 4) | ☐ | |
| GO-9 | No unresolved P0; every open P1 either fixed or recorded as not blocking, with the reason | ☐ | |
| GO-10 | Ranked: either `ranked-team-wars.enabled: false`, or the ranked release gate signed | ☐ | |
| GO-11 | Fresh production backup will be taken in the window; rollback path decided (7.1) | ☐ | |
| GO-12 | Known-issues list current and shared with testers; maintenance window announced | ☐ | |

**Decision:** GO / NO-GO, by <name>, at <date time zone>. A NO-GO lists what's missing.
