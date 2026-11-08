# Lifesteal profiles, statistics, achievements and leaderboards (issue #1429)

Lifesteal only. Everything lives in `com.liam.joshymc.lifesteal.profile` and does nothing on a
server that doesn't host Lifesteal: no tables, no listener, and `/profile`, `/stats`,
`/achievements` and `/lstop` are released there (unregistered), so Survival never has them.
Survival profiles, leaderboards (`/killtop`, `/playtimetop`, leaderboard holograms, ...) and
statistics are untouched.

## Commands and permissions

| Command | What | Permission (default) |
|---|---|---|
| `/profile [player]` (`/lsprofile`) | Profile GUI (yours or another player's, also offline) | `joshymc.lifesteal.profile.use` (true); others: `joshymc.lifesteal.profile.others` (true) |
| `/profile private <on\|off>` | Hide your profile from other players | same |
| `/stats [player]` | Chat summary (works from the console) | same |
| `/achievements [category]` (`/ach`) | Achievements GUI | `joshymc.lifesteal.achievements.use` (true) |
| `/achievements admin status\|player <name>\|recover` | Read-only diagnostics; re-send interrupted claims | `joshymc.lifesteal.profile.admin` (op) |
| `/lstop [category] [season\|alltime] [page]` (`/lifestealtop`) | Leaderboards GUI (chat from the console) | `joshymc.lifesteal.leaderboards.use` (true) |

Private profiles are opened anyway with `joshymc.lifesteal.profile.private.bypass` (op). All four
commands are `LIFESTEAL` scoped in `GamemodeAssignments` and refuse outside Lifesteal worlds.
There is **no** command that edits statistics or achievements.

## What already existed and is reused

Nothing is counted twice and no existing table is written to.

| Number | Source (existing system) |
|---|---|
| Hearts | `lifesteal_hearts` (heart system, #1341) |
| PvP kills, deaths, K/D, streaks, unique victims | `lifesteal_heart_transactions` type `PVP` (#1341): every attributable Lifesteal PvP death, evaluated once (UNIQUE dedupe key per death), with the heart system's own verdict |
| Seasons | `lifesteal_seasons` (progression, #1352) and its live season (`lifestealProgressionManager.progress().season`) |
| Ranked matches, wins, peak Player/Team Elo | `lifesteal_ranked_rating_history` (#1365, only committed rated matches) |
| Current Elo, divisions, Elo leaderboards | `lifesteal_ranked_ratings`, `DivisionTable` and `LifestealRankedStatsManager.loadLeaderboard` (#1367); the Elo boards in `/lstop` *are* the Ranked Team Wars leaderboards |
| Team, team history | `LifestealTeamManager` cache, `lifesteal_team_members` / `lifesteal_team_membership_history` (#1346) |
| Bounties claimed | `lifesteal_bounty_payouts` (#1401) |
| Alt accounts | AltManager's `player_connections` (shared connection hashes) |
| Playtime | `PlaytimeManager` (`playtime`), **only on a Lifesteal-only server** |
| Rank | `RankManager.getPlayerRankById` |
| Reward delivery | `PurchaseFulfillmentManager.enqueue` (#1175/#1199): Coins/Credits offline, items when there is room, never dropped, FAILED/INTERRUPTED recovery |
| Chat tags, crate keys | existing chat tag vouchers and crates, as reward kinds of the delivery queue |
| Natural-block detection | `PlacedBlockTracker` (Milestones, #1420), with its own chunk key `joshymc:lifesteal_profile_placed`; `HarvestRules` (#1374), `MiningRules`, `FishingRules`, `RateWindow` |
| GUIs | `CustomGui` / `GuiStyle` / `GuiManager` (cancelled clicks, blocked shift/number-key/double-click moves, click cooldown), heads via `PlayerHeadManager` |

New tables (all in the Lifesteal server's `data.db`, `ProfileStore`):
`lifesteal_profile_counters`, `lifesteal_profile_dimensions`, `lifesteal_achievements`,
`lifesteal_profile_settings`, `lifesteal_achievement_audit`. It also adds two indexes on
`lifesteal_heart_transactions` (killer, victim) for per-player reads.

## Which PvP counts (anti-farming)

A PvP death counts only when the heart system recorded it as a real fight: outcome
`TRANSFERRED`, `VICTIM_LOST_ONLY`, `VICTIM_AT_MINIMUM` or `KILLER_AT_MAXIMUM`. Teammates,
allies, unknown teams, the heart cooldown, before launch / grace period / unknown progression,
and missing records never count (`PvpCounting`). On top of that:

- **Alt boosting**: fights between two accounts that ever shared a connection count for nothing,
  in either direction.
- **Same victim**: a kill of the same player counts at most once per
  `pvp.same-victim-cooldown-minutes` (default 60). The heart system's own cooldown only covers
  kills that moved a heart, so killing a player on their last heart, or while at the maximum,
  would otherwise be farmable.
- **Repeated events**: one death is one row (UNIQUE dedupe key upstream), so reprocessing can't
  inflate anything. Ranked match kills, hosted events and Combat NPC deaths follow whatever the
  heart system recorded for them.
- Kill streak = counted kills in a row with no counted PvP death in between. Only PvP deaths are
  recorded by the heart system, so environmental deaths don't end a streak.
- Achievements never reward deaths (`pvp-deaths` can't be an achievement stat) and never touch
  hearts.

## Seasons

Seasonal numbers are **re-derived from history inside the season's window every time**: a season
runs from its launch to the next recorded season's launch; the ACTIVE one is open-ended. Nothing
is ever reset, so a season transition preserves every record and permanent achievement by
construction, and finished seasons' numbers stay exactly as they were ("Previous Seasons" on the
profile). Activity counters are stored per season id (`preseason` before any season exists).
Seasonal achievements are keyed by season (`period` = season id), so each can be earned once per
season and a new season starts them over; permanent ones use `permanent`.

JoshyMC has **no automatic season rollover yet** (seasons are 3 months per `lifesteal.yml >
seasons.length-months`, but nothing closes a season). When staff start the next season, the old
one becomes a previous season here automatically.

## Achievements

Defined in `lifesteal-profiles.yml > achievements` (stable id = the key; tiers are numbered by
position, so only append tiers). Each has name, description, category (combat, survival, social,
progression, competitive), stat, `type` permanent/seasonal, `visibility` visible/hidden, icon,
`broadcast`, and 1-10 tiers (optional name like Bronze, target or `division: <id>`, up to 5
rewards). Broken entries are logged and left out; the rest still load.

Stats: `pvp-kills`, `unique-victims`, `best-kill-streak`, `bounty-claims`, `ranked-matches`,
`ranked-wins`, `player-division`, `team-division`, `team-joined`, `ores-mined`,
`crops-harvested`, `fish-caught`, `mobs-killed`, `dimensions-explored`, `playtime-hours`.
Gathering stats are counted by `LifestealProfileListener` (MONITOR, ignoreCancelled, Lifesteal
worlds, Survival game mode): natural ores with the right tool, grown crops / unplaced
self-dropping crops, direct kills of hostile mobs with a natural spawn reason (no spawners,
eggs, portals, event mobs; per-minute cap), vanilla fish with a minimum interval, dimensions
entered. They are buffered and written every 30s, on quit and at shutdown.

Rewards: `coins` (only on a Lifesteal-only server; elsewhere the line is left out and logged),
`credits`, `tokens`, `cratekey <crate> <n>`, `chattagvoucher <tag> <n>`, `title <text>`,
`border <colour>`. Ranks, rank vouchers, hearts and anything else are refused by the parser.

**Claims** (`LifestealProfileManager.claim`): the row goes COMPLETED → CLAIMING with a
conditional UPDATE (one winner among double clicks, two menus, concurrent requests) and the
tier's rewards are snapshotted on it. Each reward is queued with reference
`ach:<id>:<tier>:<period>:<index>` (UNIQUE with the recipient in the delivery queue), then the row
becomes CLAIMED. A crash in between leaves it CLAIMING; the 1-minute recovery (and
`/achievements admin recover`) re-queues the snapshot by the same references, which finds the
existing deliveries instead of paying again. Triggers refuse any backwards move, any edit of the
completion facts and every delete.

## Leaderboards

`/lstop`: PvP kills, highest kill streak, K/D (needs `kd-minimum-fights` kills + deaths), bounties
claimed, achievements completed, playtime (all time, Lifesteal-only servers), Player Elo and Team
Elo (the existing Ranked boards). Current season and all time, 21 per page, the viewer's own
placement, offline players included, ties share a place. The non-Elo boards are rebuilt from one
read of each source every `refresh-seconds` (default 300) off the main thread. **Quests completed
is not offered**: Daily/Weekly quests are a Survival-only system (`GamemodeFeature.QUESTS`), so
Lifesteal has no quest data.

## Profile customization

Title (from claimed achievement tiers with a `title` reward), border (vanilla stained glass,
unlocked by `border` rewards; the default border is free), up to three featured achievements
(badges: any completed tier), and privacy. Every choice is re-checked against the database when
saved. No resource-pack assets are used. Titles show on the profile only (not in chat).

## Not implemented (and why)

- **Daily/Weekly quest achievements and quest leaderboards**: no Lifesteal quest system exists.
- **"Reach progression milestones" / "unlock eligible ranks"**: launch milestones are server-wide
  (not earned by a player), and ranks are bought or staff-given, so rewarding them would not be a
  legitimate achievement. `squad-up` / `ranked-*` cover team and competitive progression.
- **Cooperative objectives**: no Lifesteal system records them yet; add a stat when one does.

## Manual tests

1. On the Lifesteal server, `/profile`, `/stats`, `/profile <offline player>`; check hearts, kills
   and Elo against `/lifesteal hearts get`, `/ranked stats`.
2. Kill a player twice within an hour: kills +1 only; a teammate/ally kill and a grace-period kill
   don't count.
3. Mine a natural ore (counts) and a placed one (doesn't); fish; kill a spawner mob (doesn't).
4. Complete an achievement, claim it twice quickly (one reward), stop the server mid-claim and
   check `/achievements admin recover` pays once.
5. `/profile private on`, then open it from another account (refused) and as op (allowed).
6. Set `enabled: false`, `/joshymc reload`: commands refuse; set it back: all data is still there.

Checks: `./gradlew -I scripts/lifesteal-profile-check.init.gradle.kts lifestealProfileCheck`.
