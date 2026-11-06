# Guided tutorial walkthrough (issue #1387)

New players learn the server by doing seven real objectives, one at a time. Survival and Lifesteal
each have their own seven objectives, progress and one-time Money reward. There is no tutorial GUI,
no tutorial world, no forced path, and nothing is ever taken, reset or teleported.

Code: `src/main/kotlin/com/liam/joshymc/tutorial/`

| File | Role |
|---|---|
| `TutorialRules.kt` | Bukkit-free: the two fixed step lists, first-join/resume decision, how each signal moves the current objective, walking, which economy may pay a reward, formatting |
| `TutorialStore.kt` | Bukkit-free SQLite store: progress per (UUID, gamemode), the reward entitlement, `installed_at` |
| `TutorialManager.kt` | Sessions, listeners (join, quit, world change, walking), chat instructions, action bar, completion celebration, reward payout, `/tutorial` actions |
| `command/TutorialCommand.kt` | `/tutorial` (NETWORK scope) |
| `lifesteal/LifestealPvpInfoCommand.kt` | `/lifesteal pvp` (alias `rules`): read-only PvP / combat rules, added for Lifesteal objective 6 |
| `resources/tutorial-walkthrough.yml` | All settings and texts (missing keys fall back to the bundled file) |

## Player experience

- **Start**: a few seconds after a player's first join in a gamemode (`settings.join-delay-seconds`,
  after the first-join spawn teleport). On a server whose worlds map to both gamemodes, entering a
  world of the other gamemode stops this gamemode's display and starts/resumes the other one.
- **Chat**: when an objective begins (or resumes after a rejoin/restart) the player gets the header
  `✦ JOSHYMC TUTORIAL | STEP n/7`, an explanation, what to do, the objective, and a clickable
  `/tutorial skip` hint. It is sent once per objective (and once per resume), never repeated.
- **Action bar**: `✦ Tutorial (n/7) | <objective>` every `action-bar-interval-seconds` (2s; the
  client fades it after about 3s). It is skipped while the player is combat-tagged and for 3s after
  any other system's action bar (`commsManager.sendActionBar`: RTP warmup, quest progress, ...), and
  it is sent straight to the player, so it never delays those. Cleared on completion and skip.
- **Completion**: title "Tutorial Complete!" / "Welcome to JoshyMC!", the
  `ui.toast.challenge_complete` sound, the congratulation lines, then the reward line:
  "Reward Received: $50,000 Money" only after the deposit committed, "You've already claimed your
  tutorial reward!" on a replay, or a "saved, will be paid" line when paying failed.
- **`🍁`**: the issue's example header uses 🍁, which neither the Java default font nor Bedrock
  renders (it shows as a box), so the default header uses `✦`. It is configurable.

## Objectives and how each is detected

Every objective completes only from a success callback of the system that did the work
(`plugin.tutorialSignal(player, signal, origin)`), never from a typed command. The manager then
checks the player has an ACTIVE session **in their current gamemode**, the signal belongs to the
**current** step, and (for RTP/claims) that the origin world is in the same gamemode. Store writes
are conditional on the expected state and step, so duplicate events never advance twice.

### Survival

| # | Objective | Detected by |
|---|---|---|
| 1 | Walk 10 blocks | `PlayerMoveEvent` (MONITOR, ignoreCancelled): horizontal straight-line distance from an anchor set when the objective begins. Every teleport (`PlayerTeleportEvent`, any cause), respawn, world change and vehicle exit moves the anchor; moves while riding re-anchor; a single move longer than 8 blocks re-anchors. Nothing is saved per tick. |
| 2 | Claim the Starter Kit | `KitManager.claimKit` after the items were given and the cooldown stored, only for a kit in the Starter category of the player's gamemode. Failed, cooldown-blocked and permission-blocked claims return before the callback. |
| 3 | `/rtp` | `RtpCommand` after `player.teleport(destination)` returned true (warmup path) or the portal (skip-warmup) teleport succeeded. Moving during the warmup, combat tags and failed searches never reach it. Portal RTP counts (it is the same random teleport system); `/home`, `/warp`, `/spawn`, transfers don't call it. |
| 4 | Create a land claim | `ClaimManager.createClaim` after the claim row was inserted (overlaps, too large/small, not enough claim blocks and blocked worlds return a `Failure` first). Admin claims use a different method and never count. |
| 5 | Earn $100 | `SaleManager` (the one place every sell path reports): `/sell` (GUI, hand, all), `/shop` sell, `/market` sell and the Sell Wand now pass `earned = totalEarned`, the Money they deposited. Summed from 0 when the objective begins. See "Earnings" below. |
| 6 | Open Daily **and** Weekly Quests | `QuestCycleManager.openPage` once the Daily/Weekly page is really the open inventory. Either order; the main menu and Event Quests don't count. |
| 7 | `/discord` | `DiscordCommand` after the invite was sent. No joining, verification or linking. |

### Lifesteal

| # | Objective | Detected by |
|---|---|---|
| 1 | Walk 10 blocks | Same as Survival. |
| 2 | Claim the Lifesteal Starter Kit | Same callback; only kits whose kits.yml `gamemode` is `lifesteal`, so Survival items can never be handed out. |
| 3 | `/rtp` | Same callback. `/rtp` is SURVIVAL-scoped, so on a dedicated Lifesteal server it is refused; see "Continue" below. |
| 4 | Hearts | `/lifesteal info` (issue #1369, the existing read-only hearts screen) after it printed. Texts come from the live `lifesteal.yml` limits: start 10, max 20, min 1 by default, no elimination/revival, 1 heart per kill, same-victim cooldown, `/withdrawheart`. |
| 5 | Teams menu | `LifestealTeamMenu.open` once the menu is open. Solo players complete it; no team is required or changed. Texts use the live `teams.max-size` (5), invite expiry (60s) and friendly-fire setting. |
| 6 | Combat rules | `/lifesteal pvp` (new, read only): PvP state (grace period from the progression service), heart stealing/loss, the 1-heart minimum, killer at max gains nothing, same-victim cooldown, the combat tag duration (`combat.tag-duration-seconds`), combat-log NPC (`combat-logging.leave-npc`, never moves hearts) and team friendly fire, all read from the running services. |
| 7 | `/discord` | Same as Survival. |

### Continue (objectives that can't be done)

The `[Continue] (/tutorial continue)` button only appears, and the command only works (re-checked
server side), when an objective was already done earlier or the feature can't be used here:

- Starter Kit: no Starter Kit in this gamemode (Lifesteal ships none by default), the player has
  no permission for any of them, or they claimed one before (a `kit_cooldowns` row exists). No kit
  is ever given by the tutorial.
- RTP: `/rtp` missing, `joshymc.rtp` denied, or refused by its gamemode scope (dedicated Lifesteal).
- Claim: the player already owns a claim in a world of this gamemode, or `/claim` can't be used.
- Quests: the `quests` feature is off or `/quests` can't be used.
- Teams: Lifesteal teams are unavailable (`unavailableReason`).

Walking, earning, combat rules and Discord have no Continue.

## Earnings (Survival objective 5)

Counted: Money deposited for items sold through `SaleManager` (sell GUI, `/sell hand|all`, `/shop`
sell, `/market` sell, Sell Wand). Each sale is reported once, by the code that took the items and
paid; the balance is never read, so the existing balance can't count.

Not counted (excluded on purpose, because they are not verified gameplay earnings or can't be told
apart from transfers): `/pay` and every player-to-player transfer (trades, coinflips, Buy Orders
paying sellers out of the buyer's escrow, auctions, chest shops), admin `/eco`, the tutorial reward
itself, refunds, vote/AFK/quest/welcome/login-streak rewards, casino payouts, and store deliveries.

## `/tutorial`

| Command | What it does |
|---|---|
| `/tutorial` | Status in the current gamemode: state, current objective, reward state, clickable Start/Resume/Skip/Restart |
| `/tutorial start` | Starts a never-started tutorial, resumes/re-shows an active one; a skipped/completed one points to restart |
| `/tutorial skip` | Ends the active tutorial for this gamemode only, records SKIPPED, no reward, never resumes by itself |
| `/tutorial restart` | Back to objective 1 from any state (step progress only) |
| `/tutorial continue` | The Continue button (see above) |
| `/tutorial guide` | The old read-only Survival getting-started GUI (`tutorial.yml`); it no longer opens on first join |
| `/tutorial reload` | `joshymc.tutorial.reload` (op): reloads `tutorial.yml` and `tutorial-walkthrough.yml` |

No permission is needed for the player subcommands, and there is no player-facing admin reset.
`/tutorial` moved from SURVIVAL to NETWORK scope (`GamemodeAssignments`): it acts on the gamemode of
the world the player is in, so it works on the Lifesteal server too.

## Persistence

All in the server's own `data.db` (each network server has its own, so gamemodes never share rows
across servers; on a mixed server the `gamemode` column keeps them apart).

- `tutorial_progress` (PK `uuid, gamemode`): `state` (ACTIVE / SKIPPED / COMPLETED; no row =
  NOT_STARTED), `step` (0-based; 7 once completed), `step_progress` (earned Money, or the quest bits
  1 = Daily, 2 = Weekly), `started_at`, `updated_at`, `completed_at`, `skipped_at`, `completions`.
- `tutorial_rewards` (PK `uuid, gamemode`): `status` PENDING / PAID, `amount`, `created_at`,
  `paid_at`, `attempts`, `last_error`. Never touched by skip or restart.
- `tutorial_meta`: `installed_at`.

Writes happen when the tutorial starts, an objective completes, earnings/quest progress change, on
skip, restart and completion, and when a reward is paid: synchronously on the main thread (small
indexed writes, like the kit cooldown write), so an advance can't be reordered behind a later write.
Walking is never saved per tick (the anchor lives in memory; a rejoin re-anchors where they are).

### Reward idempotency

1. Completing the last objective runs one transaction: `UPDATE ... SET state = COMPLETED WHERE
   state = ACTIVE AND step = 6` and, only if that changed the row, `INSERT OR IGNORE` the PENDING
   reward with the configured amount. Concurrent handlers: only one UPDATE matches.
2. Paying runs one transaction: `UPDATE tutorial_rewards SET status = PAID WHERE status = PENDING`
   and `economyManager.deposit`. A refused or failing deposit throws and rolls both back, so the
   reward stays PENDING and is never marked paid without money. Failures are logged with `attempts`
   and `last_error`.
3. A PENDING reward is retried on every join. A replay after PAID finds the existing row and pays
   nothing; a player who skipped (no row) can still earn it by replaying.
4. Survival rewards are paid where Survival is hosted; Lifesteal rewards only on a server that hosts
   nothing but Lifesteal (`TutorialRules.economyBelongsTo`). On a server that maps worlds to both,
   the one `economy` table is Survival's, so a Lifesteal reward stays PENDING there and the player
   is told it is saved.

### Existing players (migration)

`installed_at` is recorded the first time this version starts on a server. A player with no
tutorial row only gets it started automatically if their first join on this server
(`OfflinePlayer.firstPlayed`) is at or after `installed_at`. Everyone who played before keeps
playing uninterrupted and can start it with `/tutorial start`. Playtime is never used. Because each
network server has its own `installed_at`, the first join on a brand-new Lifesteal server starts the
Lifesteal tutorial even for long-time Survival players, and only once (the row then exists, so proxy
transfers back and forth never retrigger it).

## Configuration (`tutorial-walkthrough.yml`)

| Key | Default |
|---|---|
| `settings.survival.enabled` / `.reward` | `true` / `50000` |
| `settings.lifesteal.enabled` / `.reward` | `true` / `25000` |
| `settings.walk-distance` | `10` (1-1000) |
| `settings.earn-amount` | `100` |
| `settings.action-bar-interval-seconds` | `2` (1-10) |
| `settings.join-delay-seconds` | `3` (0-30) |
| `messages.*` | header, action-bar format, objective/skip/continue lines, completion title, subtitle, sound key, chat lines, reward lines |
| `survival.<step>.*`, `lifesteal.<step>.*` | per-objective `action-bar`, `lines`, `objective`, `continue` |

The seven steps and their order are code (`TutorialRules`); config can't add, remove or reorder
them. A changed reward amount applies to entitlements created after the change.

## Checks

`./gradlew -I scripts/tutorial-check.init.gradle.kts tutorialCheck`: step lists, first join /
resume / no auto-restart, every objective's signal (and that no other signal or teleport counts),
earnings accumulation and invalid amounts, Daily+Weekly in either order, walking rules, which
economy pays, then the store against real SQLite: no duplicate session, conditional advances,
progress persistence, skip/restart, completion racing on four threads (one winner), failed and
throwing payments staying PENDING with no money moved, paying once, replays never paying, skipped
players earning it later, gamemode-separate rewards; and the wiring of every callback.

## Manual checks (need a live Paper / Velocity setup; not run)

- First join on Survival: tutorial starts after spawn; each objective's chat appears once; action
  bar refreshes and yields to RTP warmup / combat.
- Walk by `/spawn`ing or riding a boat: no completion; walking 10 blocks: completes.
- `/kits` → Starter Kit claim completes; a second claim on cooldown shows the Continue line.
- `/rtp` with movement during warmup (cancelled) doesn't count; a completed one does.
- Wand claim overlapping another claim doesn't count; a valid one does.
- `/pay` from another player and `/eco give` don't move the earnings line; `/sell hand` does.
- Daily then Weekly (and the reverse) complete objective 6; Event Quests don't.
- Completion: title, sound, "Reward Received: $50,000 Money", balance +50,000; `/tutorial restart`
  and completing again: "already claimed", balance unchanged.
- Lifesteal server: tutorial starts on first Lifesteal join; RTP shows Continue; `/lifesteal info`,
  `/team`, `/lifesteal pvp`, `/discord` complete 4-7; reward $25,000 into the Lifesteal server's
  balance.
- Transfer Survival ↔ Lifesteal mid-tutorial: each server resumes its own progress.
- Bedrock (Geyser): action bar and chat readable; Continue usable by typing `/tutorial continue`.

## Compatibility

- **Existing tutorial**: `/tutorial` no longer opens the GUI by default and it no longer opens on
  first join; the GUI is `/tutorial guide`. `tutorial.yml` is unchanged.
- **Kits / claims / quests**: no behaviour changed; each only gained a success callback after its
  existing success path. `SaleManager.recordSale(s)` gained an optional `earned` argument.
- **Lifesteal**: no Money commands exist there yet (`/balance` is Survival-scoped), so the $25,000
  lands in the Lifesteal server's own `economy` balance for when they arrive. There is no Lifesteal
  Starter Kit or RTP by default, so those objectives offer Continue until they exist.
