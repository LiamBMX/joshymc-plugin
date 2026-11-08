# Lifesteal Protected Bank and PvP coin stealing (issue #1427)

Lifesteal only. Players have two balances:

| Balance | What it is | At risk in PvP |
|---|---|---|
| **Wallet** | The existing economy balance (`economy` table, EconomyManager): `/balance`, `/pay`, shops, bounties, rewards. Nothing about it changed. | Yes: a share moves to the killer on an eligible PvP death. |
| **Bank** | A separate protected balance in `lifesteal_bank_accounts` (whole cents). | Never. |

The bank only runs where the server hosts **only** Lifesteal (`TutorialRules.economyBelongsTo`,
same rule as the Lifesteal bounties): on a server that also hosts Survival the `economy` table is
Survival's, so no bank table is created, `/bank` refuses and no coin moves on a death. A Survival
server doesn't even register `/bank` (released for other plugins). Survival's economy and Survival
team banks are untouched.

## Commands

| Command | Who | What |
|---|---|---|
| `/bank` | `joshymc.lifesteal.bank.use` (true) | Opens the Bank GUI. |
| `/bank balance` | same | Wallet, bank and capacity. |
| `/bank deposit <amount\|all>` | same | Wallet -> bank. |
| `/bank withdraw <amount\|all>` | same | Bank -> wallet. |
| `/bank admin view\|history\|transfers\|suspicious\|failed\|review\|summary` | `joshymc.lifesteal.bank.admin` (op) | Read-only investigation; `review <id> <note>` marks a failed transaction investigated (audited). |
| `/bank admin add\|remove <player> <amount> <reason>` | `joshymc.lifesteal.bank.admin.adjust` (op) | Corrects a **bank** balance (never a wallet): at most `bank.admin-max-adjustment` per adjustment, reason required, staff protection (`staffRankManager.protectionProblem`), ledger row + `AdminAudit` (`BANK_ADD` / `BANK_REMOVE`). Never below zero. |

Amounts use the bounty parser (`BountyMoney.parse`): `5000`, `5,000`, `$5000`, `2500.50`, `10k`,
`1.5m`. Zero, negatives, more than two decimals, NaN, Infinity, `1e400` and anything above
$10 trillion are refused. `/bank` is scoped LIFESTEAL (`GamemodeAssignments`).

## GUI

`/bank` (45 slots, `LifestealBankMenu`): your head with wallet, protected bank and capacity; Deposit
row (10%, 25%, 50%, All, Custom Amount) and Withdraw row (same), the fees and capacity, and a
"PvP Coin Loss" explanation. Percentages are taken from the right source (wallet for deposits, bank
for withdrawals) and recomputed from the balance **at click time**. Every button opens a
confirmation screen showing amount, fee, what arrives and both balances afterwards. Custom Amount
asks in chat (cancelled from public chat, 60 s timeout, `cancel`/`all` accepted).

Exploit safety: menus are `CustomGui`s (GuiManager cancels clicks, blocks shift-click, number keys,
drags and off-hand pushes, 200 ms click cooldown). Menus never move coins: Confirm calls
`LifestealBankManager.transfer`, the same path as the command, with one transaction id per
confirmation screen (single use), so a double click moves coins once.

## Rules for a transfer

Everything is re-checked in the tick the transfer runs, whatever opened it (command, alias,
`/joshymc:bank`, dispatched command, stale GUI):

1. The bank is available here (hosted, Lifesteal economy, tables ready).
2. Deposits: `bank` section valid and `bank.enabled`. Withdrawals always work, so disabling the
   bank (or a broken section) never locks coins away.
3. The player is in a Lifesteal world, alive, not combat-tagged (`block-during-combat`), not held
   by / spectating / awaiting restore from a Ranked Team Wars match (`block-during-ranked`), not in
   the event world or a running hosted event, not frozen.
4. One SQLite transaction (`BankStore.transfer`) reads both balances, plans with `BankRules.plan`
   (fee, capacity, overflow), moves the wallet with EconomyManager's conditional statements, moves
   the bank with a version-conditional UPDATE and writes the ledger row. Any failure rolls all of it
   back and records a FAILED row for staff.

**Fees** (`deposit-fee-percent`, `withdrawal-fee-percent`, default 0): rounded **up** to the next
cent, so splitting a transfer never dodges a fee. The fee comes out of the amount (deposit 1,000
with 2.5% = wallet -1,000, bank +975) and leaves the economy (a sink). Shown before confirming and
stored on the ledger row. A transfer whose fee would take everything is refused.

**Capacity** (`max-capacity`, default -1 = unlimited): deposits may not take the bank past it;
"All" deposits what fits. Lowering it never removes coins: a bank above the new limit keeps
everything and can still withdraw, it just takes no deposits. Future bonuses (team upgrades, rank
perks) register a `LifestealBankManager.CapacityBonus`; they only raise a limited capacity.

## PvP coin stealing

On a PlayerDeathEvent (MONITOR, ignoreCancelled; read only) or a Lifesteal Combat NPC death
(`LifestealCombatNpcDeathEvent`, issue #1402), in this order (`BankRules.eligibility`):

1. `pvp-coins.enabled`.
2. The heart system's attribution (`HeartRules.attribute`): only `PVP_KILL`, the final damage's
   causing player, online, in Lifesteal, not an NPC, not the victim. Environment, mob, suicide and
   admin (`/kill`) deaths never steal.
3. Event world / running hosted event: never.
4. Ranked Team Wars match (either player held): only with `ranked-enabled` (default **false**, the
   policy isn't approved). Ranked inventory snapshots are untouched; wallets were never part of them.
5. The launch progression window at death time (`heartWindow`): before launch, during the grace
   period and while unknown, nothing moves (same as hearts).
6. Teams: the heart system's verdict (`lifestealTeamManager.heartCheck`) first, so coins and hearts
   never disagree (teammates, allies, unknown membership); then, with `block-team-farming`,
   teammates, recent teammates and allies never move coins even if the team settings let them move
   hearts. Inside a ranked match the roster sides decide (as for hearts).
7. Shared connection (AltManager's hashed addresses): logged; refused only with
   `block-shared-connections`.
8. In the transaction (`BankStore.steal`): the dedupe key (`coins:pvp:<boot>:<victim>:<tick>` or
   `coins:combatnpc:<session>`, UNIQUE) so a repeated event/retry/reconnect never pays twice; the
   per killer-victim cooldown `same-victim-cooldown-minutes` (with `anti-farming-enabled`); the
   amount = `wallet-loss-percent` of the victim's wallet **read inside the transaction**, rounded
   down, capped by `max-per-kill`. The victim's wallet withdrawal, the killer's deposit, the steal
   row, the cooldown and both ledger rows commit together.

Coins are decided **independently of hearts**: a victim on their final heart keeps it (the
permanent 1-heart minimum, `HeartRules.decide` is unchanged: no heart, no Heart item, no
elimination, normal respawn) and coins still move; a killer at the heart maximum still gets coins.
The two systems use separate transactions, so a failed coin transfer never touches a heart
transaction and vice versa.

Notifications (`pvp-coins.messages`): the victim sees what they lost and their remaining wallet
(saved and shown on the next join for Combat NPC deaths); the killer sees what they stole and their
own wallet. The victim's bank is never shown to the killer.

Anti-farming log: steals that reach `repeat-kill-threshold` within `repeat-kill-window-hours`, and
shared connections, go to `lifesteal_coin_suspicious` (`/bank admin suspicious`) and the console.

## Persistence

| Table | Contents |
|---|---|
| `lifesteal_bank_accounts` | uuid PK, `balance_cents` CHECK 0..max, `version`. A trigger refuses DELETE. |
| `lifesteal_bank_ledger` | Every deposit, withdrawal, PvP loss/gain, admin adjustment and failed transaction: tx id, uuid, time, type, amount, fee, net, opposing player, state COMPLETED/FAILED, balances after, season, source, actor, detail. Append-only (triggers refuse DELETE and edits except the review columns). A tx id completes once per player (partial UNIQUE index). |
| `lifesteal_coin_steals` | One row per coin-moving death, `dedupe_key` UNIQUE, immutable. |
| `lifesteal_coin_steal_cooldowns` | Last steal per killer-victim pair. |
| `lifesteal_coin_suspicious` | Anti-farming log. |
| `lifesteal_bank_notices` | Messages for offline victims, delivered once. |

All writes are synchronous single transactions on the main thread (the heart system's pattern), so
nothing is pending at shutdown or lost on a crash, and balances survive restarts, disconnects,
world changes, team changes and disbands. Personal banks have no link to teams: teammates can't
touch them, team upgrades never pay from them, and changing or disbanding a team never moves them.

## Seasons

Every ledger and steal row stores the current season id (`lifestealProgressionManager.progress().season`).
`bank.season-reset` accepts only `none`: **no personal bank reset policy is approved**, so banks are
never wiped and there is no rollover that could duplicate coins. When a policy is approved it should
be a new value here plus a one-time, audited, idempotent migration keyed on the season id.

## Open policy decisions

- Whether ranked deaths steal wallet coins (`ranked-enabled`, default false).
- Personal bank behaviour at a season reset (`season-reset: none` only).
- Whether shared-connection kills should be blocked (`block-shared-connections`, default false: logged).
- Fee and capacity values (defaults: no fees, unlimited).

## Checks

`./gradlew -I scripts/lifesteal-bank-check.init.gradle.kts lifestealBankCheck`: money/fee math,
planning, config, access rules, eligibility (incl. the 1-heart minimum), and against real SQLite:
deposits/withdrawals/all/fees/capacity, duplicate ids, steals (bank immunity, duplicate deaths,
cooldown, max per kill), rollback on economy failures, restart persistence, append-only history,
admin adjustments, offline notices, coin conservation under concurrent deposits, withdrawals and
steals, and the wiring (Lifesteal only, Survival untouched).

## Manual tests

1. Lifesteal-only server: `/bank` opens; deposit 10%/All/custom, withdraw; balances and fees match.
2. Get combat-tagged, try `/bank deposit all` and the GUI Confirm: refused.
3. Kill a player with 100,000 wallet / 500,000 bank: killer +10,000, victim wallet 90,000, bank unchanged; messages.
4. Kill them again within 30 minutes: no coins, killer told.
5. Victim on 1 heart: no heart moves, coins do; victim respawns normally.
6. Teammates (friendly fire on) and allies: no coins.
7. Ranked test match death: no coins (default).
8. Combat-log, have the NPC killed: on rejoin the victim sees the loss once.
9. Survival server: `/bank` is not a JoshyMC command, no coin moves on death.
