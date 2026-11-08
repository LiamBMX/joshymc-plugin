# Lifesteal Team Upgrades and Shared Progression (issue #1426)

Lifesteal only. Survival teams (`TeamManager`, `/team` for Survival players) are never read or
changed. Code: `src/main/kotlin/com/liam/joshymc/lifesteal/teams/progression/`. Settings:
`lifesteal.yml > team-progression`. Checks:

```
./gradlew -I scripts/lifesteal-team-progression-check.init.gradle.kts lifestealTeamProgressionCheck
```

## What players get

- `/team upgrades` (aliases `progression`, `level`), or **Team Upgrades** (slot 13) in the `/team` menu.
- `/team bank` and `/team bank deposit <amount> [confirm]`: the team bank.
- `/team objectives`, `/team milestones`, `/team style`.
- Staff: `/team admin progression <team>` (read-only: level, XP, tiers, bank, claims, objectives, recent audit).
- Permission `joshymc.lifesteal.team.upgrades` (default true). Buying upgrades and changing the team
  style also need **team leadership**; claiming milestones and depositing need team membership.

## Team Levels

- Every team starts at Level 1. There is no maximum level.
- XP for the next level = `levels.base × level ^ levels.exponent` (default `1000 × level^1.5`).
- Only the **total Team XP** is stored. The level is always worked out from it, so no level counter
  can drift or be raised on its own. If the formula changes, every team is re-levelled. Tiers already
  bought and milestones already claimed are kept.
- Progression belongs to the team id (`lifesteal_teams.id`), never to a player or a name.

## Team XP sources

Only events another system already verified count. There is no "give XP" command.

| Source (`xp.sources.*`) | Comes from | Default XP / daily cap |
|---|---|---|
| `pvp-kill` | A Lifesteal kill the heart service settled as `Transfer`, `VictimLosesOnly`, `VictimAtMinimum` or `KillerAtMaximum`. Teammates, allies, unknown team state, the same-victim cooldown and the pre-launch/grace window never count. | 40 / 400 |
| `bounty-claim` | A Lifesteal bounty that was claimed and **paid**. | 75 / 450 |
| `ranked-match` | Each team in a Ranked Team Wars match the rating service **rated**. Test matches, unrated challenges and matches over the same-opponent limit never count. | 60 / 480 |
| `ranked-win` | The winner of a rated match, on top of `ranked-match`. | 120 / 720 |
| `ore-mined` | A naturally generated ore from `xp.ores`, broken in a Lifesteal world (MONITOR, not cancelled). Player-placed ores are marked in chunk data (`joshymc:team_xp_placed`) and never count. Batched every minute. | 1 per ore / 300 |
| `exploration` | Per 100 blocks of sampled horizontal movement in a Lifesteal world. Samples every 5 s; a jump of more than 60 blocks (a teleport) counts nothing. A minute where the player ended up near where they started (circling, AFK pools) counts nothing. AFK, flying and non-Survival game modes count nothing. Batched every minute. | 2 / 200 |
| objectives | A shared objective reaching its target. Paid once per cycle and never capped. | per objective |

Anti-farming:

- **Daily caps** (base XP, per team): one per source, `xp.team-daily-cap` for all sources together, and
  `xp.member-daily-cap` for each member. Days roll over in `xp.timezone`.
- **Team hopping**: a member earns XP for a team only after `minimum-membership-minutes` in it.
- **Repeated victims**: one team earns PvP XP for the same victim at most once per `pvp-same-victim-hours`.
  A repeat kill also never advances "unique enemies".
- **Alt accounts**: with `block-shared-connections`, kills and bounties between players who have
  shared a connection (AltManager's hashed addresses) earn nothing. A shared address isn't proof of
  an alt (families, schools, VPNs), so this only withholds Team XP. It never punishes anyone.
- **Duplicates**: every event has a `dedupe_key` (UNIQUE): the heart transfer key, the bounty payout
  id, the match id + team, or a per-boot batch number. A repeated event awards nothing.
- **AFK**: mining and exploration skip AFK players (`AFKManager`).
- Refused events (no team, too-new member, disbanded team, repeat victim) never advance objectives.
  Capped events still do.

The Team Progression upgrade adds its bonus (at most 10%) on top of the XP the caps allowed.

## Upgrade categories

Bought by the **team leader only**, with Coins from the **team bank**, never from a player's
balance. Each tier has a configurable `cost`, required Team `level` and `value`. Costs and levels
can't fall from one tier to the next (the config is refused if they do).

| Category | `value` means | State |
|---|---|---|
| Team Storage | extra storage slots (+9 per tier) | **Not available yet**: Lifesteal teams have no team storage. `enabled: false`, and the store refuses purchases while the system is missing. `storageSlotBonus(teamId)` is ready for it. |
| Team Home | extra team homes | **Not available yet**: Lifesteal teams have no team homes (and `/tpa` isn't decided for Lifesteal). `extraHomes(teamId)` is ready for it. |
| Team Economy | extra bank limit (coins) | Live. Raises the deposit limit. |
| Team Progression | total Team XP bonus % (2/4/6/8/10) | Live. Hard cap of 10%. |
| Team Cosmetics | team colours unlocked (total) | Live. The leader picks a colour in Team Style. |
| Team Utility | features unlocked (total) | Live: 1 = member contributions, 2 = activity log, 3 = XP by source. |

There are no combat, damage, heart, max-health or invulnerability upgrades, and nothing teleports.
Combat tags, safe zones, ranked restrictions, heart rules and team protections are untouched.

### Purchases

1. The category screen shows each tier (owned / ready / locked with the reason).
2. Clicking the next tier opens a **confirmation screen** with one transaction id. A second click
   on Confirm does nothing.
3. On confirm the manager re-checks the player (Lifesteal world, permission, still the leader of
   that team). Then one database transaction re-checks everything against the rows as they are now:
   transaction id unused (`transaction_id` PRIMARY KEY), buyer still in that team, the team ACTIVE
   with the buyer as its **current** leader, category enabled and its system available, the tier
   still the one the screen showed (otherwise "stale screen"), not maxed, the Team Level high enough.
   It then takes the cost from the bank with `UPDATE ... WHERE balance_cents >= cost`, moves the tier
   on with a conditional update, and writes the purchase, ledger and audit rows.
4. Concurrent purchases run one after the other on the database lock. The second one sees the new
   tier and is refused as stale, so a tier can only be bought once and only charged once.

## The team bank

Lifesteal teams had no bank before this feature, so this adds the smallest safe one:

- Any member deposits **their own** coins (`/team bank deposit <amount> confirm`, or the buttons in
  the bank screen with a confirmation). The player's withdrawal and the bank credit happen in one
  transaction, with a unique reference per deposit.
- There is **no withdrawal**. Coins in the bank can only buy upgrades. Upgrades therefore can't
  duplicate money or create coins, and a disband can't move money anywhere.
- The bank has a limit (`bank.base-capacity` + Team Economy tiers). Milestone coin rewards may take
  it above the limit.
- Deposits only work on the dedicated Lifesteal server (`TutorialRules.economyBelongsTo`). On a
  server that also hosts Survival, the coin balances are Survival's, so the bank refuses deposits
  there (Team XP still works).
- Every movement is in `lifesteal_team_bank_ledger` (append-only) and `lifesteal_team_progress_audit`.

## Shared objectives

`objectives.list` (id, pool, type, target, xp, name). Pools: combat, mining, exploration, teamwork.
The defaults have objectives in every pool, so teams progress without PvP or ranked matches.
Types: `pvp-kills`, `unique-victims`, `bounties`, `ranked-matches`, `ranked-wins`, `ores`,
`blocks-travelled`, `unique-contributors`. `objectives.cycle`: `weekly` (Monday 00:00 in
`xp.timezone`), `season`, or `never`. Each objective completes and pays once per cycle (the
completion row has its own unique key).

## Milestones

Levels 5, 10, 25, 50, 75, 100, then every 25 levels (`milestones.levels`, `every-after`). Rewards
(`milestones.rewards`, `default-reward`) are team-bank coins and/or a team title. Any member can claim
a reached milestone, **once per team, ever** (PRIMARY KEY team + level, append-only). A new team
(also one recreated with the same name) is a new team id at Level 1. It has to earn every level
again, so disbanding and recreating can't repeat a reward. Milestone coins can only buy upgrades.

## Membership changes

| Change | Effect on progression |
|---|---|
| Join | Earns XP for the team after the minimum membership time. |
| Leave / kicked | Nothing moves. The team keeps its XP, upgrades and bank. The player earns nothing more for it and can't buy, claim or deposit for it. |
| Leadership transfer | The new leader can buy at once. The old leader can't (checked against the current leader in the transaction). |
| Disband | The team's rows stay as history. Every write checks that the team is ACTIVE, so nothing can be earned, bought or claimed afterwards. Bank coins stay frozen in the disbanded team. The disband rules of #1346 are unchanged. |
| Rename / role changes | Lifesteal teams can't be renamed and have no roles besides leader. Progression keys on the team id, so a future rename wouldn't matter. |

## Seasons

- The season id comes from the launch progression (`lifesteal_seasons`, e.g. `season-1`). It is
  recorded on every XP event and per team in `lifesteal_team_season_xp` (history, never reset).
- `seasons.reset-policy: keep` is the only supported value. Team Levels, upgrades, the bank, titles
  and milestone claims are **permanent**. Season XP and the season objective cycle are per season.
- **Open decision (not invented):** whether Team Levels, upgrades or banks should reset at a season
  rollover. The blueprint defines no team-progression reset, and there is no season rollover yet
  (one season). Any other policy is refused by the config parser until the Owner approves one.
  Milestones are claimed once per team across seasons, so a reset policy can't make old rewards
  claimable again.

## Disabling

`team-progression.enabled: false` (or an invalid section) stops XP, purchases, deposits, claims and
style changes, and the menu says Team Upgrades are off. Nothing stored is deleted or changed. A
mistake in this section never pauses hearts, teams or anything else.

## Tables (data.db, Lifesteal server only)

`lifesteal_team_progress`, `lifesteal_team_season_xp`, `lifesteal_team_xp_events` (dedupe key UNIQUE),
`lifesteal_team_xp_daily`, `lifesteal_team_bank` (CHECK >= 0), `lifesteal_team_bank_ledger`
(append-only), `lifesteal_team_upgrades` (triggers: never lowered or deleted),
`lifesteal_team_upgrade_purchases` (append-only, UNIQUE team + category + tier),
`lifesteal_team_objective_progress` / `_subjects`, `lifesteal_team_milestone_claims` (append-only),
`lifesteal_team_unlocks`, `lifesteal_team_style`, `lifesteal_team_progress_audit`. Team XP can't
go down (trigger).

## Outstanding integration dependencies

- **Team storage and team homes** don't exist for Lifesteal teams. The categories are configured
  but off and unbuyable. When those systems are built, read `storageSlotBonus` / `extraHomes`, then
  turn the categories on. The team-home system must go through `CombatTeleportGuard` and the ranked
  `TransferBlocker`/`holds` rules like every teleport.
- **Officers**: Lifesteal teams have only a leader (blueprint), so "officers with permission" isn't
  implemented. Only the leader buys.
- **Team quests / team events**: Lifesteal has no quest system (Daily/Weekly quests are Survival-scoped),
  and hosted events aren't tied to teams. Those XP sources aren't wired. Add an `XpSource` and call
  `award` from the verified completion when they exist.
- **Team chat colours**: Lifesteal teams have no team chat (only `/ac` alliance chat). The chosen
  colour and title show in `/team info`, the team menu and the upgrade screens.

## Manual tests (Paper server)

1. Lifesteal-only server: create a team, wait out the membership time, mine ores and walk. Team XP
   appears in `/team upgrades` within about a minute.
2. Kill a non-teammate (after grace): Team XP once. A second kill of the same player within 24h gives none.
3. Deposit 100k, buy Team Progression I as leader. A member sees the tier but can't buy it.
4. Transfer leadership: the old leader's confirm screen is refused. Disband: nothing can be bought or claimed.
5. Reach Level 5 (`/team admin progression` to watch), claim the milestone twice: the second claim is refused.
6. Set `team-progression.enabled: false`, `/joshymc reload`: the menu says it's off, and `/team admin progression` still shows the stored data.
