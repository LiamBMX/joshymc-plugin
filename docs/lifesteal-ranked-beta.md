# Ranked Team Wars: completion review and beta checklist (issue #1371)

Lifesteal only. Issue #1371 asked for every Ranked Team Wars piece to be checked, the gaps closed,
and a beta checklist written. Everything below comes from reading the merged code (#1361, #1363,
#1365, #1367, #1369, all on `main`) and running the automated checks.
**No live multiplayer match has been played.** Ranked stays closed to players
(`lifesteal.yml > ranked-team-wars.enabled: false`) until the manual checklist in section E passes.

## A. Implementation status

| Component | Status | Where / notes |
|---|---|---|
| Ranked arenas (configurable, team spawns, 2v2-5v5, one match per arena, availability, unsafe spawns, boundaries, release) | COMPLETE | `RankedArena.kt`, `RankedStore`, `LifestealRankedManager`. A partial UNIQUE index stops two open matches in one arena. Availability is computed from the holding match, never stored, so a reservation can't leak. `SafeSpots` checks every spawn after the chunk loads. Players are pushed back at the boundary. Admin commands only, no arena GUI (as the issue asks). |
| Match lifecycle (CREATED → … → COMPLETED / CANCELLED) | COMPLETE | Each transition is a conditional UPDATE, so it happens once. A preparation timeout of 20s cancels the match. |
| Participant / team validation | COMPLETE | `RankedMatchRules.validate`: team membership, online, Lifesteal world, hearts loaded, not combat-tagged, not in another match or waiting for a restore, not transferring, not dead, Survival game mode, not in Mod Mode, frozen, trading or the event world. |
| Inventory snapshot + restore | COMPLETE | All 41 slots via `serializeAsBytes` (custom items, enchants, durability, PDC kept), written in the same transaction as the match, before any teleport. Restoring *sets* the inventory (idempotent). It runs on win, loss, cancel, death (at respawn), disconnect (at rejoin), shutdown (synchronously) and after a crash (at join). |
| Ranked death handling | COMPLETE | No drops, no XP orbs. The player is out of this match only, and hearts go through the normal heart service. |
| Match completion / simultaneous deaths | COMPLETE | Decided on the next tick. Both sides out at once = cancelled, no winner. |
| Heart integration (1 min, 20 max, cooldown, dedupe) | COMPLETE | `LifestealHeartManager.onDeath` → `HeartStore`, the same as every Lifesteal kill. Restoring never touches hearts. |
| Teammate rules inside a match | **FIXED in #1371** | See B. |
| Teleport / transfer / command lock | COMPLETE | `PlayerTeleportEvent` refuses every way out of the arena. `CombatTeleportGuard`, `TransferBlocker.RANKED_MATCH`, and only `/msg`-style commands work. |
| Spectators | COMPLETE (staff only) | `/lifesteal admin ranked spectate`. Spectators take and deal no damage and can't pick up or change anything. There is no public spectator mode. |
| Public queues 2v2-5v5 | COMPLETE | `RankedQueueBook` (memory only, FIFO): leader picks the roster, everyone picked agrees, everyone accepts the found match, and it goes through the same `startMatch` path. Disconnect/decline ends the entry. A restart cancels every entry. |
| Direct team challenges | COMPLETE | Same consent flow and the same lifecycle. **Unrated by default** (`elo.rate-challenges: false`). |
| Individual Player Elo / Team Elo | COMPLETE | `EloRating` + `RatingStore.finalize`: one transaction, append-only history, `elo:<match>` result row so a match is rated once. TEST and cancelled matches are never rated. Player Elo is keyed by UUID and Team Elo by the persistent team id. |
| Ranked GUI | COMPLETE | `/ranked` menu (`RankedQueueMenu`, `RankedMenuLayout`): 2v2-5v5 buttons, status, accept/decline, leave, rating summary, stats, history, top players/teams, Close. It uses `GuiManager`, so items can't be taken out, and every button re-runs the command's checks. Plain vanilla icons only, which also work on Bedrock. Divisions exist from #1367 but are labels only. |
| Commands | COMPLETE | `/ranked [queue <size> …|accept|decline|leave|status|challenge …|stats|teamstats|top|history|divisions|season]`, LIFESTEAL scope, and only registered where Lifesteal is hosted. |
| Leaderboards | COMPLETE | `/ranked top` (players/teams), per season, ACTIVE teams only, paged, loaded async. |
| Day 3 unlock | COMPLETE | `canEnterRankedQueue()` uses the stored season launch time (never a missing timestamp). Player access also needs `ranked-team-wars.enabled: true`. |
| Crash / disconnect recovery | COMPLETE | Startup recovery records FINISHING as COMPLETED and everything else as CANCELLED, and restores each player on join. A rating that failed is retried by the 5-minute sweep. |
| Survival isolation | COMPLETE | No tables, listener or `/ranked` command on a server that doesn't host Lifesteal. |
| Ranked forfeiture policy | BLOCKED (blueprint decision) | Disconnecting in a fight = out of that match only. No heart or Elo penalty beyond losing the match with your side. |

## B. Fixes in issue #1371

**Team changes under a running match.** Ranked hits never combat-tag, so the "no team changes while
combat-tagged" rule (#1348) never applied to ranked players. Meanwhile, friendly fire and teammate
heart transfers came from *live* team membership. A team leader standing outside the arena could:

- `/team kick` a participant mid-match, or
- `/team disband` the team mid-match (an admin could do it with `/team admin disband`).

Either one turned match teammates into "not teammates". They could then hurt each other and **steal
hearts from each other** inside the arena.

Fixes (`lifesteal/teams/`, `lifesteal/ranked/`):

1. `TeamCombatRules.rankedRefusal`: accept/leave/kick/disband are refused while any involved player
   is held by a ranked match. This lives inside the existing `combatRefusal`, so all four actions get
   it. `/team admin disband` is refused too, with the `/lifesteal admin ranked cancel <match>` to run
   first. That way staff recovery goes through the match system, which restores everyone.
2. `LifestealTeamManager.combatRelation` asks `LifestealRankedManager.matchSides` first. When both
   players are on one open match's roster, their **roster sides** decide: same side = teammates,
   opposite = not. Defeated players keep their side, so a kill is still judged correctly at MONITOR,
   after the death took the victim out. This also covers a team change that was already committing
   on the team worker thread as the match started. Outside a match nothing changes.

Nothing else was rewritten: every other component above was already complete.

## C. Testing

Run for this change, all passing: `./gradlew build`, `lifestealTeamCombatCheck` (new
`rankedTeamChanges` section: refusals for every change type, roster sides for a mixed Java/Bedrock
2v2, defeated players keep their side, heart checks for roster teammates/enemies, wiring),
`lifestealTeamCheck`, `lifestealRankedCheck`, `lifestealRankedQueueCheck`, `lifestealRankedEloCheck`,
`lifestealRankedStatsCheck`, `lifestealHeartCheck`, `lifestealCheck`, `lifestealProgressionCheck`,
`lifestealCombatLogCheck`, `gamemodeScopeCheck`, `combatTeleportCheck`, `shutdownCheck`.

Between them, the existing suites already cover these on a real SQLite database:

- 2v2-5v5 sizes, participant/team validation, queue creation/cancellation/confirmation;
- arena reservation and UNIQUE indexes;
- snapshot rows and idempotent restore marks;
- crash recovery (reopened databases);
- winner/no-winner decisions;
- Player and Team Elo, duplicate rating prevention, leaderboard ordering;
- heart min/max/cooldown;
- Day 3 unlock, Floodgate-style UUIDs and Survival isolation.

**Not verified (needs a live Paper server):**

- real item serialization round trips (custom enchants, Heart serials, shulkers);
- teleports and chunk loading;
- actual deaths, respawns and simultaneous crystal kills;
- Bedrock clients in the menu;
- `kill -9` recovery.

## D. Remaining blockers and risks

- **Critical: none known in code.** The release blocker is that no live match has been played yet.
- **High: a failed RESTORED mark.** If the database write that marks a snapshot RESTORED fails, the
  snapshot is applied again on the next join. Anything the player moved out of their inventory in
  between (into a chest, for example) would then exist twice. This only happens on a database write
  failure, which is logged severe. Check `/lifesteal admin ranked info` for a match whose restoration
  isn't COMPLETE.
- **High: other plugins' items.** Items another plugin (not JoshyMC's deliveries) puts straight into
  a held player's inventory mid-match are overwritten by the restore.
- **Open decisions (blueprint):**
  - ranked forfeiture penalties;
  - killer at 20 hearts gains nothing;
  - challenges unrated.
- **Matchmaking is FIFO** (no skill matching), by design for the beta.

## E. Ranked beta checklist

Needs at least four Lifesteal teams' worth of players (10 for a 5v5), including one Bedrock
player and one mixed Java + Bedrock team. Run it on the Lifesteal server after the season passed +72h
(or with a short test schedule).

1. Build one arena per size you'll test: `/lifesteal admin arena create`, `setcorner 1|2`,
   `setspawn a|b` (as many as the team size), `setspectator` (outside), `sizes`, `enable`.
   `arena info` shows READY.
2. With `enabled: false`, a player's `/ranked` is refused and hidden. Staff can still open it.
3. Run `/lifesteal admin ranked testmatch A B 2` once per arena. Players move in, the countdown
   runs with no damage, then the fight starts. The match is recorded TEST and not rated.
4. Set `ranked-team-wars.enabled: true` and `/joshymc reload`. For each of 2v2, 3v3, 4v4 and 5v5:
   - the leader runs `/ranked queue <n> <names>` (or uses the GUI);
   - every picked player clicks Agree;
   - a second team does the same;
   - both sides Accept, and the match starts.
5. Before a match, give players a custom-enchanted crate weapon, a damaged netherite piece, a Heart
   item, a named shulker and potions. After a win, a loss, a death, `/lifesteal admin ranked cancel`,
   and a disconnect + rejoin, inventories are identical to before. Nothing is duplicated and nothing
   picked up in the arena is kept.
6. A kill moves one heart (`/lifesteal hearts get`). A victim at 1 heart keeps it. A killer at 20
   gains none. The same pair again within 30 minutes moves nothing. Teammates can't hurt each other.
7. **#1371:** during a match, the leader (outside the arena) tries `/team kick <participant>` and
   `/team disband`. Staff try `/team admin disband`. All are refused. Teammates in the arena still
   can't hurt each other.
8. In a match, try `/spawn`, `/home`, `/tpa`, `/rtp`, `/lobby`, `/survival`, an ender pearl over the
   boundary, walking out, dropping items, `/ec` and chests. All are refused.
9. Kill the last players of both sides at once (one crystal). The match is cancelled with no winner
   and no Elo.
10. After a PUBLIC match, `/ranked stats`, `/ranked teamstats` and `/ranked top` show the new Player
    and Team Elo, once. A CHALLENGE match is unrated by default.
11. `/stop` mid-match: everyone online is restored and moved out. `kill -9` mid-match: after the
    restart the match is CANCELLED, each player is restored on join, and no Elo is given.
12. Disconnect during the countdown: the match is cancelled and the leaver is restored on rejoin.
    Disconnect while queued: the entry ends and no stale entry remains (`/lifesteal admin ranked queue`).
13. The Bedrock player opens `/ranked` and every button works (menus are plain chests).
14. On the Survival server, `/ranked` doesn't exist and Survival teams/PvP are unchanged.

## F. Integration and beta-readiness audit (issue #1377)

Issue #1377 asked for one more pass over Lifesteal and Ranked Team Wars together. As before, this
comes from reading the code on `main` and running the automated suites. **No live match was played.**

### Systems audited

| Area | Result | Where it is verified |
|---|---|---|
| Heart stealing / transfer / withdraw / redeem | OK | `HeartRules`, `HeartStore`, `lifestealHeartCheck` (982 checks) |
| 1-heart floor (never lose the last heart, a 1-heart kill gives and drops nothing, withdraw refused at 1) | OK | `HeartRules.decide` returns `VictimAtMinimum` before anything else that moves hearts. `HeartRules.withdraw` returns `AtMinimum`. `Limits` refuses a minimum below 1. Nothing drops a Heart item on death. |
| Teams (create/manage/permissions, max 5, combat rules) | OK | `lifestealTeamCheck`, `lifestealTeamCombatCheck` |
| Survival isolation | OK | Lifesteal tables, listeners and `/ranked` only exist where Lifesteal is hosted (`lifestealCheck`, `gamemodeScopeCheck`) |
| Queue entry / cancel / confirmation, 2v2-5v5 | OK, one hardening (below) | `lifestealRankedQueueCheck` |
| Match lifecycle, arenas (allocation, cleanup, reuse) | OK | `lifestealRankedCheck` |
| Real Lifesteal gear, snapshot + restore after matches and deaths | OK, one dupe fixed (below) | `lifestealRankedCheck` |
| Hearts at risk in ranked | OK | Ranked kills go through the normal heart service at MONITOR. Ranked unlocks at +72h, after the 24h grace, so the grace window never blocks a ranked kill. |
| Player and Team Elo, duplicate rating | OK | `EloRating`, `RatingRules`, `RatingStore.finalize` (`elo:<match>` row), `lifestealRankedEloCheck` |
| Disconnects, restarts, crashes | OK (code), needs a live test | Startup recovery, pending snapshots restored on join, queues memory-only and cleared on restart |
| Ranked GUIs, commands, closed by default | OK | `RankedQueueMenu`, `RankedStatsMenu`, `ranked-team-wars.enabled: false` |

### Fixed in #1377

1. **High: arrows and tridents duplicated through the snapshot.** The restore gives a participant
   back every arrow and trident they owned before the match. The projectile they fired was still a
   real item in the world, though:
   - an arrow or trident shot over the arena boundary could be picked up by a friend standing
     outside. Only players held by the match had pickups blocked, and `PlayerPickupArrowEvent`
     isn't an `EntityPickupItemEvent` anyway;
   - a Loyalty trident thrown just before the match ended flew back into the already-restored
     inventory.

   Either way, that was a second copy. Now every arrow/trident a held participant fires is tagged
   with its match id (PDC `joshymc:ranked_match_projectile`). The Bukkit-free
   `RankedMatchRules.projectilePickup` decides who touches it:
   - players of that match may pick it up;
   - anyone else is refused while the match runs;
   - once the match is over, the projectile is removed on contact.
2. **Medium: a stuck queue after an unexpected error.** If `createQueuedMatch` threw, both entries
   stayed PREPARING forever. The tick, quits and `/lifesteal admin ranked cancelqueue` all skip
   PREPARING, so neither team could queue again until a restart. The queue now cancels any
   half-created match (which restores its players) and releases both entries.

Regression checks: `lifestealRankedCheck` (#1377 projectile rule and wiring) and
`lifestealRankedQueueCheck` (#1377 start guard).

### Remaining risks, by severity

- **High (process): no live match has been played.** Sections C and E still apply.
- **Medium (open decision): dodging a loss.** A team can avoid an Elo loss two ways, both by
  design until the blueprint settles forfeits:
  - disconnecting during the countdown cancels the match unrated;
  - hiding until `max-match-minutes` runs out cancels it unrated too.
- **Medium (unverified): bundles.** Emptying a bundle in a match goes through Paper's drop path.
  The drop listener refuses it if Paper fires `PlayerDropItemEvent` there. This needs checking on
  the live server (checklist item 5 with a bundle).
- **Low:** projectiles shot out of the arena are left in the world until someone touches them or
  they despawn. They can't be picked up.
- The high-severity items in section D (a failed RESTORED mark, other plugins inserting items) are
  unchanged.

### Readiness

The core Lifesteal loop is ready for a closed beta (section E of
[lifesteal-beta.md](lifesteal-beta.md)). Ranked Team Wars are code-complete with the fixes above.
They should stay `enabled: false` until checklist E here passes on the Lifesteal server. Add these
steps to item 5:
- shoot an arrow and throw a trident out of the arena to a player outside (they can't pick it up);
- throw a Loyalty trident as the match ends (no second trident);
- empty a bundle in the arena.
