# Ranked leaderboards, divisions and statistics (Lifesteal)

Issue #1367. Builds on the Elo service of issue #1365 ([lifesteal-ranked-elo.md](lifesteal-ranked-elo.md)).
Lifesteal only: on a server that doesn't host Lifesteal nothing here runs, no index is created and
`/ranked` isn't registered.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/ranked/stats/`.

| File | What |
|---|---|
| `RankedDivisions.kt` | `Division`, `DivisionTable` (lookup, progress, promotion/demotion) and `DivisionConfig` (parses `divisions` and `leaderboards`). Bukkit-free. |
| `RankedStatistics.kt` | Statistics computed from rating history (wins, losses, win rate, streaks, peak/lowest, gained/lost). Bukkit-free. |
| `RankedStatsStore.kt` | Read-only SQL: leaderboard pages, positions, history points, match history, consistency check. Bukkit-free. |
| `LifestealRankedStatsManager.kt` | Async loads, bounded page cache, division display, promotion/demotion notice lines. |
| `RankedStatsMenu.kt`, `RankedMenuLayout.kt` | The menus and their slot layout. |
| `RankedStatsCommand.kt` | The chat views of `/ranked`. |
| `RankedStatsAdminCommand.kt` | `/lifesteal admin ranked stats ...`. |

## What the blueprint says

Blueprint v1.1 requires separate Player Elo and Team Elo, but names **no divisions, thresholds or
leaderboard rules**. Everything below that isn't an Elo value is therefore a proposed default in
`lifesteal.yml`, not an approved gameplay decision: the seven division names and thresholds
(Bronze 0, Silver 800, Gold 1000, Platinum 1200, Diamond 1400, Master 1600, Grandmaster 1800, the
issue's suggestion), and the 5-match leaderboard minimum.

## Where the numbers come from

Nothing new is stored. There is no statistics table and no division column:

- **Leaderboards** read `lifesteal_ranked_ratings` (the current rating per season, type and subject).
- **Statistics** (wins, losses, draws, win rate, peak, lowest, gained, lost, current and highest win
  streak) are computed from `lifesteal_ranked_rating_history`, the append-only rows the rating
  transaction writes (one per match per player/team, UNIQUE). So only committed, rated matches count:
  cancelled matches, staff test matches, invalid records and unrated challenges never get history
  rows. A rated challenge (`elo.rate-challenges: true`) counts like any rated match. A match can't be
  counted twice, and there is nothing to drift or rebuild after a crash or restart.
- **Match history** reads the match records (`lifesteal_ranked_matches`, `_participants`, `_arenas`)
  joined with the rating result and history. It lists every COMPLETED match that isn't a staff test
  and isn't rated INVALID, newest first, every season. Unrated ones are marked *Unrated* (with the
  reason), completed ones whose rating hasn't committed yet are marked *Rating pending*. Cancelled
  matches have no result and aren't listed. Draws aren't produced by the match system, so none are
  shown (the statistics would count them if they were ever recorded).
- **Divisions** are computed from the current rating every time they're shown.

Not shown, because nothing records them reliably per match: ranked kills/deaths and hearts won/lost
in ranked matches. They aren't estimated.

Statistics and leaderboards are per season (the season the progression service reports). Older
seasons' rows and history are never changed or deleted.

## Leaderboards

`/ranked top [players|teams] [page]` (10 per page in chat, 28 in the menu).

- Order, everywhere: rating DESC, then wins DESC, then the earlier last rated match (who got there
  first), then the UUID / team id. Deterministic; positions come from the same comparison, so a
  player's "#N" always matches the page they're on.
- Listed only with at least `leaderboards.minimum-player-matches` / `minimum-team-matches` rated
  matches this season (default 5), so an untouched initial rating can't top the board. Their rating
  is still shown in `/ranked stats`.
- Players are keyed by UUID (Floodgate UUIDs for Bedrock players) and named at display time, so a
  name change keeps the place. Offline players are listed like anyone else.
- Teams are keyed by their stable team id and shown with their current name and leader. Only ACTIVE
  teams are ranked; a disbanded team keeps its rows and history (staff can inspect it by id) but is
  never listed. A new team that takes an old name has a new id and starts from nothing.
- Player and team boards are separate tables of separate ratings.
- Only one page is read per request (indexed `LIMIT/OFFSET`). Pages are cached up to
  `leaderboards.cache-seconds` (default 30, bounded to 64 pages) and the whole cache is dropped the
  moment any rating transaction commits; a page read before that commit is never cached.

## Divisions

```yaml
ranked-team-wars:
  divisions:
    enabled: true
    tiers:
      gold:
        display-name: "Gold"     # plain text
        color: gold              # #RRGGBB or a colour name
        minimum-elo: 1000        # Player Elo threshold
        team-minimum-elo: 1000   # optional; Team Elo threshold, defaults to minimum-elo
        icon: GOLD_INGOT         # optional item, menus only (no resource pack assets)
        description: "Holding your own."
```

Validation (any failure turns divisions **off** with the problem in the console; it never pauses
hearts or anything else, and nothing is guessed): 1 to 14 divisions, ids `a-z0-9_-`, unique plain
names up to 24 characters, valid colours, thresholds 0 to 100000, **strictly increasing** player
thresholds and team thresholds in the same order (no two divisions share a boundary), and the
lowest division must start at or below `elo.minimum-rating` so every rating has one. Tiers listed
out of order are used in threshold order (a warning). `enabled: false` hides divisions everywhere.
A lifesteal.yml from before this issue has no `divisions` / `leaderboards` section: the bundled
file's section is used and a warning is logged.

A player's division always comes from their Player Elo and a team's from its Team Elo, with their
own thresholds; one is never substituted for the other. Divisions are titles only: no powers, no
rewards. Changing thresholds changes no rating and needs no migration.

**Promotion / demotion notices** are extra lines on the match result message the rating service
already sends once per committed change (also on the next join when the player was offline):
`RANKED PROMOTION! You have reached Diamond Division!` or
`Your ranked division has changed to Gold.`, plus a line for the team's division when it changed.
They're never broadcast and never sent for an uncommitted change.

## Player commands and menu

| Command | What |
|---|---|
| `/ranked` | The menu (now 45 slots: the queue row, challenges, confirmations and Leave are where they were; row 4 has Your Statistics, Team Statistics, Match History, Current Season, Ranked Divisions, Player Leaderboard, Team Leaderboard). |
| `/ranked stats [player]` | Player Elo, division and progress, Team Elo, ranking, record, Elo range, streaks, recent matches. |
| `/ranked teamstats [team]` | The same for an active team (own team by default). |
| `/ranked top [players\|teams] [page]` | Leaderboards. |
| `/ranked history [player] [page]` | Match history. |
| `/ranked divisions`, `/ranked season` | Division table with your progress; the current season. |

Others' statistics/history need `joshymc.lifesteal.ranked.stats.others` (default true); team
statistics are public. Names are resolved through `PlayerResolver` (no Mojang lookups).

The menus are plain chests (GuiManager: clicks on the menu are cancelled, nothing moves in or out),
vanilla items only, so Bedrock players get the same chests through Geyser. Every view is loaded off
the main thread first and opened when the data is there; every click re-checks the permission and
the Lifesteal server and loads fresh data. No button changes anything.

The season view shows the season id, launch date, progression phase, ranked status and the
configured season length. No end date is shown: season rollover doesn't exist yet and no date is
invented.

## Staff

`/lifesteal admin ranked stats ...` (`joshymc.lifesteal.ranked.admin`, op), read-only:

- `player <player>` / `team <team|team id>`: statistics, division and leaderboard eligibility. A
  disbanded team by its id.
- `consistency [season]`: compares every rating row with its history (counts and latest value),
  history without a RATED result of a completed non-test match, and RATED results without history.
  Logged. It repairs nothing: statistics are derived from history, so there's nothing to rebuild,
  and ratings can only change through the rating transaction (database triggers). A reported problem
  is for staff to investigate.
- `divisions`: the loaded table and any configuration problem.

There is no command that sets or awards a rating, division or statistic.

## Failure handling

- Database errors: the view says the statistics are unavailable right now and the console has the
  error; nothing is cached or made up.
- No active season: "no ranked statistics yet".
- Missing ratings: the initial rating, marked "no rated matches yet".
- Invalid lifesteal.yml Elo section: ratings (and so statistics) are unavailable, as in #1365.
- Invalid divisions/leaderboards: only those are off.

## Checks

```bash
./gradlew -I scripts/lifesteal-ranked-stats-check.init.gradle.kts lifestealRankedStatsCheck
```

Covers the division config, lookup, progress, promotion/demotion, statistics, paging, menu
layouts and, against a real SQLite database with the real match/rating/team stores, leaderboard
order, ties, pagination, positions, eligibility, offline and Bedrock players, disbanded and
recreated teams, membership and leadership changes, excluded matches, history paging, seasons,
the consistency check, database failures and the wiring.

## Manual tests (live Paper server, Java and Bedrock)

Not covered by the checks; compiling proves nothing about clients.

1. On a Lifesteal server with a launched season past Day 3, play a few public matches between
   teams with a Java and a Bedrock player. Open `/ranked` on both clients: the 45-slot menu shows,
   the queue buttons still work, Accept/Decline appear for a found match.
2. Open each statistics button: values match `/ranked stats`; nothing can be taken out of any menu
   (shift-click, number keys, drag) on either client.
3. Leaderboards: page arrows, the Player/Team switch, your entry highlighted, clicking an entry opens
   that player's or team's statistics.
4. Cross a division threshold: the promotion line appears once with the match result; log out
   before a rating commits and check it appears on rejoin, once.
5. Disband a ranked team: it leaves the team board; `/lifesteal admin ranked stats team <id>`
   still shows its record.
6. `/ranked history` paging in chat and the menu; an unrated challenge shows as unrated.
7. Break `divisions` in lifesteal.yml and `/joshymc reload`: divisions show "-"/unavailable,
   everything else keeps working; fix it and reload.
8. On a Survival server: `/ranked` doesn't exist and nothing above appears.
