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
| Ranked forfeiture policy | **IMPLEMENTED in #1465** (needs a live test) | Disconnecting in a fight = a forfeit: out of the match, the side loses if nobody is left, and one heart to the opponent who hit the leaver last, through the normal heart rules. See section G. The recipient rule is still an Owner decision to confirm. |

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
  - ranked forfeiture: implemented in #1465 (section G); the Owner still has to confirm who gets
    the heart (`last-opponent`) or switch it off (`heart-loss: none`);
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
3. Run `/lifesteal admin ranked testmatch A B 2` once per arena, confirm it, and have every player
   accept (section J). Players move in, the countdown
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

## G. Disconnect forfeits (issue #1465)

Audit #1453 found that a player about to lose a ranked fight could quit to keep their hearts.
Ranked hits never combat-tag, so no Combat NPC stood in, and a disconnect only took the player out
of the match. Issue #1465 closes this without rebuilding anything: the match, the snapshot and the
heart service are the same ones, with one disconnect policy on top.

### Policy

The server can't tell a rage-quit from a crash or a pulled cable, so it never guesses intent. Every
disconnect of the same kind is treated the same way. The rule is the Bukkit-free
`RankedMatchRules.disconnect`, and the kind comes from the existing `CombatLogRules.classify`
(quit reason + kick cause, the same classification as ordinary Lifesteal combat logs):

| When | What happens | Hearts |
|---|---|---|
| Before the fight (PREPARING / COUNTDOWN) | Match cancelled, unrated. Snapshot restored on rejoin. | never |
| In the fight, counted disconnect (quit, alt-F4, crash, player-caused kick, timeout*) | **Forfeit**: out of the match (`DISCONNECT`), can't come back. Their side loses if nobody is left. | one heart to the last opposing hitter (below) |
| In the fight, staff kick / ban / plugin kick / server error | Out of the match (`DISCONNECT_EXEMPT`). | never |
| Already knocked out, or the match is FINISHING / over | Nothing. | never |
| Server shutdown or restart (any state) | Nothing is decided at the quit. Shutdown cancels the match and restores everyone. | never |
| Staff `/lifesteal admin ranked cancel`, time limit | Cancelled, no winner (unchanged). | never |

\* Timeouts follow `combat-logging.count-timeouts` (default true), the same switch as ordinary
combat logs. With it off, a timeout is handled like a server error.

Every disconnect is written to the match's event log with the state, kind and action
(`/lifesteal admin ranked info <match>`).

### Elo

The forfeit is an ordinary "out", so the existing rule decides the match: a side with nobody left
loses. The result is recorded once by the conditional `finish`/`complete` and rated once by
`RatingStore.finalize` (`elo:<match>`). There is no new formula and no separate forfeit rating. A
disconnect alone never ends the match early: the leaver's teammates keep fighting.

**Same-tick tie-break.** Before, if the last players of both sides went out in the same tick, the
match was cancelled. A loser could quit in the tick their opponent's last player fell and turn a
loss into an unrated cancel. Now, if one side lost players that tick only through counted
disconnects and the other side lost someone another way, the disconnecting side forfeits and the
other side wins. Deaths on both sides, or forfeits on both sides, are still a cancel.

### Hearts (`ranked-team-wars.disconnect-forfeit.heart-loss`)

The setting is `last-opponent` (default) or `none` (match forfeit only). With `last-opponent`:

- **Recipient**: the opponent whose hit on the leaver landed most recently, no older than one
  combat-tag duration. That is `combat.tag-duration-seconds`, the same window as the #1461
  combat-tag credit. Only hits the match itself recorded count: MONITOR, not cancelled, between
  opposite roster sides of an ACTIVE match. The recipient must still be fighting in that match, on
  the other side, online and alive. If the latest hitter isn't eligible, the one before is tried.
  **Nobody hit them = no heart moves.** Never a teammate, never a random opponent.
- **Transfer**: `LifestealHeartManager.onRankedForfeit` makes the same `HeartStore.transferPvp`
  call as a kill, so every rule applies:
  - the 1-heart minimum: a leaver at 1 keeps it and nobody gains;
  - the 20-heart maximum: an opponent at 20 gains nothing;
  - the same-victim cooldown;
  - the team and ally checks (roster sides first, #1371);
  - the progression window.

  Hearts are only moved, never created or destroyed.
- **Once**: the dedupe key `rankedforfeit:<match>:<player>` is UNIQUE. A death and a forfeit can't
  both happen: a death takes the player out of the match first, so the quit that follows finds
  nothing to decide. Disconnecting after lethal damage is a death, handled by the normal kill path.
- **Audit**: the usual `PVP_LOSS`/`PVP_GAIN` rows, with the note
  `ranked disconnect forfeit, match <id> (<kind>)`.
- **No Heart item, no Team XP**: it isn't a kill.

**Owner decision still open.** The blueprint never said who gets a forfeited heart. The rule here
("the last opponent who hit them, within the combat-tag window") is the most defensible one the code
already has: it is the #1461 rule for environmental deaths. It is still new for disconnects. Until
the Owner confirms it, `heart-loss: none` turns the heart part off and keeps the match forfeit.

### Inventory, reconnects and restarts

Nothing changed here:

- The snapshot taken at the start stays PENDING while the leaver is offline, and it survives
  restarts.
- On rejoin it is *set* back exactly once (PENDING → RESTORED, conditional), so rapid reconnects
  can't apply it twice.
- Hearts are never part of the snapshot: a heart lost to a forfeit stays lost after the restore.
- The leaver can't rejoin the match, because their roster entry is DEFEATED.

On rejoin they also see what the forfeit did (forfeited, and the heart outcome). That notice lives
in memory, so a restart in between loses it. The heart change itself is stored.

### Tests

`lifestealRankedCheck` (section `forfeits`, all automated):

- the policy for every state and disconnect kind: before start, ACTIVE, after being knocked out,
  after the match, shutdown, staff kick, timeouts;
- 2v2-5v5 forfeits, several teammates disconnecting, and the same-tick disconnect + death
  tie-break;
- the recipient rule: most recent hit, window, future hits, teammates, self, bounded history;
- on a real SQLite HeartStore:
  - one heart moved, duplicates refused;
  - the same-victim cooldown, and a kill after a forfeit;
  - the 1-heart minimum and the 20-heart maximum;
  - teammates refused;
  - audit rows, and the heart total kept;
- store: the forfeit recorded once, the snapshot pending across a restart and restored once, an
  ordinary WIN record (rated like any other), no second result;
- the settings and the wiring.

`lifestealHeartCheck` now counts three `transferPvp` paths.

**Needs a live Paper server:**

- real quits, alt-F4 and timeouts mid-fight;
- kick-cause ordering;
- the heart moving to an online opponent;
- the rejoin notice;
- `/stop` mid-fight (expected: no forfeit, everyone restored);
- Bedrock disconnects.

### Remaining limitations

- A player who quits before anyone hits them (or more than one tag duration after the last hit)
  loses only the match, no heart.
- The rejoin notice is lost on a restart (the heart change and the snapshot are not).
- Profile PvP statistics read heart transactions, so a forfeit heart appears there like a kill.
- If the match is later cancelled (time limit, staff), a forfeit heart that already moved stays
  moved, like a kill in that match.
- Dodging by disconnecting during the countdown (cancel, unrated) and by hiding until the time
  limit are unchanged (section F).

### Checklist additions (section E)

15. In a 2v2, hit an opponent, then have them quit (alt-F4):
    - they are out, and their teammate keeps fighting;
    - the hitter gains one heart (`/lifesteal hearts get`);
    - on rejoin the leaver is restored and told they forfeited.
16. Quit without being hit: out of the match, no heart moves.
17. Quit at 1 heart: no heart moves. Quit when the hitter has 20: no heart moves.
18. `/kick` a participant mid-fight: out, no heart. `/stop` mid-fight: nobody forfeits, and
    everyone is restored.

## H. Heart feedback, ranked death detection and anti-farming monitoring (issue #1474)

Follow-up to the read-only cooldown audit (#1469) and the heart system audit (#1453). **No rule
changed**: heart limits (10 start, 1-20), the 30-minute same-victim cooldown, the order of the
heart checks, Elo, matchmaking, forfeits, inventory snapshots and restart recovery are exactly as
before.

### The shared 30-minute cooldown

Ranked has no cooldown of its own. Every heart-moving PvP path (open-world kill, Combat NPC kill,
ranked kill, ranked disconnect forfeit) goes through the same `HeartStore.transferPvp` and the
same `lifesteal_heart_cooldowns` row per directed pair (killer → victim), set by
`lifesteal.yml > pvp.same-victim-cooldown-minutes` (30). It starts only when a heart moves. It is
stored on disk with wall-clock time, so it keeps running through restarts, and a refused kill
never restarts it. A revenge kill (victim → killer) is a different pair. What players notice:

- a rematch within 30 minutes: the same killer → victim pair moves no heart; new pairings do;
- a ranked steal blocks the same pair in the open world for the rest of the 30 minutes, and the
  other way round;
- the match result and Elo never depend on hearts: a kill refused by the cooldown still counts
  toward winning.

### Ranked death detection

The #1461 combat-tag credit excluded ranked deaths with `matchOf(victim) != null`. But the ranked
death handler (HIGHEST) removes the victim from the held set before the heart handler (MONITOR)
runs, so that check was always false. It was harmless only because ranked hits never
combat-tag.

Now the heart listener's existing LOWEST handler copies the victim's match id with the server
tick (`RankedMatchRules.DeathCapture`: memory only, removed when used and on quit). `onDeath`
decides once with `RankedMatchRules.deathMatch(copy, tick, matchOf(victim))`, and a copy from
another tick is never used. That one answer drives both the combat-tag exclusion and the match
summary. There is no second match tracking, no stored state and no priority change.

### Heart outcomes in the match summary

For each ranked kill, the heart service passes its **stored** result (the `HeartRules.Decision`
that `transferPvp` returned; it is never called a second time) to
`lifestealRankedManager.recordHeartOutcome`. Forfeits add theirs from `forfeitHeart`. Each
outcome is kept on the in-memory match (at most 24, dropped with the match). It is also queued to
the match's event log (`lifesteal_ranked_events`, kind `HEART`, visible in
`/lifesteal admin ranked info <match>`). The per-kill chat lines players already got (stole a
heart, cooldown, last heart, maximum, teammates, ...) are unchanged.

After "X won the ranked match!", and after a cancellation (hearts that already moved stay moved),
everyone in the match and its spectators get one section through `commsManager`:

```
[Ranked] Hearts this match: 2 of 3 moved a heart.
[Ranked] Ann killed Bob: heart stolen
[Ranked] Ann killed Cid: no heart, same-victim cooldown
[Ranked] Dee forfeited to Ann: heart stolen
```

- Green lines moved a heart; gray lines didn't. A refusal is never worded as a steal.
- Reasons are the public ones only: same-victim cooldown, victim on their last heart, killer at
  the maximum, teammates, allies, grace period, season not launched, season state unknown, team
  data unavailable, heart data unavailable.
- A 5v5 has at most 9 deaths, so every kill fits. Past 12 lines the rest folds into
  "... and N more".
- A match with no heart decision shows no section.
- Nothing about connections, alts or reviews is ever shown to players.

### Anti-farming monitoring (logging only)

`lifestealRankedManager.monitor` (`lifesteal/ranked/monitor/`) checks each finished ranked match
that reached the fight, once, after its result is recorded, on the database thread
(`databaseManager.async`). Staff test matches and shutdowns are skipped, and any failure is only
logged. It **never** blocks a match, changes a heart decision or a rating, or punishes anyone.

| Finding | Counted from | Default (within `window-hours`, this match included) |
|---|---|---|
| `REPEATED_TEAM_MATCHUP` | non-test matches between the same two team ids (either order) | 4 in 24h |
| `REPEATED_PLAYER_MATCHUP` | non-test matches where the same two players were opponents, whatever their teams (catches rotating teams) | 6 in 24h |
| `REPEATED_HEART_TRANSFERS` | hearts one killer took from one victim, any PvP path (`lifesteal_heart_transactions`) | 3 in 24h |
| `SHARED_CONNECTION` | opponents AltManager saw on one connection (`player_connections` hashes compared in SQL, never read out) | on |
| `REPEATED_FORFEITS` | ranked matches a player forfeited by a counted disconnect | 3 in 24h |

Each finding produces:

- one row in `lifesteal_ranked_reviews`, the only new table (UUIDs, team ids, match id, kind,
  reason, timestamp; never an address or a hash);
- one `admin_logs` row (`RANKED_REVIEW_<KIND>` by "Lifesteal Ranked", visible in /admin);
- a console warning.

Duplicates: the dedupe key (kind + subject + match) is UNIQUE, so processing a match again adds
nothing, and the same kind + subject is logged at most once per window. Staff read the entries
with `/lifesteal admin ranked reviews [player]` (`joshymc.lifesteal.ranked.admin`). A shared
connection is not proof of one owner (households share one), and the entry says so.

Settings live in `lifesteal.yml > ranked-team-wars.monitoring`. They are read on their own: a
mistake only turns monitoring off, never the rest of Lifesteal, and a missing section uses the
bundled defaults.

```yaml
monitoring:
  enabled: true
  window-hours: 24             # 1-720
  repeated-team-matches: 4     # 0-1000, 0 = off
  repeated-player-matches: 6
  repeated-heart-transfers: 3
  repeated-forfeits: 3
  shared-connections: true
```

New indexes (no rows changed): `lifesteal_ranked_participants (uuid, match_id)` and
`lifesteal_heart_transactions (killer, victim, created_at)`.

### Tests

`./gradlew -I scripts/lifesteal-ranked-check.init.gradle.kts lifestealRankedCheck` now also covers
the `#1474` checks:

- death detection at every stage, stale copies, ordinary vs ranked environmental deaths,
  simultaneous deaths;
- summary lines for transfer, cooldown, last heart and maximum; refusals never shown as steals;
  several kills; 2v2-5v5 sizes; bounds; no heart transaction while building it;
- the cooldown through the real `HeartStore` for back-to-back matches, ranked ↔ open world,
  forfeits, restarts and direction, and Elo sources never reading hearts;
- every monitoring finding against a real SQLite database: thresholds, windows, test matches,
  dedupe and suppression, no heart or punishment change, no connection data stored, and the
  settings parser.

`lifestealHeartCheck` keeps the #1461 wiring checks, updated for the new exclusion.

**Needs a live Paper server:**

1. A 2v2 where both kills move a heart: the summary says 2 of 2, in green.
2. Rematch the same pairs within 30 minutes: those kills say "no heart, same-victim cooldown",
   and Elo still changes (`/ranked stats`).
3. Kill a 1-heart opponent, and kill while at 20 hearts: "last heart" and "at the maximum".
4. Play the same two teams four times within a day: `/lifesteal admin ranked reviews` lists one
   `REPEATED_TEAM_MATCHUP`, /admin logs show it, and nothing was blocked. A fifth match adds no
   second entry.
5. Two opponents on one home connection: one `SHARED_CONNECTION` review; the players see nothing.

### Remaining limitations

- The summary is chat only and in memory: a restart mid-match loses it (hearts, the event log and
  the cooldown are kept).
- Review entries are information for staff. There is no resolve/dismiss workflow and no
  automatic action (by design).
- Matchups are counted by team id and by player pair. A ring that rotates both teams and players
  across many accounts only shows up through the heart-transfer and shared-connection findings.
- `SHARED_CONNECTION` only knows what AltManager recorded (`player_connections`). Accounts never
  seen on one connection are never flagged.

## I. Environmental deaths in ranked matches (issue #1478)

Follow-up to the heart system audit (#1453), the ranked cooldown audit (#1469), the open-world
environmental credit (#1461) and the disconnect forfeits (#1465). **No rule changed**: heart limits
(10 start, 1-20), the 30-minute same-victim cooldown, the order of the heart checks, Elo,
matchmaking, forfeits, inventory snapshots and restart recovery are exactly as before.

### Root cause

A heart only moved when the final damage had a causing player. The #1461 combat-tag credit covers
environmental deaths in the open world, but it is switched off in ranked (ranked hits never
combat-tag), and nothing replaced it there. So a participant who was knocked into lava or the void,
fell to their death after a hit, or jumped into lava on purpose died "to the environment" and kept
their heart. The death still counted toward the match result.

### How the credit works

- **Hits**: the match already records the opponent hits on each participant for the #1465 forfeits
  (`Match.recentHits`, MONITOR with `ignoreCancelled`, only damage that landed, only between
  opposite roster sides of an ACTIVE fight, never self-damage, at most 8 per participant). There is
  no second combat tracker and no new table.
- **Capture**: the ranked death handler (HIGHEST) defeats the victim, which drops their hits. The
  heart listener's existing LOWEST handler therefore copies them with the match id, the server tick
  and the time into the #1474 `RankedMatchRules.DeathCapture`. That copy is in memory only, used
  only by the death in its own tick, and removed when used and on quit.
- **Who is credited** (`RankedMatchRules.environmentalKiller`): only when the final damage had no
  other player behind it (`ENVIRONMENT`: lava, fire, void, fall, drowning, ... or `SELF`: own TNT,
  jumping into lava). The candidate is the **most recent** recorded hit that is younger than one
  combat-tag duration (`combat.tag-duration-seconds`, the same window as #1461 and the forfeits).
  The attacker must be on the **other roster side of the same match, which must still be ACTIVE**
  (`isCreditableOpponent`; a defeated attacker keeps their side). They must also pass the normal
  attribution checks (online, Lifesteal, not an NPC, heart record loaded). If the newest attacker
  fails these checks, the next newest is used.
- **Never credited**: a direct PvP kill (it keeps its killer), a teammate, the victim themselves, a
  hit older than the window, a hit from another match, a copy from another tick, a death before the
  fight (that cancels the match), and `/kill`. With no eligible attacker, no heart moves: there is no
  random recipient and no Heart item, and the death still counts for the match.

### Hearts, dedupe and feedback

The credited death goes through the same `HeartStore.transferPvp` with the same per-death dedupe key
(`pvp:<boot>:<victim>:<tick>`) as a direct kill. Only one killer is chosen per death, so the two
paths can never both move a heart. The launch window, the team check (roster sides decide inside a
match), the 1-heart minimum, the 20-heart maximum (`NO_TRANSFER`) and the shared 30-minute cooldown
all apply unchanged. The audit reason gets `(ranked environmental credit, match <id>, died to
<damage type>)`.

Players get the normal heart messages naming the credited opponent ("You lost 1 heart to Ann!",
cooldown, last heart, maximum). The match summary lists it as `Bob died after a hit from Ann: heart
stolen` (or the refusal reason). Players never see the attribution data itself.

A death and a disconnect can't both decide: after the death the player is no longer held, so their
quit is not a forfeit, and a defeated player can't be defeated again.

### Inventory and match result

Unchanged. Drops and XP are still cleared at HIGHEST and the snapshot is restored at respawn. The
heart path never touches inventories or snapshots, and the defeat and the next-tick winner decision
run exactly as before.

### Tests

`./gradlew -I scripts/lifesteal-ranked-check.init.gradle.kts lifestealRankedCheck` adds the `#1478`
checks:

- the rule for lava, void, fall and self deaths, no hits, an expired window, several attackers,
  teammates, self-damage, `/kill`, ordinary deaths, stale copies, other matches and non-ACTIVE
  matches, and 2v2 to 5v5;
- against the real `HeartStore`: one heart moved, duplicate death events, the shared cooldown (also
  against open-world kills), 1-heart victims, killers at 20, the audit note and heart conservation;
- the summary wording and the wiring (captured at LOWEST, the one `transferPvp`, after the
  Lifesteal gate, no inventory access).

The existing Elo-independence, forfeit, restart and snapshot checks still pass unchanged.

**Needs a live Paper server:**

1. Knock an opponent into lava, into the void and off a high ledge: each costs one heart to the
   last hitter.
2. Hit an opponent, then let them jump into lava on purpose: the heart goes to you.
3. Wait longer than the combat-tag duration after the last hit, then die to lava: no heart.
4. With two attackers on one victim, the later hitter gets the heart; a teammate never does.
5. A rematch of the same pair within 30 minutes: "no heart, same-victim cooldown" in the summary.
6. After each death, respawn and check that the inventory is restored exactly.

### Remaining limitations

- Credit only follows hits the match recorded. Damage with no player behind it (a TNT minecart, or
  lava a player placed but never hit with) can't be attributed.
- The window is the combat-tag duration, not a separate ranked setting.
- The copy is memory only. A server crash in the middle of the death event leaves that death
  without credit (match recovery itself is unchanged).

## J. Staff TEST matches need confirmation and every player's consent (issue #1491, finding F-3)

### Root cause

`/lifesteal admin ranked testmatch <teamA> <teamB> <size>` called `createMatch` directly: the first
`size` online members of each team were snapshotted and teleported into a fight at once. TEST
matches move real permanent hearts, so a staff member could force players into a heart fight with
no consent, no confirmation, no staff hierarchy check and no `admin_logs` row, and the farming
monitor skipped TEST matches.

### New flow

1. **Staff preview** (`testmatch <teamA> <teamB> <size> [arena]`, `joshymc.lifesteal.ranked.admin`):
   checks permission, the staff hierarchy (`StaffRankManager.protectionProblem` for every
   participant: self, equal and higher staff are refused; console is allowed), the normal ranked
   eligibility of every player (`participantProblem`: online, team member, Lifesteal world, loaded
   hearts, combat tag, other match, pending restore, transfer, death, game mode, Mod Mode/frozen/
   trading/event world), not already in a queue/challenge/other test, the season unlock, arena and
   size. Then it shows both teams, the players, the size, the heart risk and a `[Confirm]` button.
   Nothing else happens. Audited `RANKED_TESTMATCH_REQUESTED`.
2. **Staff confirmation** (`testmatch confirm <rx-token>`): 60s, bound to the staff member (player
   UUID or console) and to the exact plan (teams by id, size, arena, the players on each side). The
   plan is rebuilt from live data and must match, else the confirmation is spent. Single use; a new
   request replaces the older one. All checks above run again. Audited `RANKED_TESTMATCH_CONFIRMED`.
3. **Player consent**: every participant gets the warning (permanent hearts at risk under the normal
   rules, the 1-20 range, the last heart can't be lost, no Elo, inventory saved, both teams and the
   size, who set it up) and `[Accept]`/`[Decline]` for that proposal (`/ranked accept|decline rt-...`).
   A token is required: a bare `/ranked accept` shows the proposal again. Leaders can't accept for
   anyone. The window is `ranked-team-wars.matchmaking.consent-seconds` (default 60). These two
   `/ranked` subcommands work for an asked player even while `/ranked` is closed to players.
   One decline, the timeout, a disconnect, a team change, another match/pending restore or joining a
   queue ends it (`RANKED_TESTMATCH_DECLINED` / `_EXPIRED` / `_CANCELLED`). Each accept is audited
   (`RANKED_TESTMATCH_ACCEPTED`).
4. **Start**: once (the single CONFIRMING → STARTING gate), only if the staff member is still online
   and authorized and every check passes again, through `LifestealRankedManager.createTestMatch`
   with exactly the accepted players. That runs the same validation, inventory snapshots and
   lifecycle as every other match (`RANKED_TESTMATCH_CREATED`). The end is audited once
   (`RANKED_TESTMATCH_COMPLETED` / `RANKED_TESTMATCH_MATCH_CANCELLED`) with every heart decision.

Staff can cancel an open test proposal with `/lifesteal admin ranked cancelproposal rt-...`; it also
shows in `/lifesteal admin ranked queue`. Nothing is persisted: a restart or reload ends every
confirmation and proposal (nothing was touched before the start).

### Hearts and Elo (unchanged)

TEST matches are still not heart-safe: kills, forfeits and environmental deaths go through the one
`HeartStore.transferPvp` with the normal cooldown, 1-heart minimum and 20-heart maximum. TEST matches
are never rated. Inventory snapshots and restoration are unchanged.

### Farming monitor

TEST matches are now checked when they end, with the same thresholds. Team and player matchups of
a TEST match are counted against other TEST matches only (so staff testing never inflates the public
counts); heart transfers and shared connections are counted as always. TEST findings get a `test:`
subject (separate suppression) and name the staff member who started the match, in
`lifesteal_ranked_reviews` and `admin_logs`. Still logging only.

### Checks

`./gradlew -I scripts/lifesteal-ranked-test-match-check.init.gradle.kts lifestealRankedTestMatchCheck`
(96 checks: permission, hierarchy, eligibility, confirmation expiry/reuse/ownership/changes, consent
per player and per proposal, decline/timeout/disconnect, start once, the warnings, unrated, heart
transfer + cooldown + minimum against the real `HeartStore`, TEST monitoring against SQLite, audit
wiring, unchanged challenges/matchmaking/restoration).

**Needs a live Paper server:**

1. As Admin, `testmatch A B 2`: only a preview. Confirm: the four players get the warning and
   buttons; nobody moves.
2. Three accept, one declines: everyone is told, nothing happens. Repeat and let it time out.
3. Repeat and have one player log out, or leave the team: the proposal ends at once.
4. Confirm after a teammate logs in/out between preview and confirm: "changed", not confirmed.
5. Include a staff member ranked equal or above the Admin, or the Admin themselves: refused.
6. All four accept: the match runs as before; a kill moves a heart; no Elo; inventories restored.
7. `/admin` logs show the REQUESTED, CONFIRMED, ACCEPTED, CREATED and COMPLETED rows.
8. Play four TEST matches between the same teams in 24h: one `test:` review entry naming the Admin.

### Limitations

- Staff can't include themselves in a test match (the hierarchy rule refuses self). Use console, or
  another staff member, to start one that involves you.
- The staff member must be online when the last player accepts (their rank and permission are
  re-checked live); otherwise the proposal is cancelled.
- Proposals are memory only (a restart cancels them before anything was touched).

## K. Restricted abilities, staff commands and delegated commands (issue #1498, findings F-2, F-4, F-5)

### Root causes

- **F-2**: the match entry rule (`RankedMatchRules.participantProblem`) checked game mode, Mod Mode
  and freeze, but not God Mode, Vanish or over-limit gear, and nothing re-checked a participant
  after the snapshot. The #1487 eligibility rule only blocked heart/bounty/coin rewards, so a
  player could switch on `/god`, `/vanish` or Creative mid-match and still win a rated match.
- **F-4**: `/heal`, `/feed`, `/fly`, `/god`, `/gamemode`, `/tp`, `/tphere`, `/smite`, `/vanish` and
  freeze acted on any online player, including ranked participants.
- **F-5**: the command allowlist for held players lived only in `PlayerCommandPreprocessEvent`.
  `Player.performCommand` (used by `/sudo`, NPC clicks and portal `command` actions) never fires
  that event, so those paths ran any command as a held player. The listener also judged only the
  typed label, so an allowed alias registered by another plugin for another command got through.

### Behavior

- **Entry**: `participantProblem` now also refuses everything `CompetitiveEligibility.problems`
  reports (God Mode, Vanish, Creative, Spectator, Mod Mode, over-limit worn gear), with a clear
  message (`RankedIntegrity.eligibilityProblem`). Queue registration, challenges, match acceptance,
  TEST consent and match creation already used it. The manager re-checks after preparation, every
  countdown second and immediately before ACTIVE.
- **Blocked before they happen**: any game mode change of a held player away from Survival
  (`PlayerGameModeChangeEvent`, whatever caused it: command, console, plugin), flight toggles
  (`PlayerToggleFlightEvent`), `/god` and `/vanish` on (self or others), Mod Mode on, and the staff
  commands above aimed at a held player (`refusesStaffAction`, server side in each executor, console
  included). `/tp`, `/tphere` and `/sudo` also wait while a player's snapshot is still pending.
- **Backstop**: once a second during the fight, every held participant is checked. Flight is taken
  away again. A restricted state found **before** the fight cancels the match (unrated, nobody's
  hearts change; the same as a disconnect before the fight). Found **in** the fight, the player is
  out as a forfeit (`DefeatReason.INELIGIBLE`), their snapshot is restored and their side is marked
  ineligible for this match. No heart moves for that forfeit; heart decisions already committed stay.
- **Result**: `win()` re-checks the winners still fighting; `ineligible_sides` is stored with the
  winner. `RatingRules` keeps a win by an ineligible side as the result but **never rates it**
  (`RankedIntegrity.UNRATED_REASON`). A **loss** by an ineligible side stays rated, so switching state
  can't be used to escape a loss. Going ineligible in the same tick as the opponent's last death is a
  forfeit (like a counted disconnect), so it can't turn a loss into a no-winner cancel. Exactly-once
  rating is unchanged (still one conditional `finish`/`complete` and one rating result row).
- **Delegated commands**: `DelegatedCommands.allowRun` (NPC, portal) and `/sudo` (every sender,
  console too) call `DelegatedCommands.rankedProblem` before `performCommand`: the same allowlist as
  typed commands, with the label resolved through the command map (`canonicalName`), so aliases and
  `plugin:` names can't slip past. The typed-command listener uses the same resolution.
- **Emergency interventions**: staff step in only through `/lifesteal admin ranked cancel|remove`
  (now written to `admin_logs`). A player of that match, or a member of either team, can't use them
  on a PUBLIC/CHALLENGE match (`RankedIntegrity.interventionRefusal`); TEST matches are exempt.
- **Audit** (`admin_logs`, throttled to one row per match/actor + target + action per minute):
  `RANKED_STAFF_ACTION_BLOCKED`, `RANKED_RESTRICTED_ABILITY_BLOCKED`, `RANKED_DELEGATED_COMMAND_BLOCKED`,
  `RANKED_MATCH_INVALIDATED`, `RANKED_INELIGIBLE_AT_RESULT`, `RANKED_RESULT_SUPPRESSED`,
  `RANKED_MATCH_CANCEL`, `RANKED_PARTICIPANT_REMOVE`. Invalidations also go to the match event log.
- No bans, mutes or other penalties are applied automatically.

### Checks

`./gradlew -I scripts/ranked-integrity-check.init.gradle.kts rankedIntegrityCheck` (Bukkit-free rules,
the result/Elo decision through a real SQLite `RankedStore`, and the wiring at every boundary).

**Needs a live Paper server:**

1. `/god`, then `/ranked queue`: refused ("has God Mode on"). Same with `/vanish`, `/gmc`, `/gmsp`, Mod Mode.
2. Start a match; during the countdown run `/god <participant>` from console: refused. Run
   `/gamemode creative <participant>` from console: refused (game mode stays Survival).
3. In the fight, `/heal`, `/feed`, `/fly`, `/tp`, `/tphere`, `/smite`, freeze on a participant: each
   refused with the cancel/remove hint, one `RANKED_STAFF_ACTION_BLOCKED` row.
4. `/sudo <participant> spawn` (as Owner and as console), an NPC with `spawn`, a portal `command`
   action: refused; `/sudo <participant> msg x hi` works.
5. With another plugin's god/vanish (or a test plugin forcing Creative), activate it mid-fight: within
   a second the player is out ("restricted ability"), restored and moved out; if their side still
   wins, the win message says "unrated" and `/lifesteal admin ranked rating match <id>` shows UNRATED.
6. A participant (staff) tries `/lifesteal admin ranked cancel <their match>`: refused.

### Limitations

- God Mode and Vanish from **other** plugins aren't detected (only JoshyMC's `/god`, `/vanish` and
  Mod Mode); Creative/Spectator are, whatever sets them.
- Other plugins' commands that heal or teleport a player directly (not through JoshyMC's executors)
  are only covered by the existing teleport/boundary listener, not by `refusesStaffAction`.

## L. Inventory snapshot completeness and restore safety (issue #1506, findings B-2, B-3/E-2)

### Root causes

- **B-2**: `capture` closed the player's menu before taking the snapshot, so the server put cursor and
  crafting-grid items back into the inventory. Anything that didn't fit was **dropped at the
  player's feet**, outside the snapshot (and pickable by anyone), and the close happened before the
  snapshot was stored.
- **B-3 / E-2**: the restore set the inventory, saved the player, then did `PENDING → RESTORED`. When
  that last write failed the row stayed PENDING, so the next join or respawn applied the same old
  snapshot again, overwriting (or duplicating against) everything the player had gained since.

### Snapshot (B-2)

- `capture` now **only reads**. Cursor items and the crafting grid (the 2x2 grid, or an open crafting
  table's 3x3 grid) are copied and placed into the snapshot's storage slots 0..35 the way vanilla
  returns them (similar partial stacks first, then empty slots; never armor/off-hand), by the
  Bukkit-free `RankedInventorySafety.place`. Stacks only merge when `isSimilar` (all metadata equal);
  every stack is serialized with `serializeAsBytes`, so custom ids, enchantments, durability, custom
  model data, amounts and PDC are kept exactly. The crafting result slot is derived and never captured.
- If they don't fit, or an anvil/grindstone/enchanting/smithing/stonecutter/loom/cartography/
  merchant/beacon menu that hands its items back on close holds anything, the match is **refused**
  ("make room first" / "close it first"), nothing changed, `RANKED_SNAPSHOT_REFUSED` in `admin_logs`.
- Only after `insertMatch` committed does `applyCapture` make the live inventory equal the snapshot:
  cursor and grid emptied first, then the changed slots, then the menu closes with nothing to drop.
  A failed insert returns before anything is touched. The snapshot format is unchanged (still 41
  slots), so the #1490 XP/effect fields and every older snapshot work as before.

### Restore protocol (B-3 / E-2)

A Bukkit inventory change can't be inside a SQLite transaction, so each restore is three durable steps
on the snapshot row (`RankedInventorySafety`, the allowed moves enforced by a trigger):

1. `PENDING → RESTORING` (conditional UPDATE with this plugin instance's `restore_boot` id) **before**
   the player is touched. A failed write: nothing changes, it stays PENDING, retried on the next
   join/respawn/staff restore (`RANKED_RESTORE_DB_FAILED`). Zero rows changed: another restore already
   took it and nothing is applied (`RANKED_RESTORE_DUPLICATE_PREVENTED`), so concurrent join + respawn
   or completion + rejoin can't apply it twice.
2. Inventory, XP and effects set (the unchanged #1490 logic), player saved.
3. `RESTORING → RESTORED`. If this write fails (`RANKED_RESTORE_MARK_FAILED`) the row stays
   RESTORING: it is not PENDING, so no join/respawn/startup applies it again; the mark is retried on
   joins, restores, staff restores and at shutdown.

At startup, a RESTORING row from **another** boot (crash, or a failed confirm before a restart) is
ambiguous: the player's saved inventory may already be the restored one. It becomes **REVIEW**
(`review_reason`, `RANKED_RESTORE_REVIEW`, match `restoration = REVIEW`) and is never applied
automatically; the data is kept. A partial UNIQUE index allows one PENDING/RESTORING/REVIEW snapshot
per player, and the participant check treats one as "waiting to be restored", so such a player can't
enter a new match whose inventory the old snapshot could later overwrite.

Staff:

- `/lifesteal admin ranked restores`: snapshots waiting for review.
- `/lifesteal admin ranked restore <player>`: restores a PENDING snapshot, or explains a REVIEW one.
- `/lifesteal admin ranked restore <player> apply confirm`: check the player's inventory first; if the
  pre-match items did not come back, applies it (`REVIEW → RESTORING → RESTORED`, replaces the current
  inventory, no teleport).
- `/lifesteal admin ranked restore <player> resolve confirm`: the items did come back; `REVIEW →
  RESTORED` without applying anything. Both are in `admin_logs` (`RANKED_RESTORE_APPLY` / `_RESOLVE`).

Migration: four nullable columns (`restore_started_at`, `restore_boot`, `review_reason`,
`reviewed_at`), one partial index and one trigger, all `IF NOT EXISTS` / `ADD COLUMN`. Existing PENDING
and RESTORED rows are unchanged and go through the new protocol; nothing is reset or deleted.

Hearts, Elo, matchmaking, team sizes, TEST/CHALLENGE rules, forfeits and the own-gear rule are unchanged.

### Checks

`./gradlew -I scripts/ranked-inventory-check.init.gradle.kts rankedInventoryCheck` (the placement
rules with stand-in stacks, the protocol against a real SQLite database reopened to simulate restarts
and closed to simulate failed writes, old-schema rows, and the wiring). The existing
`lifestealRankedCheck`, `lifestealRankedQueueCheck`, `lifestealHeartCheck`, `rankedIntegrityCheck`,
`lifestealRankedTestMatchCheck` and `lifestealRankedEloCheck` still pass.

**Needs a live Paper server:**

1. Hold a custom item (e.g. an enchanted, damaged Autumn's Edge) on the cursor in your inventory
   screen when the match starts: it lands in a free slot, nothing drops, and after the match it's
   there exactly once with the same enchants, durability and model.
2. Put items in the 2x2 grid (and separately in an open crafting table) when the match starts: same.
3. Fill your inventory completely, hold an item on the cursor and accept the match: refused with
   "make room first", nothing dropped, inventory unchanged.
4. Put items in an anvil and accept the match with it open: refused ("close it first").
5. Die in a match, respawn: restored once (`lifesteal_ranked_snapshots.status = RESTORED`).
6. Disconnect mid-fight, rejoin: restored once; die again outside ranked and respawn: nothing reapplied.
7. Kill the server process (not `/stop`) right after a restore message: on restart the snapshot is
   REVIEW, the player is told it needs a staff check and `/ranked queue` is refused; `restore <p>
   resolve confirm` or `apply confirm` settles it and they can queue again.

### Limitations

- A crash between step 1 and the player's save always needs a staff decision; the database can't tell
  whether Paper wrote the restored inventory.
- A failed RESTORED mark is retried only while the server runs (memory); if it still fails at shutdown
  the snapshot becomes REVIEW after the restart.
- Only crafting grids are placed automatically; other open menus with items refuse the match.
## L. Arena interference from outside (issue #1507, finding F-6)

### Root cause

The match listener only restricted the match's own participants and spectators (no building,
buckets, containers) and kept outsiders from walking or teleporting into an arena. Nothing stopped
someone standing just outside the boundary from reaching in: placing or breaking blocks, emptying
buckets, letting water or lava flow in, pushing blocks in with pistons or slime/honey contraptions,
spreading fire, firing dispensers, dropping sand/anvils or sending TNT in. Explosions already never
broke arena blocks, but unattributed TNT still hurt participants.

### Behavior

`RankedArenaGuardListener` (registered next to `LifestealRankedListener`, only where Lifesteal is
hosted) applies the Bukkit-free `RankedArenaGuard` rules. **Protected area** = the stored arena
boundary of every arena in use:

- held by a match (from PREPARING through COUNTDOWN, ACTIVE and FINISHING until the cleanup
  releases it), or
- reserved by a queue proposal waiting for its players to accept.

The list is rebuilt from that in-memory state at most once per tick, so a cancelled or completed
match, a restart or an expired proposal never leaves an arena protected. Idle arenas aren't
protected by JoshyMC; put a WorldGuard region on them for that (staff can maintain them between
matches).

While an arena is in use:

| Path | Rule |
|---|---|
| Block place (incl. beds/doors, every block of a multi-place), break, sign edit | refused for everyone inside the boundary |
| Buckets | refused when the fluid block or the clicked block is inside |
| Using arena blocks from outside (doors, levers, buttons, note blocks, dragon egg) | refused for anyone not in that match |
| Item frames, paintings, armor stands | not placed, broken, popped, rotated or manipulated |
| Boats, minecarts, armor stands, End Crystals placed inside | refused unless the placer is in that match |
| Water / lava / dragon egg (`BlockFromToEvent`) | refused when flowing from outside into the arena; inside it fluids behave normally |
| Fire, vines, sculk, grass spreading (`BlockSpreadEvent`) | refused from outside into the arena |
| Ignition | allowed from fire/lava already inside, from the match's participants (combat unchanged) and from End Crystals; refused from outsiders and unattributed sources outside (lightning, dispensed fire) |
| Burning | arena blocks never burn |
| Pistons (normal, sticky, extend, retract, slime, honey) | refused unless the piston, its head, every moved block and every destination are all in the same arena (or none is in any arena) |
| Dispensers / droppers | refused when the block in front is inside the arena and the dispenser isn't |
| Trees, bone meal, sponges | blocks inside the arena are dropped from the change when it starts outside |
| `EntityChangeBlockEvent` (falling blocks landing, Endermen, Withers, Ravagers, axes/hoes/shovels, projectiles breaking pots) | refused inside the arena |
| Explosions | arena blocks are removed from the block list (also for reserved arenas) |
| Damage to participants of an ACTIVE match with no player behind it | explosions other than End Crystals, dispenser projectiles from outside the arena and falling blocks from outside the arena are cancelled; everything with a player behind it stays with the match listener's rules |

Every handler only cancels (or removes blocks from a list) with `ignoreCancelled`, so WorldGuard
and claims still decide first and anything they refused stays refused. Hearts, Elo, results,
snapshots, restoration and queue eligibility are untouched.

### Checks

`./gradlew -I scripts/ranked-arena-guard-check.init.gradle.kts rankedArenaGuardCheck`: zone
lifecycle (reservation, match, release, restart, several matches), player changes, fluids, buckets,
dispensers, pistons (push, pull, slime, honey, head, inside-only, unrelated), fire, hazards, and the
listener wiring (every event handled, `ignoreCancelled`, never un-cancels, once-per-tick cache).

### Live Paper verification

With a running 2v2 TEST match in an arena, from a second account outside the boundary:

1. Place and break blocks on the boundary face; empty a water and a lava bucket onto it; pick up
   arena water with a bucket.
2. Pour water and lava one block outside so they flow toward the boundary; they stop at it.
3. Build a piston, a sticky piston, a slime-block and a honey-block contraption pushing into and
   pulling out of the arena; none moves.
4. Fire a dispenser (water, lava, flint and steel, TNT, arrows) at the boundary and from two blocks
   away; light fire next to flammable arena blocks; drop sand/an anvil from above the boundary.
5. Run a TNT cannon into the arena: no blocks break and participants take no damage.
6. Confirm participants' own End Crystals, flint and steel and hits still work, and that after the
   match ends (win and `/lifesteal admin ranked cancel`) all of the above work normally again.
7. With a WorldGuard region on the arena, confirm its denials still apply (nothing JoshyMC allows).

### Limitations

- Idle arenas (no match, no proposal) are not protected by JoshyMC; that needs a WorldGuard region.
- Redstone signals crossing the boundary aren't blocked (`BlockRedstoneEvent` can't be cancelled);
  pistons, the usual way a signal changes the arena, are.
- A TEST proposal still waiting for acceptance doesn't reserve its arena; it is protected once the
  match starts (PREPARING).
- Projectiles and wind charges from outside still fly in; damage from a player behind them is
  refused by the match listener's damage rule, and their explosions break no arena blocks.
- Paper's exact event coverage (e.g. which tools fire `EntityChangeBlockEvent`) needs the live
  checklist above.

## M. Arena entry, spectator integrity and outsider interference (issue #1524)

### Review: what already existed

Before this issue the match listener already refused outsiders walking or teleporting into an
arena a match holds, refused damage between a match and anyone outside it (melee, projectiles,
potions, clouds, combustion resolved to a player), kept spectators out of damage, building,
buckets, containers, entity use and item pickup, and the F-6 guard (section L) kept the arena's
blocks from changing. `outsidersIn` refuses to start a match while someone stands in the arena, and
`/lifesteal admin ranked spectate` (D-L7, #1511) teleports spectators to the spectator point only
after `CombatTeleportGuard` allows it.

### Gaps closed

| Gap | Fix |
|---|---|
| An outsider already inside a protected arena (logged in there, respawned at a bed/anchor there, ridden in, or standing there while a queue proposal reserved it) was never moved | A 1-second sweep in `LifestealRankedManager` moves them to the arena's spectator point, else the Lifesteal spawn |
| Riders (horses, boats, minecarts) don't fire `PlayerMoveEvent`, so they could ride in | `onVehicleMove` stops the vehicle at the boundary and puts the outsider back outside |
| Arenas reserved by a queue proposal could be walked into (and an outsider standing there blocked the match from starting) | entry rules and the sweep cover reserved arenas; the proposal's own players may go in |
| A tamed mob whose owner is offline counted as environmental damage | resolved as `ABSENT_OWNER` and refused like an outsider |
| A tamed llama's spit, Evoker Fangs and clouds weren't followed back to a player | shared `RankedDamageSources` resolver (projectile → shooter → pet → owner, TNT → igniter, cloud → thrower) |
| Outsiders' pets could still target participants | `EntityTargetLivingEntityEvent` refused unless the owner fights in that match |
| Spectators could drop items, step on pressure plates/tripwires and hit non-player entities (End Crystals, mounts) | refused |
| Outsiders could detonate a participant's End Crystal from over the boundary, or hit mounts/mobs in the arena | non-player entities in an arena in use can only be hurt by that match's participants |
| Spawn eggs, thrown eggs, mob buckets, golem/wither builds and breeding in an arena in use | refused (natural and spawner spawns stay) |
| Pets following an outsider, endermen/shulkers hopping in, mobs sent through portals | non-player entities can't teleport or portal into an arena in use from outside |
| Outsiders could use a participant (leads, name tags) or any entity inside the arena | refused |
| A spectator who left the arena's world stayed registered (restricted everywhere) | spectating ends on world change |

### Relocation rules (`RankedArenaAccess.relocation`)

1. Participants of the match (and, for a reservation, the proposal's players) are never moved.
2. Dead players and players this system is already moving (an expected teleport, a restore, a
   spectate request) are left alone.
3. **Combat-tagged outsiders are never moved** (the answer comes from `CombatTeleportGuard`, so
   moving them can't end their fight early). They stay where they are and every interference rule
   keeps refusing them: they can't hurt participants or be hurt by them, can't change blocks, use
   blocks, entities or pressure plates, and can't pick anything up. They're told once every few
   seconds and moved once their tag ends.
4. One attempt per player every 5 seconds, so a failed or refused teleport can't loop.
5. The destination must be loaded, `SafeSpots`-safe and outside every protected arena; an unloaded
   spectator point is loaded asynchronously for the next sweep. No destination = deny only.

Protection follows the authoritative state only: a match from PREPARING to FINISHING, and an open
queue proposal. Nothing is stored, so a completed or cancelled match, an expired proposal or a
restart releases the arena at once, and spectator registrations end with the match (release), on
quit and on a world change.

Unchanged: team sizes, Elo, bring-your-own gear, snapshots and restoration, heart limits and
cooldown, the heart transfer itself, disconnect forfeits, matchmaking and the time limit.

### Checks

`./gradlew -I scripts/ranked-arena-access-check.init.gradle.kts rankedArenaAccessCheck`: entry
for participants, reserved players and outsiders across simultaneous matches; relocation (combat
tag, no destination, dead, retries); harm from outsiders (melee, projectiles, potions, clouds,
TNT, online and offline owners' pets), participant combat, spectators both ways; pet targeting;
entity harm; spawn reasons; entity arrival; and the manager/listener wiring (reserved arenas,
memory-only zones, sweep start/stop, CombatTeleportGuard, async chunk loads, cleanup on quit and
world change, no un-cancelling, no hearts/Elo/snapshot code).

### Live Paper verification

With a running 2v2 TEST match and a third account:

1. Log out inside the arena before the match is reserved, then log in during the countdown: you're
   moved to the spectator point within a second. Repeat with a bed set inside the arena and `/kill`.
2. Ride a horse and a boat toward the boundary: you're stopped and put back outside.
3. Get combat-tagged by a fourth account, then enter the arena by logging in inside it: you are not
   moved while tagged, can't hurt or be hurt by participants, can't use blocks or plates, and are
   moved once the tag ends.
4. Send a tamed wolf at a participant, then log out with the wolf still sitting there: it neither
   targets nor hurts them.
5. From outside the boundary, shoot a participant's End Crystal; throw a spawn egg and a chicken egg
   onto the arena floor: nothing happens.
6. As a spectator, drop an item, step on a pressure plate, hit an End Crystal: all refused. Leave the
   arena world: you stop spectating.
7. During a queue proposal (before everyone accepts), walk into the reserved arena: refused.
8. After a win and after `/lifesteal admin ranked cancel`, everything above works normally again.

### Limitations

- Wild (untamed) mobs that walk into the arena are left alone: they are part of normal Minecraft
  and their damage is environmental, as before.
- A TEST proposal waiting for acceptance still doesn't reserve its arena (section L).
- The sweep runs once a second, so an outsider who logs in inside an arena can stand there for up
  to a second; nothing they do in that time reaches the match.
