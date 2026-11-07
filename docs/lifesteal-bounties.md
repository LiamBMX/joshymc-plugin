# Lifesteal bounties (issue #1401)

Player-funded bounties on the Lifesteal server. Players spend their own coins to put a bounty on
another Lifesteal player. Contributions stack. Whoever legitimately kills the target in ordinary
Lifesteal PvP gets 100% of the bounty. If nobody does within seven days, every contributor is
refunded in full.

Code: `src/main/kotlin/com/liam/joshymc/lifesteal/bounty/`. Checks:
`./gradlew -I scripts/lifesteal-bounty-check.init.gradle.kts lifestealBountyCheck`.

## Where it runs

- **Lifesteal only.** Bounties run only where `networkIdentityManager.hostedGamemodes` is exactly
  Lifesteal, which is the dedicated Lifesteal server. On a server that also hosts Survival, the one
  `economy` table belongs to Survival (`TutorialRules.economyBelongsTo`), so bounties log a warning
  and stay off instead of moving Survival coins.
- `/bounty` is NETWORK-scoped and goes through `command/BountyCommandRouter`, the same pattern as
  `/team`:
  - Players in a Lifesteal world get `LifestealBountyCommand`.
  - Everyone else gets the unchanged Survival `BountyCommand`, with its SURVIVAL scope and
    `joshymc.bounty`.
- The Survival bounty system (`TeamManager`, `bounties` table, `gui/bounty/`) is untouched.
- Announcements go only to players in Lifesteal worlds.

## Commands and permissions

| Command | Permission | What it does |
|---|---|---|
| `/bounty` | `joshymc.lifesteal.bounty.use` (default true) | Opens the active bounty menu. |
| `/bounty add <player> <amount>` | same | Creates a bounty, or adds to the active one. Amounts like `10000`, `10,000`, `25k`, `1.5m`. Player names must match exactly (case doesn't matter); there is no prefix matching. |
| `/bounty stats` | same | Your own statistics and active contributions (private). |
| `/bounty top [richest\|claims] [page]` | same | The two hunter leaderboards (menu; chat from console). |
| `/bounty admin list\|info <player>\|history <player>\|pending\|failures\|suspicious\|audit\|check\|retry` | `joshymc.lifesteal.bounty.admin` (default op, never inherited by staff ranks) | Staff investigation and recovery (see below). |

## Menus

- **Active list** (54 slots):
  - 45 bounties per page, highest total first. Ties go to the older bounty, then the bounty id.
  - Each bounty shows the target's head (offline targets too, via PlayerHeadManager), the total,
    the time left and whether the target is online.
  - Bottom row: Leaderboards 45, Your Stats 46, Previous 47, Close 49, Next 51, Place a Bounty 52,
    How Bounties Work 53.
  - An empty list shows a "No active bounties" note.
  - Open lists redraw in place whenever the active list changes.
- **Contribute** (36 slots):
  - Target head with the current total.
  - Preset amounts (`gui.contribution-presets`; amounts below the minimum are hidden).
  - Custom Amount: typed in chat, or `cancel`, with a 60-second timeout.
  - Back and Close buttons.
- **Confirm** (27 slots):
  - Shows the amount and your balance (read off the main thread).
  - Confirm runs `LifestealBountyManager.contribute`, the same path as `/bounty add`.
- **Place a Bounty**: you type the exact player name in chat, then see the Contribute screen.
- **Leaderboards**: Richest (coins earned) and Most Successful (claims), with switch buttons,
  pages and Back.

All menus are `CustomGui`. GuiManager cancels every click on menu slots, blocks
shift/number-key/double-click moves into the menu and rate-limits clicks.

Stale actions are refused:

- Clicking a bounty re-reads it. If it was claimed or expired, the list refreshes.
- Confirming passes the bounty id the player saw (`expectedBountyId`), so a bounty that ended while
  the menu was open is refused as STALE. It never quietly turns into a new bounty.
- Each confirmation screen has one transaction id and can only be used once, so a double click
  pays once.

No menu, lore, message, error or tab completion ever shows contributor names, UUIDs or individual
amounts. Tab completion only offers names of players currently online in Lifesteal.

## Escrow and transactions

- **Money is exact.** Every bounty amount is a `Long` of cents (`BountyMoney`), parsed with
  `BigDecimal` and added with overflow checks.
  - Refused: zero, negatives, NaN/Infinity, `1e400`, fractions of a cent, and anything above
    $10,000,000,000,000 per contribution or per bounty total (`MAX_CENTS`). That limit is the
    largest amount whose `Double` conversion is still exact to the cent.
  - There is no gameplay maximum.
- **The economy is the existing `EconomyManager`.** Bounty tables live in the same `data.db`, and
  EconomyManager's statements join an enclosing transaction. So in `BountyStore`:
  - **Contribution**, one transaction: checks (target has a `lifesteal_hearts` row, i.e. has
    joined Lifesteal; not teammates by stored membership), `economy` withdraw (conditional on the
    balance), bounty insert or conditional update, contribution row (its transaction id is the
    PRIMARY KEY), statistics, audit row, and the target's notification. Either all of it commits
    or nothing does.
  - **Claim**, one transaction: ACTIVE → CLAIMED (conditional), a PENDING payout (UNIQUE per
    bounty and per death dedupe key), the killer-target cooldown and an audit row.
  - **Payout**, one transaction: PENDING → PAID (conditional), deposit, the hunter's statistics,
    the hunter's notification and an audit row. A failure rolls back and leaves the payout
    PENDING, with the attempt count, error and next retry time recorded.
  - **Expiration**, one transaction: ACTIVE → EXPIRED (conditional, only once `expires_at` has
    passed), and every contribution becomes refund PENDING.
  - **Refund**, one transaction per contribution: PENDING → REFUNDED (conditional), deposit of
    exactly that contribution, statistics, the contributor's notification and an audit row.
    Failures stay PENDING and are retried.
- **Safety nets in the database:**
  - Triggers refuse any change to a CLAIMED/EXPIRED bounty, any decrease of a total, any edit of
    a contribution, a second refund, and any change to a PAID payout.
  - A partial UNIQUE index allows one ACTIVE bounty per target.
- **Exactly one ending.** A bounty ends exactly once. Claims also require `expires_at > death
  time`, so an expired bounty can never be claimed, and a claimed one can never expire.
- **Threads.** Every store call runs on the JoshyMC-DB thread (`databaseManager.async`), and the
  results come back to the main thread for messages.

## Expiration

- `expires_at` is stored when the bounty is created: creation time plus `expiration-hours`
  (default 168 = 7 days). Timestamps are UTC epoch milliseconds.
- Later contributions never move it, and changing the setting only affects new bounties.
- One repeating task (`sweep-interval-seconds`, default 60, never overlapping itself) does this,
  in order:
  1. Expires due bounties.
  2. Refunds pending contributions.
  3. Retries pending payouts (after `retry-delay-seconds`).
  4. Sends claim announcements a restart interrupted.
- It works from the stored timestamps, so expiration continues while targets are offline and
  across restarts.
- A contribution to a bounty that has run out but hasn't been swept yet expires it in the same
  transaction and starts a new bounty with a new id and timer.

## Claims

The death is read at MONITOR (`ignoreCancelled`) and never changed: drops, hearts and death
messages stay as they are. The heart system is never called.

Killer attribution is `damageSource.causingEntity`, the same as the heart system. The kill must
pass these checks, in order:

1. It's a player kill.
2. It's not a self-kill.
3. The killer is not an NPC and is online.
4. Both players are in Lifesteal worlds.
5. Not Ranked: neither player is held by or spectating a Ranked Team Wars match, and the death /
   killer position isn't inside a Ranked arena boundary (even without a match, so ranked arenas in
   the normal world are excluded).
6. Not an event: neither player is in the event world or a participant of a RUNNING hosted event.
7. Not an arena: neither player is in a Survival PvP arena zone.
8. Not teammates: `lifestealTeamManager.combatRelation`, with TEAMMATES, RECENT_TEAMMATES and
   UNKNOWN all refused.

The store then decides again inside the claim transaction:

- active and unexpired at the time of death;
- not teammates by the stored `lifesteal_team_members` rows;
- the 24h killer-target cooldown (`lifesteal_bounty_cooldowns`, persisted, keyed by UUIDs).

A refused claim never consumes the bounty. When the target had a bounty, the hunter is told why.

A victim at 1 heart is judged exactly like anyone else: hearts are never part of the decision.

## Anti-abuse

- **Self, teammates, events, Ranked, cooldown:** see Claims.
- **Duplicates:** each death gets a dedupe key (boot id + victim + server tick), payouts are
  UNIQUE per bounty, and every transition is conditional. Repeated events, retries, restarts and
  concurrent work can't pay twice.
- **Repeated kills:** every eligible kill of a bounty target is recorded (`lifesteal_bounty_kills`,
  pruned after 30 days). Reaching `anti-abuse.repeat-kill-threshold` kills of the same target
  inside the window writes a SUSPICIOUS audit row (and a console line). It is logged only and
  never blocks a claim.
- **Alt accounts:** JoshyMC has **no confirmed account-link system**. The only signal is
  AltManager's `player_connections`, salted hashes of connection addresses. When the hunter and
  the target share one, the claim is logged as SUSPICIOUS for staff review (`/bounty admin
  suspicious`). It is **not** blocked: a shared address is not proof (families, schools, VPNs,
  carrier NAT), and the issue says not to act on shared IPs alone.
  - Real alt abuse therefore can't be detected automatically or reliably. Staff handle it with
    the usual punishment tools.

## Statistics and leaderboards

- `lifesteal_bounty_stats` keeps lifetime counters per UUID:
  - claims and coins earned, updated only in the payout transaction;
  - coins contributed and number of contributions, updated in the contribution transaction;
  - coins refunded.
- None of them are erased when a bounty ends.
- `/bounty stats` shows these plus your active contributions, each with its amount, the bounty
  total and its expiration.
- Leaderboards list players with at least one claim:
  - Richest: coins earned, then claims, then UUID.
  - Most Successful: claims, then coins earned, then UUID.
- Leaderboard pages are cached for 30 seconds and the cache is cleared after a payout.

## Notifications

- **Public, Lifesteal players only:**
  - a new bounty (once, on creation; later contributions are not announced);
  - a claim, after the payout is committed. The `announced` flag is flipped once, so restarts
    and retries never repeat it.
- **Private:** stored in `lifesteal_bounty_notifications` inside the transaction that caused
  them, and delivered once (conditional `delivered_at`):
  - the target, when a bounty on them is created;
  - the hunter, with the exact recorded payout;
  - every refunded contributor.
- Delivery happens right away when the player is online, otherwise on their next join to (or
  entry into) Lifesteal. A player who leaves during delivery gets the notice again next time.

## Administration

All admin subcommands need `joshymc.lifesteal.bounty.admin`.

- `list`: active bounties with ids.
- `info <player>`: the latest bounties on a player, each with its contributors, amounts, refund
  states and payout. Audited through `AdminAudit`.
- `history <player>`: a player's statistics and claims. Audited.
- `pending`: payouts and refunds not completed yet, with attempts and errors.
- `failures`, `suspicious`, `audit`: the audit log (`lifesteal_bounty_audit`).
- `check`: the escrow total (active totals + pending refunds + pending payouts) and a consistency
  check. Totals must equal the sum of contributions, claimed bounties need one matching payout,
  and refund states must match the bounty state.
- `retry`: runs the normal sweep now. It is idempotent and audited, and it can't mint coins or
  touch escrow.

There is deliberately no command that sets balances, deletes bounties or refunds outside the
normal flow.

## Configuration (`lifesteal.yml > bounties`)

This section is read on its own, like the ranked divisions:

- A server whose lifesteal.yml predates it uses the bundled section (logged).
- A wrong value is severe. It only stops new contributions and claims; hearts, teams and the
  rest of Lifesteal keep running.

Keys:

- `enabled`
- `minimum-contribution` (10000)
- `expiration-hours` (168, 1..8760)
- `claim-cooldown-hours` (24, 0..8760)
- `sweep-interval-seconds` (60)
- `retry-delay-seconds` (300)
- `anti-abuse.repeat-kill-threshold`, `.repeat-kill-window-hours`, `.log-shared-connections`,
  `.console-logging`
- `gui.*-title`, `gui.contribution-presets`
- `messages.created|claimed|target-notified|hunter-paid|refunded|contributed`

The ranges are chosen so no setting can overflow a timestamp or an amount.

**Disabled, or the section is invalid:** no new contributions and no new claims. All data is
kept. Active bounties still expire on their stored time, contributors are still refunded and
pending payouts are still paid. The sweep keeps running with the last valid (or default) retry
settings, because doing so only returns escrow to its owners or pays rewards already won.

## Schema

These tables are new, created with `CREATE TABLE IF NOT EXISTS` at startup on the Lifesteal
server only. No migration of existing data is needed.

| Table | Contents |
|---|---|
| `lifesteal_bounties` | `id` (UUID), `target`, `target_name`, `state` ACTIVE/CLAIMED/EXPIRED, `total_cents`, `contributions`, `created_at`, `expires_at`, `ended_at`, `claimed_by`, `version` |
| `lifesteal_bounty_contributions` | `id` (transaction id), `bounty_id`, `contributor`, `contributor_name`, `amount_cents`, `source`, `created_at`, `refund_state` NULL/PENDING/REFUNDED, `refunded_at`, `refund_attempts`, `refund_next_at`, `refund_error` |
| `lifesteal_bounty_payouts` | `id`, `bounty_id` UNIQUE, `dedupe_key` UNIQUE, killer/target ids and names, `amount_cents`, `state` PENDING/PAID, `created_at`, `paid_at`, `attempts`, `next_attempt_at`, `last_error`, `announced` |
| `lifesteal_bounty_stats` | per-UUID lifetime `claims`, `earned_cents`, `contributed_cents`, `contributions`, `refunded_cents` |
| `lifesteal_bounty_cooldowns` | `(killer, target)` → `last_claim_at` |
| `lifesteal_bounty_kills` | eligible kills of bounty targets, for repeated-kill detection |
| `lifesteal_bounty_notifications` | pending/delivered private notices |
| `lifesteal_bounty_audit` | every contribution, claim, refusal, payout, expiration, refund, failure, suspicious kill and admin retry |

The store also reads `economy` (through EconomyManager), `lifesteal_hearts` (has joined
Lifesteal), `lifesteal_team_members` / `lifesteal_teams` (stored team membership) and
`player_connections` (shared-connection signal).

## Known limitations

- **Alt detection** is a logged signal only (see Anti-abuse).
- **Mixed servers are not supported.** A server hosting both Survival and Lifesteal keeps
  bounties off, because its coin balances are Survival's.
- **Balances on the Lifesteal server:** `/balance` and `/pay` are SURVIVAL-scoped, so on the
  dedicated Lifesteal server players can't currently check or transfer coins with them. The
  confirmation screen shows the balance, and bounties work regardless.
- **Never-joined check:** "has joined Lifesteal" means the player has a Lifesteal heart record.
  A player whose first Lifesteal join happened while lifesteal.yml was invalid has none until
  their next join with hearts running.
- **Teams during the cache window:** claims check team membership both in the live team cache
  (which also covers recent teammates) and in the stored rows. A team change that was committed
  but not yet loaded into the cache is still caught by the stored check.
- **Organized PvP in the normal world:** event exclusion covers the event world, running hosted
  events and Survival PvP arenas. Organized PvP held in the normal Lifesteal world without the
  event system can't be told apart from ordinary PvP.

## Manual tests

On a Lifesteal test server (`gamemode-lock.server-gamemode: lifesteal`) with two or three
accounts:

1. `/bounty` with no bounties shows the empty note. `/bounty add <other> 5000` is refused with the
   minimum. `/bounty add <other> 10000` takes $10,000, announces to Lifesteal players and notifies
   the target.
2. Add more from a second account. The total stacks, no new announcement is sent, and the expiry
   is unchanged.
3. Kill the target with a third account. The full total is paid, the claim is announced once and
   `/bounty top` lists the hunter. Kill them again after a new bounty: the cooldown message
   appears and the bounty stays.
4. Teammates: create a Lifesteal team. Contributing and claiming are refused.
5. Set `expiration-hours: 1`, reload, create a bounty and wait. Contributors are refunded, and
   offline ones are told on join.
6. Menus: page through more than 45 bounties, use Place a Bounty / Custom Amount, and try to
   drag or shift-click items into the menus. Open the confirmation in two windows and confirm
   after the bounty ended: refused.
7. On the Survival server, `/bounty` is the old Survival command and unchanged.
