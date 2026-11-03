# Ranked Team Wars: matchmaking, queues and team challenges (issue #1363)

Lifesteal only. Teams queue for 2v2, 3v3, 4v4 or 5v5, every picked player agrees, the matchmaker
pairs teams, everyone accepts the found match, and the match then runs through the existing
Ranked Team Wars lifecycle ([lifesteal-ranked.md](lifesteal-ranked.md)): same validation, same
arena reservation, same inventory snapshot and restore, same heart service. **No Elo, team Elo,
leaderboards, rewards, divisions or seasonal resets** are part of this.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/ranked/queue/`

| File | What it is |
|---|---|
| `RankedQueue.kt` | Bukkit-free queue state: `QueueState`, `QueueEntry`, `Proposal`, `Challenge`, `RankedQueueBook` (indexes, limits, every transition), `OpponentSelector` + `FirstInFirstOut`, `BoundedCooldowns`. |
| `LifestealRankedQueueManager.kt` | Live checks, messages, the 1-second matchmaking tick, hand-off to `LifestealRankedManager.createQueuedMatch`. |
| `RankedCommand.kt` | `/ranked` |
| `RankedQueueMenu.kt` | The `/ranked` menu (27-slot chest, player picker, challenges). |
| `RankedQueueStore.kt` | `lifesteal_ranked_queue_audit` (async audit log, 30-day retention). |

## Dependencies checked

Merged before this: Lifesteal teams (#1346/#1347), team PvP (#1348/#1349), combat logging
(#1350/#1351), launch progression (#1352/#1354), combat teleport guard (#1356/#1357) and the ranked
arenas and match lifecycle (#1361/#1362). Nothing here duplicates them: there is no second arena
manager, match lifecycle, snapshot or heart code.

## Rules (unchanged)

2v2 to 5v5 (`ranked-team-wars.team-sizes`), players from existing Lifesteal teams, their own gear,
inventory restored afterwards, hearts at risk through the normal heart service (1 to 20), no
elimination or revival. Ranked unlocks on Day 3 of the season: queueing needs the progression's
`canEnterRankedQueue()` (the scheduled unlock **and** this matchmaking being ready, which the queue
manager registers as the progression's `rankedTeamWarsSystem`).

## Flow

1. **Leader picks the players** (`/ranked queue 3v3 Bob Carol`, or the menu). Only the team
   leader can queue or send/answer challenges (the team model has no officers). List `size - 1`
   teammates to play yourself, or `size` to sit out. With no names, the online members are used
   only when exactly `size` are online. A five-member team can play 2v2 with two of them;
   non-members can never be picked.
2. **Every picked player agrees** (`/ranked accept`, the chat button or the menu). The leader who
   queued and plays counts as agreed; nobody else is enrolled without agreeing. Anyone declining
   (`/ranked decline`) drops the roster. Unanswered after `consent-seconds` → expired.
3. **Waiting** in the per-size queue. A team waits at most `queue-timeout-minutes`.
4. **Match found**: once a second (and right away when a team starts waiting) the matchmaker takes
   the eligible waiting entries of each size, oldest first, and pairs the longest-waiting team
   with the longest-waiting compatible one (different team, no shared player, same size and
   season) when an arena hosting that size is free. Entries with a temporary problem (someone
   combat-tagged, dead, switching servers, in another match...) are skipped, keep their place and
   the team is told once ("Matchmaking is paused for your team: ..."). The arena is reserved for
   that proposal (no other proposal can take it).
5. **Confirmation**: every player of both teams gets a title, the team names, the size, the
   heart-risk and gear notices, and Accept/Decline buttons; non-accepted players get an action-bar
   reminder every 5 seconds. Accepting is refused while combat-tagged or otherwise ineligible.
   Accept is bound to the player's UUID and the proposal id (stale buttons are refused; a second
   accept does nothing). A decline or disconnect ends that team's entry, gives the player the
   `decline-cooldown-seconds` cooldown, and puts the other team back in the queue **with its
   original place**. On timeout, the team(s) that didn't fully accept expire (silent players get
   the cooldown) and a fully-accepted team waits again.
6. **Start** (everyone accepted, exactly once): both teams and every player are revalidated, the
   arena is re-checked (another free one is used if it got taken), then
   `LifestealRankedManager.createQueuedMatch` runs the normal match creation: validation, the
   arena reservation in `lifesteal_ranked_matches`, inventory snapshots, preparation, teleports,
   countdown. The queue entries are then gone; the match manager owns everything (disconnects,
   deaths, restore). If one team fails revalidation, only that team's entry ends; the other keeps
   its place. If no arena is free, both wait again.

## Team challenges

`/ranked challenge <team> <size> [players...]` (leader): the challenger picks and its players
agree like a queue entry, but the roster never enters the public queue. The other team's leader
answers with `/ranked challenge accept <team> [players...]` (their players agree too) or
`/ranked challenge decline <team>`; unanswered challenges expire after `challenge-expire-seconds`.
Once both rosters are ready, it becomes a found match with the same confirmation and start as
above. A team has at most one open outgoing challenge, may send one every
`challenge-cooldown-seconds`, and can receive at most 5 open challenges. Declining or failing a
challenge match ends the whole challenge.

**Match records**: queued matches are `kind = PUBLIC`, challenges `kind = CHALLENGE` (staff test
matches `TEST`), with the proposal id in `queue_ref`. Whether challenge results count towards a
future competitive Elo is an **open policy decision**; nothing assumes either way.

## Safety

- **Inventories, hearts, positions, combat tags**: the queue never touches them. Snapshots,
  restores, teleports and heart transfers are the match manager's and heart service's, unchanged.
  Cancelling or expiring a queue entry changes nothing for the players.
- **Combat and teleports**: combat-tagged players can't queue, agree or accept, and a tag while
  waiting pauses matchmaking for that team. Once the match exists it holds its players (the
  existing rules block `/spawn`, `/home`, `/team home`, every other command and player-initiated
  server transfers). Combat-tag durations are untouched.
- **Disconnects** during agreeing, waiting or confirming end that team's entry (teammates are
  told); during confirmation the player also gets the decline cooldown, so disconnect/reconnect
  can't be abused. Once the match exists, disconnects are the match manager's (unchanged).
- **Restart / reload / shutdown**: the queue is memory only. Shutdown and reload cancel every
  entry, proposal and challenge (players are told); after a restart the queue is empty and
  nothing is resumed. A match that already started is recovered by the match manager as before.
- **Team changes**: leaving, being kicked, disbanding or a new season ends the entry on the next
  tick; the start revalidates again.
- **Limits**: 200 entries (one per team), 100 proposals, 200 challenges, bounded cooldown maps
  (2,000 keys each). Per-player 400 ms command/menu spam guard; team requeue cooldown after an
  entry ends.
- **Performance**: one 1-second task (returns at once while the queue is empty), checks only
  queued players, no database reads during matching (the audit log is written async).

## Commands

```
/ranked                                        menu
/ranked queue <2v2|3v3|4v4|5v5> [teammates...] leader: pick who plays
/ranked accept [request]                       agree to play / accept a found match
/ranked decline [request]                      refuse
/ranked leave                                  withdraw your team's entry (picked player or leader)
/ranked status                                 where your team stands
/ranked challenge <team> <size> [teammates...] leader
/ranked challenge accept <team> [teammates...] leader of the challenged team
/ranked challenge decline <team>
/ranked challenge cancel
/ranked challenges
```

`joshymc.lifesteal.ranked.use` (default true). Scope `LIFESTEAL`; the command is only registered
on a server that hosts Lifesteal (released elsewhere) and refuses outside Lifesteal worlds.
Teams literally named `accept`, `decline` or `cancel` can be challenged from the menu.

Staff (`joshymc.lifesteal.ranked.admin`), next to the existing match tools:

```
/lifesteal admin ranked queue                         queues, entries (state, roster, age, pause reason; possibly stuck in red), proposals, challenges, reserved arenas, cooldowns
/lifesteal admin ranked cancelqueue <entry|team> [confirm]
/lifesteal admin ranked cancelproposal <id> [confirm] ends the found match and both entries
/lifesteal admin ranked queuelog                      last 15 audit rows
```

Staff cancels are logged and audited. They never change inventories or hearts; a match that
already exists is cancelled with the existing `/lifesteal admin ranked cancel`.

## Settings (`lifesteal.yml > ranked-team-wars.matchmaking`)

| Key | Default | Range |
|---|---|---|
| `enabled` | `true` | |
| `consent-seconds` | 60 | 10-600 |
| `confirm-seconds` | 30 | 10-120 |
| `queue-timeout-minutes` | 30 | 1-240 |
| `requeue-cooldown-seconds` | 15 | 0-600 |
| `decline-cooldown-seconds` | 60 | 0-1800 |
| `challenge-expire-seconds` | 120 | 30-900 |
| `challenge-cooldown-seconds` | 60 | 0-3600 |

There was no existing confirmation timeout, so `confirm-seconds` is new. A file without the section
loads with the defaults and one warning; a bad value makes lifesteal.yml invalid (never guessed).

## Future Elo

`LifestealRankedQueueManager.selector` is an `OpponentSelector`; a rating-based selector replaces
`FirstInFirstOut` without changing the queue, confirmation or start.

## Java and Bedrock

Everything is keyed by UUID (Floodgate ids included), mixed teams work, menus are plain chests
(Geyser shows them), and Accept/Decline exist as menu buttons because Bedrock can't click chat.
No Geyser, Floodgate or proxy configuration and no packet code.

## Checks

```
./gradlew -I scripts/lifesteal-ranked-queue-check.init.gradle.kts lifestealRankedQueueCheck
```

## Manual tests (need a live Paper server)

Not verified by compilation or the check script:

1. Before Day 3 (`/lifesteal progress`): `/ranked queue 2v2` says it hasn't unlocked.
2. Leader queues 2v2 with one teammate: the teammate gets the prompt; Agree → "in the queue".
3. A second team does the same: both get the Match Found title; all four accept → arena prepares,
   countdown, fight; inventories restored afterwards; hearts move as in any Lifesteal kill.
4. Decline at the confirmation: the decliner's team is out (cooldown), the other team is back in
   the queue and is matched first by the next team.
5. Let the confirmation time out; disconnect while confirming; get combat-tagged while waiting
   (paused message) and try to accept while tagged.
6. Kick a queued player from the team: the entry ends within a second.
7. 3v3, 4v4, 5v5 with an arena for each size; a 5-member team playing 2v2.
8. Challenge flow: send, accept with players, all agree, confirm, match; also decline, expiry and
   a second challenge within the cooldown.
9. A Bedrock player in a mixed team uses only the menu (picker, Accept/Decline).
10. Restart with teams queued: queues are empty afterwards and nobody's items or hearts changed.
11. Survival server: `/ranked` doesn't exist (or belongs to another plugin).
