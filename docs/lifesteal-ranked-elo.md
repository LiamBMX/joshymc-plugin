# Lifesteal Ranked Team Wars: Player Elo and Team Elo

Issue #1365. Lifesteal only. Code: `src/main/kotlin/com/liam/joshymc/lifesteal/ranked/rating/`.

Two separate ratings, both per season:

- **Player Elo**: a player's own rating, keyed by their UUID (Floodgate UUIDs for Bedrock players;
  names are never stored as identity). Joining, leaving or switching teams never changes it.
- **Team Elo**: a Lifesteal team's rating, keyed by the team's stable id (`lifesteal_teams.id`).
  It is never computed from its members' ratings, and joins, leaves, kicks and leader transfers
  never change it.

Both change only after an eligible Ranked Team Wars match has been recorded COMPLETED. There are
no ranked rewards, divisions, leaderboards, season resets or rollovers in this issue.

## Dependencies (verified merged on `main` before this change)

| Needed | Issue | Status |
|---|---|---|
| Persistent Lifesteal teams with stable ids | #1346 | merged |
| Launch progression, seasons (`lifesteal_seasons`) and the Day 3 unlock | #1352 | merged |
| Ranked arenas, match lifecycle, match records (`lifesteal_ranked_matches` / `_participants`) | #1361 | merged |
| Public matchmaking, challenges, the match `kind` column | #1363 | merged |

Nothing was duplicated: ratings read the existing match records, teams and season; the queue,
lifecycle and team code are unchanged apart from the hand-over call and the menu item.

## Formula (`EloRating`, the only place Elo is calculated)

```
Expected  E     = 1 / (1 + 10^((Opponent - Own) / 400))
Change    Delta = K x (Actual - E)        Actual: win 1, loss 0, draw 0.5
New             = Own + Delta             kept inside minimum-rating..maximum-rating
```

- Delta is rounded half away from zero to a whole number, so a win and the matching loss always
  move by the same amount (15.5 → 16, -15.5 → -16).
- When the bounds cut a change, the change actually applied is what is stored.
- Ratings are integers up to 100000 (`LifestealConfigParser.MAX_ELO_RATING`); every value is
  checked finite, so NaN, Infinity and overflow can't reach the database.
- **Player Elo in a team match**: each side's strength is the average pre-match Player Elo of its
  participants. Every participant on a side gets the change computed from the two averages (only
  the bounds clamp can differ). Kills and deaths don't count. Example: 1000/1100/1200 (avg 1100)
  beat 900/1000/1100 (avg 1000): every winner +12, every loser -12.
- **Team Elo**: the two teams' own pre-match Team Elo, standard formula.
- **Draws**: the formula supports 0.5, but no ranked rule produces a draw. Two sides running out of
  players at the same time cancels the match (#1361), so it is unrated.

## Settings (`lifesteal.yml > ranked-team-wars.elo`)

The blueprint requires separate individual and team Elo but gives no numbers, so these are the
issue's **proposed** defaults, not approved blueprint values:

| Key | Default | Range | Meaning |
|---|---|---|---|
| `initial-player-rating` | 1000 | min..max | Player Elo before a player's first rated match of a season |
| `initial-team-rating` | 1000 | min..max | Team Elo before a team's first rated match of a season |
| `minimum-rating` | 0 | 0..99999 | floor |
| `maximum-rating` | 5000 | 1..100000 | ceiling |
| `k-factor` | 32 | 1..200 | volatility |
| `rate-challenges` | false | | direct team challenges change Elo |
| `same-opponent-limit` | 3 | 0..100 | rated matches between the same two teams per window (0 = off) |
| `same-opponent-window-hours` | 24 | 1..720 | the window |

A file without the section loads with these defaults and one warning; a bad value makes the file
invalid (never guessed). Changing a value never rewrites a stored rating: initial ratings only
apply to a player/team with no row yet in that season, and nothing is ever reset automatically.

## What gets rated (`RatingRules`)

| Match `kind` | Classification | Rated |
|---|---|---|
| `PUBLIC` (`/ranked queue`) | RANKED_MATCHMAKING | yes |
| `CHALLENGE` (`/ranked challenge`) | DIRECT_TEAM_CHALLENGE | only with `rate-challenges: true` |
| `TEST` (`/lifesteal admin ranked testmatch`) | ADMIN_TEST_MATCH | never |

`kind` is written by the server code path that created the match; players can't choose it.

Before rating, the stored record is checked: state COMPLETED with outcome WIN, a winner side whose
team id is the recorded winner team, two different team ids, size 2..5, exactly `size`
participants per side, each recorded for their side's team. A record that fails this is
**INVALID**: nothing changes, it is logged as severe and listed for staff, and it is never guessed
or retried. Cancelled matches, matches without a season, test matches, unrated challenges and
matches over the same-opponent limit are **UNRATED** (final, with the reason). Matches that aren't
over are not touched.

## Transactions and recovery (`RatingStore.finalize`)

One transaction per match, run on the database thread (`databaseManager.async`), never on the
main thread:

1. If the match already has a final result (RATED / UNRATED / INVALID), stop.
2. Re-read the match record and participants; decide (above).
3. Read the pre-match ratings of the season (missing = the initial rating).
4. Calculate every Player Elo and both Team Elo changes.
5. Insert every history row, create the rating rows if needed, update each rating with a
   conditional `UPDATE ... WHERE rating = <pre-match value>`, and write the RATED result row.

Any error rolls back the whole transaction: nobody is rated alone. The attempt is then recorded as
FAILED (with the error) and retried by the sweep (at startup and every 5 minutes) up to 5 times,
after which it waits for `/lifesteal admin ranked rating retry`. A crash between completion and
rating leaves a COMPLETED match without a result, which the sweep finds. The transaction id is
`elo:<match id>` (UNIQUE), history is UNIQUE per (match, rating type, subject), and an in-memory
in-flight set keeps one rating task per match, so duplicate completion events never rate twice.
Matches completed during shutdown are rated after the next start.

## Tables

| Table | Contents |
|---|---|
| `lifesteal_ranked_ratings` | PK (season_id, rating_type, subject_id): rating, wins, losses, draws, matches, team name (TEAM_ELO), last match. Index (season, type, rating DESC) for future leaderboards. |
| `lifesteal_ranked_rating_history` | Append-only: transaction id, match, season, type, subject, team, side, previous, delta, new, outcome, both strengths, expected score, K, time, `notified`. |
| `lifesteal_ranked_rating_results` | PK match id, UNIQUE transaction id: RATED / UNRATED / INVALID / FAILED, reason, attempts, last error, the team pair and end time (same-opponent limit). |

Triggers make the history append-only (only `notified` may change), refuse deleting ratings, and
refuse any change of a rating that isn't the `new` value of a history row of the same match,
subject and season starting from the old value. No other system can overwrite a rating.

Only created where Lifesteal is hosted.

## Anti-abuse

- **Test matches**: never rated. **Challenges**: unrated by default (two friendly teams could farm).
- **Repeated rematches**: `same-opponent-limit` per `same-opponent-window-hours`, counted on RATED
  results of the same pair in either order and the same season.
- **Repeated rosters (issue #1499)**: the same limit also applies by **player UUID**, so it can't be
  reset by disbanding and recreating a team, renaming it, or swapping a minority of the roster.
  Inside the finalize transaction, `RatingStore.repeatedRosterMatches` reads the RATED matches of
  the season inside the window that share a player with this one (from the stored
  `lifesteal_ranked_participants` history, which is never deleted with a team and survives
  restarts) and counts those whose rosters are the same opponents by `RatingRules.sameOpponents`:

  > With the sides lined up either way (A with A and B with B, or A with B and B with A), each side
  > of this match shares at least half (rounded up) of the smaller of the two lined-up rosters, **and**
  > at least two opposing player pairs met as opponents again (shared on one side × shared on the
  > other ≥ 2).

  Examples: the same players under a new team id = same; 2v2 with one player replaced (3 of 4 the
  same) = same; 3v3 or 4v4 keeping two of each side = same; 5v5 keeping three of each side = same;
  one player of each side met before (a single incidental opponent pair) = **not** the same;
  unrelated teams = not the same. A match is UNRATED when **either** the team-pair count or the
  roster count reaches `same-opponent-limit` (one verdict: no Player Elo and no Team Elo for
  anyone). Only RATED matches count, so the first three stay rated and unrated rematches never
  extend the block. Heart rules and inventory restoration are untouched: hearts still move in an
  unrated match, and the players are told so.
- **Repeated opposing players (issue #1527)**: the roster rule above deliberately ignores a single
  repeated opponent pair, which left one bypass: two players who keep meeting by rotating their
  teammates (and teams) every match. So the same limit also applies per **opposing player pair**:
  `RatingRules.Limit.PLAYER_PAIR`, counted inside the finalize transaction by
  `RatingStore.mostRepeatedOpponentPair` from the same stored participant history (RATED matches
  of the season inside the window in which one player of each side of this match stood on opposite
  sides, whatever their team ids, teammates or match size). Reaching `same-opponent-limit` makes
  the match UNRATED for everyone, like the other two limits. Players who were only teammates never
  count, and without a team change two players only meet as their two teams, which the team-pair
  limit already caps, so in practice this only adds a limit after somebody changed teams. It never
  blocks queueing or a match; hearts still move.
- **The window**: an earlier match counts when it ended at or after this match's end minus
  `same-opponent-window-hours` (wall-clock epoch milliseconds stored in the database, so it is the
  same after a restart). Matches that ended later count too: a match whose rating is retried after
  newer ones were rated is judged against them, never rated past the limit.
- **Disbanding / recreating / renaming**: Team Elo is keyed by the team id; a new team (even with
  a reused name) gets a new id and the initial rating. A disbanded team's rows and history stay for
  audits (`/lifesteal admin ranked rating team <id>`); it is no longer an active team. Its players'
  earlier rated matches still count toward the repeated-roster limit above.
- **One match per team (issue #1499, A-1)**: a team plays at most one ranked match at a time,
  whichever members it would send. Checked when queueing, challenging, answering a challenge,
  agreeing, accepting a found match, when matchmaking proposes and right before the match is
  created (`LifestealRankedManager.teamMatch`, read from the open matches, and
  `RankedMatchRules.validate`'s `busyTeams` for every kind incl. TEST). The database enforces it as
  well: the trigger `trg_lifesteal_ranked_matches_one_per_team` refuses inserting an open match
  for a team that already has one, reading the match rows' own state, so completion, cancellation
  and the startup recovery release the team and no lock can get stuck after a crash.
- **Monitoring**: a match the rating limit made unrated is logged (logging only) as a
  `RATING_LIMIT_REACHED` review (`/lifesteal admin ranked reviews`), one per roster per monitoring
  window, naming any other team ids the earlier matches were played under (possible team
  recreation). Repeated opponent UUID pairs were already logged (`REPEATED_PLAYER_MATCHUP`).
- **Leaving a team after a loss / leadership transfer**: Player Elo follows the player, Team Elo
  stays with the team; neither changes.
- **Disconnects / forfeits**: a disconnect in the fight is an ordinary "out" in the match (#1361),
  so the disconnected player's side is rated like any other result. Quitting can't dodge a loss.
  There is no heart penalty or other punishment.
- **Duplicate results**: see "Transactions".
- **Alternate accounts**: no IP linking. Only players who accepted the match (and passed every
  participant rule) are rated, and the same-opponent limit bounds farming between two teams.
- **No Elo modification command exists** (not even for staff).

## Commands

| Command | Who | What |
|---|---|---|
| `/ranked stats [player]` | `joshymc.lifesteal.ranked.use` (+ `.stats.others` for others, default true) | Player Elo, Team Elo, season, matches, wins, losses, win rate, recent changes |
| `/ranked rating` | `.use` | Player Elo and Team Elo |
| `/ranked history [player]` | `.use` (+ `.stats.others`) | last 10 Player Elo changes this season |
| `/lifesteal admin ranked rating status` | `joshymc.lifesteal.ranked.admin` | settings, result counts, completed matches without a final result |
| `... rating player <player>` / `team <name\|id>` | admin | every season's row + history |
| `... rating match <match>` | admin | the result, transaction, every change with expected score and strengths |
| `... rating failed` | admin | FAILED and INVALID results |
| `... rating retry <match> confirm` | admin | re-runs the normal idempotent rating (no effect on final results) |

They are subcommands of the existing `/ranked` and `/lifesteal` (no new command, no new alias).
Player lookups use `PlayerResolver` (never `Bukkit.getOfflinePlayer(String)`); every read runs off
the main thread. Players without ratings see the initial rating marked "no rated matches yet";
players without a team see "not in a team".

## Menu and result messages

`/ranked` loads the player's profile asynchronously, then opens the existing menu with a new
**Your Ranked Rating** item (slot 4): season, Player Elo, Team Elo, wins/losses, last three
changes. Clicking it shows `/ranked stats`. Everything else in the menu is unchanged. Profiles are
cached (at most 256, 5 minutes, dropped on quit, team change or a new rating).

After a match is rated, each participant gets one message once it is committed:

```
Ranked Team Wars | Match Complete
Winner: Alpha  Loser: Bravo  (3v3)
Your Player Elo: 1012 (+12)
Alpha Team Elo: 1016 (+16)
```

Offline players get it on their next join (the history row's `notified` flag is claimed once).
Unrated, non-test matches tell the participants why Elo didn't change. Uncommitted values are
never shown.

## Java and Bedrock

Everything is keyed by UUID (Floodgate's for Bedrock), mixed teams work the same, the menu is a
plain chest (Geyser shows it), messages are plain chat. No Geyser/Floodgate/Velocity changes, no
packet code.

## Survival isolation

Survival servers create no rating table, run no task and don't register `/ranked` (unchanged
since #1363). The rating code never touches Survival teams, economy, inventories, PvP statistics or
leaderboards.

## Checks

```
./gradlew -I scripts/lifesteal-ranked-elo-check.init.gradle.kts lifestealRankedEloCheck
```

Covers the formula (expected scores, rounding, bounds, invalid inputs, draws), 2v2-5v5 averages,
Team Elo, eligibility (cancelled, test, challenge, invalid records, same-opponent limit), and on a
real SQLite database: initial ratings, duplicate finalization, a simulated database failure in the
middle of the transaction (full rollback, FAILED, retry), the protection triggers, season
isolation, membership changes, disbanded/renamed teams, offline and Bedrock players, notices,
restart; plus lifesteal.yml parsing and the wiring.

## Manual tests (need a live Paper server)

1. Two teams, `/ranked queue 2v2` with both, play it out: both teams see "Match Complete" with
   their changes; `/ranked stats` shows them; `/lifesteal admin ranked rating match <id>` shows
   RATED and six history rows.
2. Repeat with a 3v3 mixing Java and Bedrock players: the Bedrock player gets the same message and
   menu item.
3. `/ranked challenge` match: participants are told it didn't change Elo; status UNRATED.
4. `/lifesteal admin ranked testmatch`: no Elo message, UNRATED.
5. Log one loser out before the match ends: on rejoin they get the result message once.
6. Restart the server right after a match ends (`/stop` during FINISHING): after the start, the
   sweep rates it (check `rating status`).
7. Stop the database mid-rating isn't practical live; covered by the check script.
8. Kick a member from a rated team and invite a new one: Team Elo unchanged in `/ranked stats`.
9. Disband a rated team, recreate it with the same name: it starts at the initial Team Elo; the old
   id still shows its history under `rating team <old id>`.
10. Play the same two teams four times within 24 hours: the fourth is UNRATED (same-opponent).
    Then disband one team, recreate it (same or another name) with the same players and play
    again: UNRATED ("repeated opponent roster limit reached"), both teams see the same line with the
    heart reminder, and `/lifesteal admin ranked reviews` shows a `RATING_LIMIT_REACHED` entry.
11. (#1499) With a 4-member team in a 2v2 match, try `/ranked queue 2` with the other two members,
    challenge another team, and answer a challenge: all refused ("already playing a ranked match").
    The same while the team is in a staff TEST match. After the match ends (or is cancelled, or the
    server restarts) the team can queue again.
12. On the Survival server: `/ranked` doesn't exist and no `lifesteal_ranked_rating*` table exists.
