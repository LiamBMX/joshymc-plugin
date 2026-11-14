# Ranked Team Wars: QA test matrix (issue #1531)

Companion to [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md) (setup, accounts, arenas, evidence)
and [lifesteal-ranked-release-gate.md](lifesteal-ranked-release-gate.md). Run on the **test
server only**.

## How to use this matrix

- **Expected** is what the current code on `main` does, read from the source (file/issue in
  **Ref**). Wording in quotes is the actual message text; other results are described.
- **Status** starts as `NOT RUN`. Set it to one of:
  - `PASS`: you ran it, the result matched, and the evidence is linked;
  - `FAIL`: it didn't match (link the bug report);
  - `BLOCKED`: it couldn't be run (say why: missing accounts, a crash in an earlier step...);
  - `PENDING #1530`: depends on an unmerged PR; don't run it until that PR merges, then judge it
    by the merged behavior;
  - `N/A`: the feature doesn't exist (say so; never invent it).
- **Never mark PASS without evidence** (screenshot, video, command output or log excerpt). A
  passing automated suite in **Ref** is not live evidence on its own.
- **Actual / evidence**: what happened and a link. For a FAIL, the bug report link.
- Copy this file (or the tables you're running) into the session log rather than editing it in
  the repository; the repository copy stays the blank template.

Common preconditions unless a test says otherwise: an ACTIVE season with ranked unlocked
(`/lifesteal progress`), READY arenas for the size, teams of test players in the Lifesteal world,
nobody combat-tagged, every tester with a recorded inventory and heart count. "TEST match" =
`/lifesteal admin ranked testmatch ...` + confirm + every player accepts. "PUBLIC match" = both
leaders `/ranked queue <size> ...`, everyone agrees and accepts (needs
`ranked-team-wars.enabled: true`).

Players column: number of accounts needed (O = an extra outsider, S = a staff account).

---

## A. Matchmaking (MM)

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| MM-01 | **TEST 2v2.** `enabled: false`. Admin runs `/lifesteal admin ranked testmatch <A> <B> 2`, then `/lifesteal admin ranked testmatch confirm <rx-token>`; all 4 run `/ranked accept rt-...`. | 4+S | Preview first (nothing moves); after confirm every player gets the heart-risk warning; once all accept, everyone is teleported in, a `countdown-seconds` (10) countdown with no damage runs, then the fight. Recorded as TEST, never rated (`rating match` = UNRATED "staff test matches are never rated"). | #1491, `lifestealRankedTestMatchCheck` | NOT RUN | |
| MM-02 | **TEST 3v3, 4v4, 5v5.** Repeat MM-01 per size (one arena per size). | 6/8/10+S | Same as MM-01 for every size; the arena chosen hosts that size. | #1361 | NOT RUN | |
| MM-03 | **PUBLIC 2v2.** `enabled: true`. Leader A `/ranked queue 2v2 <mate>`; mate `/ranked accept`. Team B the same. Everyone accepts the found match. | 4 | Each entry FORMING → WAITING once all picked players agree; the 1s tick pairs the two teams when an arena is free; everyone accepts within `confirm-seconds` (30); match starts through the same lifecycle as TEST; kind PUBLIC; rated on completion. | #1363, `lifestealRankedQueueCheck` | NOT RUN | |
| MM-04 | **PUBLIC 3v3 / 4v4.** As MM-03 at 3v3 and 4v4. | 6/8 | As MM-03. | #1363 | NOT RUN | |
| MM-05 | **PUBLIC 5v5.** As MM-03 at 5v5 (teams of 5). | 10 | As MM-03. | #1363 | NOT RUN | |
| MM-06 | **Solo queue.** A player with no team runs `/ranked queue 2v2`. Then try `/ranked queue 1v1` from a leader. | 1 | No team: "Join a Lifesteal team first (/team)." 1v1: "1v1 isn't offered (sizes: 2v2, 3v3, 4v4, 5v5)." | `LifestealRankedQueueManager.startProblem` | NOT RUN | |
| MM-07 | **Non-leader queues.** A team member (not leader) runs `/ranked queue 2v2 <name>`. | 2 | "Only your team leader can queue the team or send challenges." | `startProblem` | NOT RUN | |
| MM-08 | **Team too small.** A 2-member team's leader runs `/ranked queue 3v3`. | 2 | "Your team needs at least 3 members for 3v3 (it has 2)." | `startProblem` | NOT RUN | |
| MM-09 | **Ineligible roster member.** Pick a teammate who is combat-tagged, offline, in Creative, has `/god` on, or isn't in the Lifesteal world. | 2–3 | Registration refused with one line per problem player ("Your team can't queue yet:" + reasons). Nothing is queued. | `rosterProblems`, `participantProblem`, #1498 | NOT RUN | |
| MM-10 | **Ready-check accept / reject (agree step).** Leader queues with 1 teammate; teammate runs `/ranked decline`. Repeat and let it time out. | 2 | Decline ends the entry; the leader is told. With no answer, the entry is dropped after `consent-seconds` (60). Nobody is teleported. | #1363 | NOT RUN | |
| MM-11 | **Found-match accept / reject.** Two teams waiting; one player declines the found match (or ignores it for `confirm-seconds`). | 4 | The proposal ends; the decliner gets the `decline-cooldown-seconds` (60) queue cooldown; the other team keeps its place in the queue. No inventories or hearts touched. | #1363 | NOT RUN | |
| MM-12 | **Two independent matches.** Two pairs of teams queue 2v2 with two READY 2v2 arenas. | 8 | Two matches run at once in different arenas; each arena RESERVED/ACTIVE only for its own match (`/lifesteal admin arena list`); results, hearts and Elo stay separate. | `lifestealRankedCheck` | NOT RUN | |
| MM-13 | **Multiple queues at once.** One team in 2v2 and another in 3v3 at the same time; the first team also tries a second size. | 4+ | Different teams in different sizes are fine. The same team twice: "Your team is already in a ranked queue (/ranked status)." | `startProblem` | NOT RUN | |
| MM-14 | **Leave queue.** Leader `/ranked leave` while WAITING; queue again at once. | 2 | Left; queueing again within `requeue-cooldown-seconds` (15): "Your team can queue again in Ns." Works after. | `leave`, `teamCooldown` | NOT RUN | |
| MM-15 | **Leave while the match is being set up.** `/ranked leave` right after everyone accepted. | 4 | "The match is already being set up." (PREPARING can't be left; use the match flow). | `leave` | NOT RUN | |
| MM-16 | **Duplicate player.** Player X is in team A's queue entry; team A's leader tries a challenge with X. Also list the same name twice. | 2+ | "X is already in a ranked queue or challenge." / "X is listed twice." | `rosterProblems`, `resolveRoster` | NOT RUN | |
| MM-17 | **No arena for the size.** Disable every 4v4 arena (`/lifesteal admin arena disable <id>`), then `/ranked queue 4v4`. | 4 | "No arena hosts 4v4 right now." | `startProblem` → `hostsSize` | NOT RUN | |
| MM-18 | **All arenas busy.** One 2v2 arena; a 2v2 is running; two more teams queue 2v2. | 8 | They stay WAITING (no proposal) until the arena frees, then are paired. No team is dropped unless `queue-timeout-minutes` (30) passes. | `availableArenaFor` | NOT RUN | |
| MM-19 | **Repeated opponents (rating).** Same two teams play 4 PUBLIC matches within 24h. | 4 | Matches 1–3 rated; the 4th (and later) completes normally but is UNRATED "these two teams already played 3 rated match(es) recently (same-opponent limit 3)". Matchmaking itself is FIFO and doesn't avoid repeats. | `RatingRules.decide`, #1365 | NOT RUN | |
| MM-20 | **Elo farming via new teams / rotation.** After MM-19, the same players form new teams (new names/ids) and play again; then rotate teammates but keep one opposing pair. | 4–6 | Still UNRATED: the roster limit (`REPEATED_ROSTER`) or the player-pair limit (`PLAYER_PAIR`) applies by UUID. | #1499, #1527, `lifestealRankedEloCheck` | NOT RUN | |
| MM-21 | **Team already in a match.** While team A plays a 2v2 with two members, its leader queues 2v2 with two other members. | 6 | "Your team is already playing a ranked match. A team plays one match at a time; try again when it ends." | #1499 | NOT RUN | |
| MM-22 | **Closed / locked states.** (a) `enabled: false`: a player runs `/ranked`; (b) on a fresh season before the ranked unlock hour: a staff member queues. | 2 | (a) "Ranked Team Wars aren't open yet. Staff will announce when they start." and `/ranked` isn't tab-completed for players. (b) "Ranked Team Wars haven't unlocked for this season yet; they open on Day 3 (/lifesteal progress shows when)." | #1369, #1352 | NOT RUN | |
| MM-23 | **Countdown.** Start any match; try to hit an opponent during the countdown. | 4 | No damage before the fight; during the countdown participants are re-checked every second (a restricted state cancels, see ST-08). | #1361, #1498 | NOT RUN | |
| MM-24 | **Cancellation by staff.** `/lifesteal admin ranked cancel <match>` then `... confirm`. | 4+S | Warns first; on confirm: no winner, everyone restored and moved back, hearts already stolen stay stolen, `RANKED_MATCH_CANCEL` in `/admin logs`, UNRATED. | `LifestealRankedCommand` | NOT RUN | |
| MM-25 | **Completion.** Play a match to the last death. | 4 | "<team> won the ranked match!" then the heart summary; everyone restored; arena back to AVAILABLE; PUBLIC = rated once. | #1361, #1474 | NOT RUN | |
| MM-26 | **Challenge.** Leader A `/ranked challenge <B> 2v2 <mate>`; leader B `/ranked challenge accept <A> <mate>`; play it. | 4 | Same consent and lifecycle; kind CHALLENGE; UNRATED "team challenges are unrated" while `elo.rate-challenges: false`. A second challenge within `challenge-cooldown-seconds` (60) is refused. | #1363 | NOT RUN | |
| MM-27 | **Bedrock in the queue menu.** A Bedrock leader opens `/ranked` and queues through the menu. | 4 | Plain chest menu; every button works and re-runs the command checks. | `RankedQueueMenu` | NOT RUN | |

## B. Team integrity (TI)

`/team transfer` and `promote` are the same action (there are no officers, so no demotion).
Player-side changes refused while a player is **held by a match** come from #1371
(`TeamCombatRules.rankedRefusal`). Ownership-transfer guards are issue **#1530 (open)**.

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| TI-01 | **Leader transfer while queued.** Team A WAITING in a 2v2 queue; leader `/team transfer <member> confirm`. | 2 | Today: the transfer is **not** refused and the queue entry continues. Behavior is being changed by #1530. | `LifestealTeamManager.transfer`, #1530 | PENDING #1530 | |
| TI-02 | **Leader transfer during countdown.** As TI-01 during the countdown. | 4 | Today: not refused (no ranked check in `transfer`). #1530 owns the fix. | #1530 | PENDING #1530 | |
| TI-03 | **Leader transfer during combat.** As TI-01 during the fight. | 4 | Today: not refused. Match sides still come from the roster (`matchSides`), so friendly fire/hearts stay correct, but the guard is #1530's. | #1371, #1530 | PENDING #1530 | |
| TI-04 | **Admin ownership transfer.** `/team admin transfer <team> <player> confirm` for a team in a match. | 4+S | Today: not refused (no ranked check in `adminTransfer`, unlike `adminDisband`). The audit finding #1530 targets. | #1530 | PENDING #1530 | |
| TI-05 | **Member joins during a match.** Leader (not playing) `/team invite <outsider>`; outsider `/team accept`. | 5 | Today: allowed (only the accepting player is checked). The joiner isn't on the match roster and can't enter the arena. Whether to lock it is #1530's decision. | #1530 | PENDING #1530 | |
| TI-06 | **Participant leaves during a match.** A participant `/team leave`. | 4 | Refused: they're held by a ranked match (#1371 ranked refusal message). | #1371, `lifestealTeamCombatCheck` | NOT RUN | |
| TI-07 | **Non-playing member leaves.** A team member who isn't in the match `/team leave`. | 5 | Allowed (they aren't held); the match roster is unchanged. | #1371 | NOT RUN | |
| TI-08 | **Kick a participant.** Leader outside the arena `/team kick <participant>`. | 4+ | Refused (target held). | #1371 | NOT RUN | |
| TI-09 | **Disband during a match.** Leader `/team disband confirm`; staff `/team admin disband <team> confirm`. | 4+S | Leader: refused. Admin: "<team> is playing a ranked match. Cancel it first: /lifesteal admin ranked cancel <match> confirm". | #1371 | NOT RUN | |
| TI-10 | **Team switching.** A participant tries to accept another team's invite. | 4+ | Refused: "You're already in <team>. Leave it first." (and leaving is refused, TI-06). | `accept` | NOT RUN | |
| TI-11 | **Member removed while queued.** Leader kicks a picked (queued, not yet in a match) player. | 2+ | Kick allowed; the queue entry ends on the next tick ("<name> is no longer in <team>"). | `hardProblem` | NOT RUN | |
| TI-12 | **Friendly fire by roster.** In a match, teammates hit each other (melee, arrow, splash potion). | 4 | No damage between same-side players; roster sides decide even if live membership changed. | #1371 | NOT RUN | |
| TI-13 | **Unlock after cancellation.** After MM-24, the leader kicks / disbands / transfers. | 4 | All work normally again (no permanent lock). | #1371 | NOT RUN | |
| TI-14 | **Unlock after completion.** After MM-25, same as TI-13. | 4 | All work normally again. | #1371 | NOT RUN | |
| TI-15 | **Rename.** Look for a team rename. | 1 | N/A: Lifesteal teams have no rename command (`/team` help). Mark N/A unless #1530 adds one. | `LifestealTeamCommand` | NOT RUN | |

## C. Hearts (HT)

Approved rules: start 10, max 20, min 1, no elimination, no revival. 30-minute same-victim
cooldown per killer → victim pair, shared across open-world, Combat NPC and ranked kills.

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| HT-01 | **New player.** Fresh account joins Lifesteal; `/lifesteal info`. | 1 | 10 / 20 hearts; health bar shows 10 hearts. | #1341 | NOT RUN | |
| HT-02 | **Ordinary kill.** Open world, both at 10, after grace. | 2 | Killer 11, victim 9 (`/lifesteal hearts get`). | `HeartRules.decide` | NOT RUN | |
| HT-03 | **Ranked kill.** In a TEST or PUBLIC match. | 4 | One heart moves, same rules as HT-02; the summary line reads "<killer> killed <victim>: heart stolen". | #1474 | NOT RUN | |
| HT-04 | **Ranked environmental kill.** Hit an opponent, knock them into lava/void within one combat-tag duration (`combat.tag-duration-seconds`, 15). | 4 | The last opposing hitter gets the heart; summary "<victim> died after a hit from <hitter>: heart stolen". After the window: no heart. | #1478 | NOT RUN | |
| HT-05 | **Victim at 1 heart.** Set the victim to 1, kill them (open world and ranked). | 2 | Victim stays 1, killer gains nothing; summary "no heart, victim was on their last heart". | `VictimAtMinimum` | NOT RUN | |
| HT-06 | **Killer at 20.** Set the killer to 20, kill. | 2 | Killer stays 20 and gains nothing; summary "no heart, killer is at the maximum" (open blueprint decision `KILLER_AT_MAXIMUM = NO_TRANSFER`). The victim keeps their hearts too and no cooldown starts. | `HeartRules` | NOT RUN | |
| HT-07 | **Withdraw.** At 2+ hearts, `/withdrawheart`. At 1, try again. | 1 | −1 heart and a Heart item; at 1: "You cannot withdraw your final heart!" | #1344 | NOT RUN | |
| HT-08 | **Redeem.** Right-click a Heart item. At 20 hearts, try again. | 1 | +1 heart, item gone. At 20: "You have already reached the maximum of 20 hearts!" | #1344 | NOT RUN | |
| HT-09 | **Duplicate serial.** Creative middle-click a copy of a Heart item (test server), redeem both. | 1+S | The first redeems, the copy is refused (its serial is already REDEEMED). | #1344 | NOT RUN | |
| HT-10 | **Forged item.** A Nether Star renamed "Heart", and one with a made-up serial. | 1+S | Neither redeems (no ISSUED row). | #1344 | NOT RUN | |
| HT-11 | **Same-victim cooldown.** Kill the same victim twice within 30 min (once in ranked, once in the open world). Then the victim kills the killer. | 2–4 | Second kill moves nothing ("no heart, same-victim cooldown" in ranked). The revenge kill is a different pair and moves one. | #1474 | NOT RUN | |
| HT-12 | **Withdraw eligibility.** A new account (under 120 min Lifesteal playtime / 24h) runs `/withdrawheart`. | 1 | Refused with the playtime / account-age message from `heart-items.withdraw-eligibility.messages`. | #1457 | NOT RUN | |
| HT-13 | **Shared connection monitoring.** Two accounts on one connection kill each other / transfer a Heart item. | 2 | Never blocked. A flag appears in `/lifesteal hearts monitor` (and an alt redemption in `/lifesteal hearts reviews`); players see nothing. Don't record addresses. | #1457, #1508 | NOT RUN | |
| HT-14 | **Ranked forfeit heart.** In a fight, hit an opponent then have them alt-F4. | 4 | They're out; the hitter gets one heart (`heart-loss: last-opponent`); summary "<leaver> forfeited to <hitter>: heart stolen". | #1465 | NOT RUN | |
| HT-15 | **Ranked hearts across a cancel.** A kill moves a heart, then staff cancel the match. | 4+S | The heart stays moved; inventories restored. | #1465 | NOT RUN | |
| HT-16 | **Persistence after restart.** Note every tester's hearts, `/stop`, start, check. | any | Unchanged. | `HeartStore` | NOT RUN | |
| HT-17 | **Team/ally kills.** Teammates (friendly fire off) and allies can't damage each other; with friendly fire on (test only) a teammate kill still moves no heart. | 2 | No damage / no heart (`heart-transfers: false`). | #1348, #1405 | NOT RUN | |
| HT-18 | **Grace period.** Fresh season with `grace-period-hours > 0`: players hit each other. | 2 | No damage, no tag, no heart. | #1352 | NOT RUN | |

## D. Inventory restoration (IR)

There are **no ranked kits**: players bring their own Lifesteal gear. The snapshot holds all 41
slots (36 storage + 4 armor + off-hand), XP and effects. Compare against before/after screenshots.

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| IR-01 | **Win.** Full prepared inventory (guide section 7), win a match. | 4 | Identical after the restore (items, counts, enchants, durability, names, PDC). Nothing picked up in the arena is kept. | #1361 | NOT RUN | |
| IR-02 | **Loss.** As IR-01, lose. | 4 | Identical. | #1361 | NOT RUN | |
| IR-03 | **Armor and off-hand.** Damaged armor + totem/shield in off-hand; take damage and use the shield. | 4 | Armor durability and off-hand back exactly as before the match. | #1361 | NOT RUN | |
| IR-04 | **Ender chest.** Try `/ec` (`/enderchest`) and an ender chest block in the match. | 2 | Both refused while held; ender chest contents unchanged (they aren't part of the snapshot and can't be used in a match). | #1361 | NOT RUN | |
| IR-05 | **XP and levels.** Note the XP level and progress; gain or lose XP in the match (e.g. an XP bottle, dying). | 4 | XP level and progress set back to the pre-match value. | #1490 | NOT RUN | |
| IR-06 | **Potion effects.** Start with a timed effect; drink potions in the match. | 4 | Match effects removed; pre-match effects back with their real-time remaining duration. Ominous Bottles can't be drunk while held. | #1490 | NOT RUN | |
| IR-07 | **Cursor item.** Hold an item on the cursor in the inventory screen as the match starts. | 4 | It's placed in a free slot (nothing drops) and back exactly once afterwards. | #1506 | NOT RUN | |
| IR-08 | **Crafting grid.** Items in the 2x2 grid (and separately an open crafting table) at match start. | 4 | Same as IR-07. | #1506 | NOT RUN | |
| IR-09 | **Inventory full.** Full inventory + an item on the cursor, accept the match. | 4 | Refused ("make room first"), nothing dropped, inventory unchanged, `RANKED_SNAPSHOT_REFUSED` in `/admin logs`. | #1506 | NOT RUN | |
| IR-10 | **Other open menu.** Accept a match with items in an open anvil. | 4 | Refused ("close it first"). | #1506 | NOT RUN | |
| IR-11 | **Death in ranked.** Die, respawn. | 4 | No drops, no XP orbs; restored once at respawn (`lifesteal_ranked_snapshots.status` RESTORED). Hearts follow HT-03. | #1361 | NOT RUN | |
| IR-12 | **Disconnect in ranked.** Quit mid-fight, rejoin. | 4 | Restored once on rejoin; dying later outside ranked reapplies nothing. | #1465, #1506 | NOT RUN | |
| IR-13 | **Restart mid-match.** `/stop` during the fight. | 4 | Everyone online restored and moved out synchronously; nobody forfeits. | #1465 | NOT RUN | |
| IR-14 | **Crash during restore.** Test server only: kill the process right after a restore message. | 2 | On restart the snapshot is REVIEW; player told it needs a staff check; `/ranked queue` refused until `/lifesteal admin ranked restore <p> resolve confirm` or `apply confirm`. | #1506 | NOT RUN | |
| IR-15 | **Bundle.** Empty a bundle inside the arena. | 2 | Drop refused if Paper fires `PlayerDropItemEvent`; **unverified in code**, record what happens. | §F of lifesteal-ranked-beta.md | NOT RUN | |
| IR-16 | **Arrows / tridents.** Shoot an arrow and throw a trident out of the arena to an outsider; throw a Loyalty trident as the match ends. | 4+O | Outsider can't pick them up; no second trident after the restore. | #1377 | NOT RUN | |
| IR-17 | **Duplicate restore attempts.** Disconnect and reconnect rapidly several times after a match; respawn + rejoin at once. | 2 | Applied exactly once (`RANKED_RESTORE_DUPLICATE_PREVENTED` may appear); no second copy. | #1506 | NOT RUN | |
| IR-18 | **Pending review listing.** After IR-14, `/lifesteal admin ranked restores`. | S | Lists the REVIEW snapshot with its reason; empty when none. | #1506 | NOT RUN | |
| IR-19 | **Heart item in inventory.** Bring a Heart item; try to redeem it in the match. | 2 | Redeem refused while held; the item is back after the match with the same serial (`/lifesteal hearts items <player>`). | #1361 | NOT RUN | |
| IR-20 | **Items given mid-match.** Staff `/joshymc give` an item to a held player. | 2+S | Record it: items another source puts into a held inventory are overwritten by the restore (known risk, §D of lifesteal-ranked-beta.md). | §D | NOT RUN | |

## E. Elo and rating (EL)

Formula (`EloRating`): expected `E = 1 / (1 + 10^((opp − own) / 400))`, change
`K × (actual − E)` rounded half away from zero, clamped to `elo.minimum-rating..maximum-rating`.
Player Elo uses each side's **average** participant Player Elo; Team Elo is the team's own rating.
Derived example with the bundled defaults (initial 1000, K 32): two fresh sides → E = 0.5 →
winners **+16**, losers **−16**. For any other ratings, compute from the formula with the
before-values you recorded; don't guess.

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| EL-01 | **Individual Elo, first match.** Fresh players/teams, PUBLIC 2v2, A wins. | 4 | Each A player 1000 → 1016, each B player 1000 → 984 (`/ranked stats`). Shown once to each player. | `EloRating`, #1365 | NOT RUN | |
| EL-02 | **Team Elo, first match.** Same match. | 4 | Team A 1016, team B 984 (`/ranked teamstats <team>`). | #1365 | NOT RUN | |
| EL-03 | **Unequal ratings.** After EL-01, play again with A winning; compute the expected change from the recorded before-values. | 4 | Change equals the formula result; winner's gain equals loser's loss unless a clamp applies. | `EloRating` | NOT RUN | |
| EL-04 | **Loss calculation.** B wins the next match. | 4 | As EL-03 with B as winner. | `EloRating` | NOT RUN | |
| EL-05 | **Disconnect forfeit.** A side loses because its last player disconnected mid-fight. | 4 | Ordinary loss: rated like any other result (no separate forfeit rating). | #1465 | NOT RUN | |
| EL-06 | **Repeated opponents.** See MM-19 (4th match UNRATED). | 4 | `/lifesteal admin ranked rating match <id>` shows UNRATED with the same-opponent reason. | #1365 | NOT RUN | |
| EL-07 | **Team disband / recreate.** See MM-20. | 4–6 | UNRATED (`REPEATED_ROSTER` / `PLAYER_PAIR`); old Team Elo stays with the disbanded team's id; a new team starts at `initial-team-rating`. | #1499, #1527 | NOT RUN | |
| EL-08 | **Cancelled match.** MM-24. | 4 | UNRATED "the match was cancelled (...)"; no rating change. | `RatingRules` | NOT RUN | |
| EL-09 | **Simultaneous last deaths.** Both sides' last players die in one tick (one crystal). | 4 | Cancelled, no winner, no Elo. (A same-tick disconnect vs death: the disconnecting side loses, see DR-10.) | #1361, #1465 | NOT RUN | |
| EL-10 | **Timeout.** `max-match-minutes: 1` (test), hide until it runs out. | 4 | Cancelled with no winner; UNRATED; inventories restored; hearts already moved stay moved. | #1361 | NOT RUN | |
| EL-11 | **TEST match.** After MM-01. | 4 | Never rated. | #1491 | NOT RUN | |
| EL-12 | **Challenge.** After MM-26. | 4 | UNRATED while `rate-challenges: false`. | #1363 | NOT RUN | |
| EL-13 | **Ineligible winner.** A winning-side player switches on another plugin's god/Creative mid-fight (ST-09) and the side still wins. | 4 | Win kept as the result but UNRATED (`RankedIntegrity.UNRATED_REASON`); a loss by an ineligible side stays rated. | #1498 | NOT RUN | |
| EL-14 | **Two matches at once.** MM-12, both PUBLIC. | 8 | Each match rated once, independently. | #1365 | NOT RUN | |
| EL-15 | **Persistence.** Note ratings, `/stop`, start. | any | Unchanged; `/ranked history` lists the same matches. | `RatingStore` | NOT RUN | |
| EL-16 | **Ranking display.** After 5+ rated matches per player/team: `/ranked top players`, `/ranked top teams`, `/ranked divisions`. | any | Listed only with at least `leaderboards.minimum-player-matches` / `minimum-team-matches` (5) rated matches; divisions match the thresholds in lifesteal.yml. | #1367 | NOT RUN | |
| EL-17 | **Match history.** `/ranked history [player]`. | any | Shows COMPLETED non-test matches with their Elo change; unrated ones marked unrated. | #1367 | NOT RUN | |
| EL-18 | **Restart recovery.** Test server: kill the process right after a match ends, before the rating shows; start. | 4 | The rating finalizes exactly once (FAILED rows retried by the 5-minute sweep, up to 5 times; `/lifesteal admin ranked rating failed`, `retry`). | #1365 | NOT RUN | |

## F. Arena and spectators (AS)

Protection applies only while a match (PREPARING → FINISHING) or a queue proposal holds the arena.
A TEST proposal waiting for acceptance doesn't reserve its arena yet.

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| AS-01 | **Participants.** In a match, walk to the boundary and try to leave; ender pearl over it. | 4 | Pushed back; every teleport out refused. | #1361 | NOT RUN | |
| AS-02 | **Outsider walks in.** During a match, an outsider walks / rides a horse or boat toward the boundary. | 4+O | Stopped at the boundary and put back outside. | #1524 | NOT RUN | |
| AS-03 | **Outsider logs in inside.** Log out inside the arena before it's reserved, log in during the countdown. | 4+O | Moved to the spectator point within ~1 second. | #1524 | NOT RUN | |
| AS-04 | **Staff spectate.** `/lifesteal admin ranked spectate <match>`, then `unspectate`. | 4+S | Teleported to the spectator point; can't be hurt, deal damage or pick anything up; `unspectate` ends it. | #1361, #1511 | NOT RUN | |
| AS-05 | **Spectator item pickup / drop.** As a spectator, pick up and drop items. | 4+S | Both refused. | #1524 | NOT RUN | |
| AS-06 | **Spectator blocks.** Place and break blocks. | 4+S | Refused. | #1361, #1507 | NOT RUN | |
| AS-07 | **Spectator entities.** Hit an End Crystal / a mount, step on a pressure plate, use a lead. | 4+S | Refused. | #1524 | NOT RUN | |
| AS-08 | **Outside projectiles.** Outsider shoots arrows / throws tridents at participants. | 4+O | No damage (player-attributed harm from outside a match is refused). | #1524 | NOT RUN | |
| AS-09 | **Outside potions.** Splash / lingering harmful potions thrown from outside. | 4+O | No effect on participants. | #1524 | NOT RUN | |
| AS-10 | **TNT / explosions.** TNT cannon and a creeper outside; a participant's own End Crystal inside. | 4+O | No arena blocks break; outside unattributed explosions don't hurt ACTIVE participants; participants' crystals still work. | #1507 | NOT RUN | |
| AS-11 | **Fluids.** Outsider empties water/lava onto the boundary and pours it to flow in. | 4+O | Refused / stops at the boundary. | #1507 | NOT RUN | |
| AS-12 | **Pistons.** Piston, sticky piston, slime and honey contraptions pushing in / pulling out. | 4+O | Nothing moves across the boundary. | #1507 | NOT RUN | |
| AS-13 | **Dispensers and fire.** Dispenser at the boundary (water, lava, fire, TNT, arrows); fire beside flammable arena blocks; sand/anvil from above. | 4+O | Refused / no arena change. | #1507 | NOT RUN | |
| AS-14 | **Pets.** Send a tamed wolf at a participant; then log out with it sitting there. | 4+O | It neither targets nor hurts them (offline owner = `ABSENT_OWNER`). | #1524 | NOT RUN | |
| AS-15 | **Spawn eggs / eggs.** Outsider throws a spawn egg and a chicken egg onto the arena floor. | 4+O | Nothing spawns. | #1524 | NOT RUN | |
| AS-16 | **Reserved arena.** While a queue proposal waits for accepts, an outsider walks in. | 4+O | Refused (reserved arenas are protected); the proposal's own players may enter. | #1524 | NOT RUN | |
| AS-17 | **Idle arena + WorldGuard.** No match: break a block in the arena; then with a WorldGuard region on it, during a match confirm WG denials still apply. | O | Idle: JoshyMC doesn't protect it (WorldGuard's job). In a match: JoshyMC only adds cancels, never un-cancels WG. | #1507 | NOT RUN | |
| AS-18 | **Cleanup and reuse.** After a win and after a cancel: `/lifesteal admin arena info <id>`; start a new match there. | 4 | Availability AVAILABLE; outsiders can interact normally again; the next match starts there. Projectiles shot out stay until touched/despawned. | #1361, #1507 | NOT RUN | |
| AS-19 | **Cross-world teleport.** Participant uses `/spawn`, `/home`, `/tpa`, `/rtp`, `/lobby`, `/survival`, a nether/end portal. | 4 | All refused while held. | #1361 | NOT RUN | |
| AS-20 | **Combat-tagged spectator / outsider.** A combat-tagged staff member runs spectate; a tagged outsider logs in inside the arena. | 4+S+O | Spectate refused while tagged. The tagged outsider is not moved, can't hurt/be hurt by participants or use anything, and is moved once the tag ends. | #1511, #1524 | NOT RUN | |
| AS-21 | **Spectator disconnect / world change.** A spectator quits, or leaves the arena world. | 4+S | Spectating ends (no lingering restrictions on rejoin). | #1524 | NOT RUN | |
| AS-22 | **Spectator restart.** Spectate, then `/stop` the test server and start. | 4+S | Spectating is memory only: after the restart the staff member isn't a spectator. | #1524 | NOT RUN | |
| AS-23 | **Arena validation.** Create an arena with a spectator point inside, or overlapping an enabled arena. | S | `arena info` shows INVALID with the reason; it can't host a match. | `RankedArenaRules.validate` | NOT RUN | |

## G. Staff abilities (ST)

Hierarchy (staff-permissions.md): Owner > … > Admin > … > Mod > … > Trainee; you can only act on
someone strictly below you; console always. Every refused staff action on a held player logs
`RANKED_STAFF_ACTION_BLOCKED` (throttled to one row per minute).

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| ST-01 | **/fly** `/fly <participant>` (Admin and console); participant `/fly` on themselves. | 4+S | Refused on a held player; self flight toggles cancelled while held. | #1498 | NOT RUN | |
| ST-02 | **/god** on a participant; participant `/god` on themselves during countdown/fight. | 4+S | Refused (on, self or others). Before entry: `/ranked queue` refused "has God Mode on". | #1498 | NOT RUN | |
| ST-03 | **/heal, /feed** on a participant. | 4+S | Refused on held players. (Self `/heal`/`/feed` are also refused outside Survival by #1521.) | #1498, #1521 | NOT RUN | |
| ST-04 | **/gamemode, /gmc** `/gamemode creative <participant>`, `/gmc <participant>` from console; participant self. | 4+S | Refused; game mode stays Survival (any change away from Survival is cancelled while held). | #1498 | NOT RUN | |
| ST-05 | **/tp, /tphere** a participant (and right after the match while the restore is pending). | 4+S | Refused for held players and for pending restores. | #1498 | NOT RUN | |
| ST-06 | **/invsee** a participant with and without `joshymc.invsee.edit`. | 4+S | Opens read-only; live edits refused while the player is held. | #1353 | NOT RUN | |
| ST-07 | **/sudo** `/sudo <participant> spawn` (Owner and console), then `/sudo <participant> msg <x> hi`. | 4+S | `spawn` refused; `msg` works. | #1498, #1353 | NOT RUN | |
| ST-08 | **NPC / portal commands.** An NPC with command `spawn` (`/npc setcommand`) and a portal `command` action, clicked/entered by a participant (near the arena). | 4+S | Refused for held players (same allowlist as typed commands). | #1498 | NOT RUN | |
| ST-09 | **Restricted state mid-fight.** Another plugin's god/vanish (or a test plugin forcing Creative), or JoshyMC `/vanish`/Mod Mode, activated in the fight. | 4+S | Within ~1 second the player is out (`INELIGIBLE` forfeit, "restricted ability"), restored; their side's win is unrated (EL-13). Other plugins' god/vanish aren't detected (only Creative/Spectator are, whatever sets them). | #1498 | NOT RUN | |
| ST-10 | **Smite / freeze / vanish / Mod Mode** on a participant. | 4+S | Refused with the cancel/remove hint. | #1498 | NOT RUN | |
| ST-11 | **Admin TEST match creation, hierarchy.** Admin `testmatch` including themselves, an equal-rank Admin, or a higher rank; then a Mod without the permission. | 4+S | Self/equal/higher refused (staff can't include themselves; use console). Mod: no permission. Console allowed. | #1491 | NOT RUN | |
| ST-12 | **Closed /ranked.** `enabled: false`: player vs Admin open `/ranked`. | 2+S | Player refused and not tab-completed; Admin with `joshymc.lifesteal.ranked.admin` can open it. | #1369 | NOT RUN | |
| ST-13 | **Intervention by a participant.** A staff member playing in a PUBLIC match runs `/lifesteal admin ranked cancel <their match>` / `remove`. | 4 | Refused (`interventionRefusal`); TEST matches are exempt. | #1498 | NOT RUN | |
| ST-14 | **Remove a participant.** `/lifesteal admin ranked remove <player> confirm`. | 4+S | Player restored and moved back; before the fight it cancels the match; `RANKED_PARTICIPANT_REMOVE` logged. | #1361, #1498 | NOT RUN | |
| ST-15 | **Insufficient permission.** A Mod (no `joshymc.lifesteal.ranked.admin`) runs `/lifesteal admin ranked list`, `arena create`, `testmatch`; `/lifesteal hearts set` without `joshymc.lifesteal.hearts.admin`. | S | No permission for each. | `LifestealRankedCommand`, `LifestealHeartsCommand` | NOT RUN | |
| ST-16 | **Owner / console.** Owner account and console repeat ST-01..ST-05. | 4+S | Still refused on held players (console included); the ranked refusal isn't a rank check. | #1498 | NOT RUN | |
| ST-17 | **Audit trail.** After ST-01..ST-14, `/admin logs`. | S | `RANKED_STAFF_ACTION_BLOCKED`, `RANKED_DELEGATED_COMMAND_BLOCKED`, `RANKED_TESTMATCH_*`, `RANKED_MATCH_CANCEL`, `RANKED_PARTICIPANT_REMOVE` rows present. | #1491, #1498 | NOT RUN | |

## H. Disconnects and restarts (DR)

**Destructive tests (`kill -9`) on the test server only.**

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| DR-01 | **Disconnect while queued.** A picked player quits while the entry is WAITING. | 2 | The entry ends on the next tick ("<name> went offline"); no stale entry (`/lifesteal admin ranked queue`). | `hardProblem` | NOT RUN | |
| DR-02 | **Disconnect during the ready check.** A player quits while a found match waits for accepts. | 4 | Proposal ends; that player gets the decline cooldown; the other team keeps its place. | #1363 | NOT RUN | |
| DR-03 | **Disconnect during the countdown.** | 4 | Match cancelled, unrated, no hearts; the leaver is restored on rejoin. | #1465 | NOT RUN | |
| DR-04 | **Disconnect in the fight, hit first.** `heart-loss: last-opponent`. | 4 | Forfeit (out of the match; teammates keep fighting); one heart to the last opposing hitter within the tag window; restored + told on rejoin. | #1465 | NOT RUN | |
| DR-05 | **Same with `heart-loss: none`.** | 4 | Forfeit only; no heart moves. | #1465 | NOT RUN | |
| DR-06 | **Disconnect without being hit / at 1 heart / hitter at 20.** | 4 | Out of the match; no heart moves in each case. | #1465 | NOT RUN | |
| DR-07 | **Staff kick in the fight.** `/kick` a participant. | 4+S | Out (`DISCONNECT_EXEMPT`); never a heart. | #1465 | NOT RUN | |
| DR-08 | **Disconnect after victory.** Quit during FINISHING / right after the win message. | 4 | Nothing decided at the quit; restored on rejoin once. | #1465 | NOT RUN | |
| DR-09 | **Disconnect during restoration.** Quit right as the restore runs. | 2 | Restored once (PENDING → RESTORING → RESTORED); if the mark couldn't be written, RESTORING is never reapplied. | #1506 | NOT RUN | |
| DR-10 | **Same-tick disconnect vs death.** The losing side's last player quits in the tick the other side's last player dies. | 4 | The disconnecting side forfeits and the other side wins (not a no-winner cancel). | #1465 | NOT RUN | |
| DR-11 | **Normal restart.** `/stop` mid-match. | 4 | Nobody forfeits; everyone restored and moved out; match CANCELLED; no Elo. Queues and proposals (memory only) are gone after the restart. | #1465, #1363 | NOT RUN | |
| DR-12 | **Unexpected shutdown.** `kill -9` mid-match, start. | 4 | Startup recovery: FINISHING → COMPLETED with its winner, everything else CANCELLED; each player restored on join; no Elo for cancelled. | #1361 | NOT RUN | |
| DR-13 | **Arena during shutdown.** After DR-11/DR-12, `/lifesteal admin arena info <id>`. | S | AVAILABLE (availability is computed from the match, never stored). Arena blocks unchanged (matches don't change blocks). | #1361 | NOT RUN | |
| DR-14 | **Pending restore recovery.** Player offline at DR-12; joins later. | 2 | Restored on join, once. A RESTORING row from the crashed boot becomes REVIEW (IR-14). | #1506 | NOT RUN | |
| DR-15 | **Elo settlement recovery.** EL-18. | 4 | Finalized once. | #1365 | NOT RUN | |
| DR-16 | **Heart transaction recovery.** `kill -9` right after a ranked kill message; start. | 4 | The heart change is either fully committed or not at all (committed before applied); no double transfer on rejoin (UNIQUE dedupe key per death). | `HeartStore` | NOT RUN | |
| DR-17 | **Bedrock disconnect.** A Bedrock participant closes the app mid-fight. | 4 | Same as DR-04 (counted disconnect). | #1465 | NOT RUN | |

## I. Performance and stability (PS)

| ID | Scenario, preconditions and steps | Players | Expected (current code) | Ref | Status | Actual / evidence |
|---|---|---|---|---|---|---|
| PS-01 | **TPS under load.** `/tps` before and during two simultaneous matches. | 8 | No sustained drop (record numbers; ranked/heart writes are a few indexed rows). | §C of lifesteal-beta.md | NOT RUN | |
| PS-02 | **Console errors.** Watch `logs/latest.log` through a full session. | any | No stack traces / severe lines from JoshyMC. Attach any. | — | NOT RUN | |
| PS-03 | **Long session.** Ten consecutive matches in one arena. | 4 | Each starts and ends cleanly; the arena never stays RESERVED/ACTIVE; no growing queue/proposal entries. | #1361 | NOT RUN | |

---

## Totals

| Category | Scenarios | Pending on #1530 |
|---|---|---|
| A. Matchmaking (MM) | 27 | 0 |
| B. Team integrity (TI) | 15 | 5 |
| C. Hearts (HT) | 18 | 0 |
| D. Inventory restoration (IR) | 20 | 0 |
| E. Elo (EL) | 18 | 0 |
| F. Arena / spectators (AS) | 23 | 0 |
| G. Staff (ST) | 17 | 0 |
| H. Disconnect / restart (DR) | 17 | 0 |
| I. Performance (PS) | 3 | 0 |
| **Total** | **158** | **5** |
