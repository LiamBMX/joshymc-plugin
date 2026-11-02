# Lifesteal launch progression (issue #1352)

Lifesteal only. The launch schedule of a season: the grace period, the Ranked Team Wars unlock and
the End unlock. On a server that doesn't host Lifesteal nothing here runs: no tables, no task, no
listener, no PvP, End or announcement changes.

Code: `lifesteal/progression/` (`ProgressionRules` and `ProgressionStore` are Bukkit-free,
`LifestealProgressionManager` is the one service, `LifestealProgressionListener` enforces it on
events, `LifestealProgressCommand` is `/lifesteal progress` and `/lifesteal admin launch`).

## The schedule

Counted in **elapsed real time from the season's launch time**, never calendar dates:

| Milestone | When | What changes |
|---|---|---|
| Day 0: launch | launch time | Lifesteal opens; the grace period starts |
| Day 1: grace ends | launch + 24h | Hostile PvP is on; eligible PvP kills move hearts |
| Day 3: Ranked Team Wars | launch + 72h | Ranked becomes eligible (playable only once the ranked system exists and is enabled) |
| Day 7: the End | launch + 168h | End portals and teleports into the End work |

There are no other milestones. Downtime counts: a server that was offline during a milestone is
correct the moment it starts again, the countdown never pauses, and the grace period never restarts.

### Phases and checks

`ProgressionRules.Progress` gives the phase (`NOT_SCHEDULED`, `PRE_LAUNCH`, `GRACE_PERIOD`,
`NORMAL_GAMEPLAY`, `RANKED_UNLOCKED`, `END_UNLOCKED`, plus `DISABLED` and `UNKNOWN`) and the
independent checks other systems use:

- `pvpProtected`: before launch, during the grace period, and while the state is unknown.
- `heartWindow`: `OPEN` only after the grace period (or with progression disabled).
- `endLocked`: until +168h, and while the state is unknown.
- `rankedStatus`: `LOCKED`, `UNLOCKED_UNAVAILABLE` (by schedule, but no ranked system is
  registered/enabled: always the case today), `AVAILABLE`.

A milestone is reached when the clock passes it **or** it is recorded for the season; a later
milestone implies the earlier ones. So a system clock going back never relocks anything (the clock
going back before a milestone was recorded only delays it, never skips it).

Future Ranked Team Wars (Phase 3) register `lifestealProgressionManager.rankedTeamWarsSystem` and
must check `lifestealProgressionManager.canEnterRankedQueue()` at queue entry. Nothing here creates a
queue, matchmaking or Elo.

## Launch time and seasons

- One authoritative launch time per season, stored as UTC epoch milliseconds in
  `lifesteal_seasons` (`data.db`). A fresh install has **no** season: nothing starts on its own.
- Staff set it with `/lifesteal admin launch schedule <timestamp>` or `activate`, or by putting
  `progression.launch-timestamp` in `lifesteal.yml` (quoted ISO-8601 with an offset, e.g.
  `"2026-12-01T18:00:00Z"`; times without an offset are refused as ambiguous).
- **Precedence**: the stored season wins. `launch-timestamp` only creates the first season while the
  database has none; a different value later is logged once at startup and ignored.
- A launch time can only move while it is still in the future; once launched it can't be changed,
  re-activated or reset (also enforced inside the UPDATE, with a version check).
- The first season is `season-1`. Milestones (`lifesteal_season_milestones`) and the audit
  (`lifesteal_progression_audit`) are keyed by season id, so a later season can't inherit another's
  unlocks. At most one season is ACTIVE (partial UNIQUE index). Season rollover, resets and
  automatic three-month scheduling are a later phase.

## Grace period (and before launch)

`LifestealProgressionListener`, HIGHEST + ignoreCancelled, Lifesteal-world victims only:

- Damage the game credits to another player is cancelled: `damageSource.causingEntity` (melee,
  bows, crossbows, tridents and Channeling lightning, fireworks, End Crystals someone broke, TNT they
  lit, harming potions), with the damager / projectile shooter / TNT igniter / pet owner as fallback.
- Harmful splash and lingering potions skip other players, flaming hits don't ignite them, and tamed
  pets don't target other players.
- Damage without a player behind it (fall, lava, fire ticks, drowning, void, mobs, beds, anchors)
  is untouched: nobody is invulnerable. Self-damage is untouched.
- `CombatListener` tags at MONITOR with ignoreCancelled, so blocked hits never start a combat tag.
- No exceptions: the blueprint defines none.

Hearts: `HeartRules.decide` checks the window first (`GracePeriod` / `NotLaunched` /
`ProgressionUnknown`, recorded in `lifesteal_heart_transactions` like other refusals, no cooldown,
no heart change). The window is taken from the same timestamp as the death, so a death processed a
moment later can't fall after the grace period. Minimum 1, maximum 20, starting 10, the 30-minute
cooldown, teammates and the per-death dedupe key are unchanged.

## The End

Until it unlocks, players can't enter any End-environment world that belongs to Lifesteal:

- End portals (`PlayerPortalEvent`): refused; the player is moved onto the portal frame next to
  them (or the world spawn) so they don't fall into the stronghold's lava.
- Any teleport into the End (`PlayerTeleportEvent`: `/tp`, `/execute in`, warps, other plugins'
  portals), and vehicles carrying a player (`EntityPortalEvent`).
- Backstop: arriving in the End by any other path (`PlayerChangedWorldEvent`) or joining inside it
  moves the player out (respawn point if it isn't in the End, else the Lifesteal overworld spawn).

Leaving the End always works. The End world is never unloaded, generated, reset or deleted; with no
End world loaded there is simply nothing to block (`/lifesteal admin launch status` says so). Only an
explicit grant of `joshymc.lifesteal.progression.end-bypass` (default **false**, not even ops) gets in
early, for staff emergencies.

## Commands and permissions

| Command | Permission | |
|---|---|---|
| `/lifesteal progress` | `joshymc.lifesteal.progress` (true) | Phase, grace, Ranked and End status with time left (local times in the player's `/timezone`). Only on a server hosting Lifesteal; elsewhere `/lifesteal progress` still transfers. |
| `/lifesteal admin launch status` | `joshymc.lifesteal.progression.admin` (op) | Service state, season, schedule, every milestone in UTC, current checks |
| `/lifesteal admin launch schedule <timestamp> [confirm]` | same | Create or move a future launch; without `confirm` it only previews |
| `/lifesteal admin launch activate [confirm]` | same | Launch now; once only |
| `/lifesteal admin launch audit` | same | The last 10 audit rows |

Every change is audited (`lifesteal_progression_audit`) and logged to the console. There is no
bypass, unlock or reset command.

## Announcements and countdowns

Each milestone is announced once per season to Lifesteal players (and the console), with the
announcement header (`AnnouncementManager.formatBroadcast`), within 20 seconds of it. The milestone row's
`announced` flag is claimed with a conditional UPDATE, so restarts never repeat one. After downtime
only the latest newly reached milestone is announced, the rest are marked silently. The Ranked line
says "unlocked by schedule" while no ranked system is available. Messages: `progression.announcements`
(`enabled: false` still marks them, so turning it on later doesn't replay old ones).

Countdowns: `/lifesteal progress`, one join reminder line while PvP is still off
(`progression.join-reminder`), and an action-bar hint (at most every 2s) when a hit or End entry is
refused. The scoreboard is unchanged. All are plain chat/action-bar text, the same for Java and
Bedrock.

## Configuration (`lifesteal.yml > progression`)

| Key | Default | |
|---|---|---|
| `enabled` | true | false = no launch schedule here: nothing is held back (logged as a warning) |
| `launch-timestamp` | "" | Seeds the first season only (see Precedence) |
| `grace-period-hours` | 24 | 0..720, 0 = no grace |
| `ranked-team-wars-unlock-after-hours` | 72 | >= grace |
| `end-unlock-after-hours` | 168 | >= ranked, inside the shortest season |
| `join-reminder` | true | |
| `announcements.*` | see the file | `&` codes, `\n` for new lines |

The day-numbered keys from issue #1336 (`ranked-team-wars-unlock-day`, `end-unlock-day`, "day 1 =
launch day", i.e. 48h for day 3) are no longer read: with their default values they are a warning
and the approved hours apply; any other value makes the file invalid until it is replaced.

## Failures

The state is `UNKNOWN` (PvP protected, no heart moves, End locked, Ranked locked, admin changes
refused) when `lifesteal.yml` is invalid (until fixed + `/joshymc reload`) or the database can't be
read (retried every 20 seconds). A failed milestone write only delays recording/announcing: the
checks are time-derived and keep working. Nothing ever deletes or rewrites recorded progression.

## Performance

Checks read an in-memory copy (no database call per hit, teleport or death). One repeating task every
20 seconds records/announces milestones and is cancelled once all four are recorded. It's cancelled
on reload and shutdown. Player state is only a bounded, UUID-keyed hint map cleared on quit.

## Checks

`./gradlew -I scripts/lifesteal-progression-check.init.gradle.kts lifestealProgressionCheck`:
settings and timestamp validation, exact 24/72/168h boundaries with fixed clocks, pre-launch,
activation, restart during grace and after unlocks (reopened SQLite file), offline milestones,
duplicate announcement prevention, clock going back, heart decisions in each window (with teammates,
minimum, maximum and cooldown), admin decisions, season isolation, and the Lifesteal-only wiring.

## Manual tests (live Paper server)

1. Fresh Lifesteal server: `/lifesteal progress` says not launched; two players can't hurt each
   other (melee, bow, crossbow, trident, splash potion, flame, wolf); fall/lava/mob damage still hurts.
2. `/lifesteal admin launch schedule <~2 min ahead>` then `... confirm`; launch announcement arrives
   once; `/lifesteal progress` counts down. Restart during grace: grace continues, no new announcement.
3. Set `grace-period-hours: 0` on a test copy (or wait 24h): PvP works, a kill moves a heart.
4. Before +168h: End portal (player lands on the frame), `/tp @s` into `world_the_end`, `/execute in
   minecraft:the_end run tp`, a boat through the portal, and logging in inside the End: all refused
   or moved out. With `end-bypass` granted it works. After +168h everything works.
5. Repeat 1 and 4 with a Bedrock (Floodgate) player.
6. Survival server: none of the above changes anything; `/lifesteal progress` still transfers.
