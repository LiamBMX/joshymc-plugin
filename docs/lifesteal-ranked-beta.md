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
