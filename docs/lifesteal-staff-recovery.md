# Lifesteal closed beta: staff recovery handbook (issue #1561)

How staff investigate and fix player problems on the Lifesteal server **with the tools that
already exist**, without unnecessary restarts and without risking permanent data loss.

This handbook only covers recovery. For other topics see:

- Release, restart safety, backups and rollback: [lifesteal-beta-release.md](lifesteal-beta-release.md)
- Ranked QA sessions and test data resets: [lifesteal-ranked-qa.md](lifesteal-ranked-qa.md)
- Bug report template: [lifesteal-ranked-bug-report.md](lifesteal-ranked-bug-report.md), [lifesteal-beta-templates.md](lifesteal-beta-templates.md)
- System internals: [lifesteal-hearts.md](lifesteal-hearts.md), [lifesteal-ranked.md](lifesteal-ranked.md),
  [lifesteal-ranked-beta.md](lifesteal-ranked-beta.md) (sections G, K, L), [lifesteal-combat-npc.md](lifesteal-combat-npc.md),
  [lifesteal-bounties.md](lifesteal-bounties.md), [lifesteal-bank.md](lifesteal-bank.md), [event-inventory.md](event-inventory.md)
- Staff ranks and protection rules: [staff-permissions.md](staff-permissions.md)

Every command below was checked against the source at the time of writing. If a command answers
"Usage: ...", trust that over this page.

---

## 0. Ground rules (read before touching anything)

1. **Look first, change second.** Every system below has a read-only command. Run it, screenshot
   it, and only then decide. Most problems settle by themselves on the player's next join.
2. **One change, one time.** Every recovery command that changes data is single-use by design
   (conditional updates, UNIQUE dedupe keys). If a command says "already ...; nothing changed",
   stop: the fix already happened. Never try a *different* command to force the same result.
3. **Never edit `data.db` by hand** (SQLite browser, `sqlite3`, scripts). The tables have triggers,
   version columns and append-only audit rows; a manual edit can break the invariants every
   recovery tool relies on, and it leaves no audit trail. If the tools can't fix it, escalate.
4. **Never delete snapshots or recovery rows.** Ranked snapshots, event inventory snapshots,
   Combat NPC sessions, Heart item rows and bounty payouts are the only copy of what a player is
   owed. There is no command to delete them, on purpose.
5. **Don't restart to "unstick" things** while a transaction is unresolved (a ranked match is
   running, a restore is RESTORING, a Combat NPC is standing, a bounty sweep is mid-run). A clean
   shutdown is handled (see section I), but a restart never fixes data: it only moves the problem
   to the player's next join, with fewer people online to check it.
6. **Never clear player data to fix a login.** Don't delete `world/playerdata/<uuid>.dat`, don't
   use `/playerdata reset` (that is a ban-time wipe of inventory, ender chest, vaults, claims and
   homes, `joshymc.playerdata.reset`), and don't use the `/admin` panel's inventory rollback on a
   player who is held by ranked, has a standing/unsettled Combat NPC, or has an unsettled event
   inventory. Those systems *set* the inventory from their own snapshot and depend on the player
   file being what they left.
7. **Staff protection applies.** Commands that change another player refuse yourself and anyone
   of equal or higher staff rank (console is always allowed). Don't work around a refusal by
   asking a higher-ranked friend to run it "quickly": escalate properly and write down why.
8. **Record every action** in the staff channel: who, what command, why, the output. Most of the
   commands below also write `admin_logs` rows (AdminAudit), but your notes are what explain them.

### Evidence to preserve (every case)

- Screenshots of the player's report and of every command output (before and after).
- Player name **and UUID**, edition (Java/Bedrock), exact time with timezone.
- Match id / serial / incident id / snapshot id / bounty id, whichever applies.
- The console log around the time (SEVERE/WARNING lines tagged `[Hearts]`, `[Ranked]`, `[Bank]`,
  `[Bounty]`, `[Combat]`, event inventory). Copy `logs/latest.log` before a restart rotates it.
- For anything that may need the Owner: ask the Owner/host to take a **copy** of `data.db` (and
  `data.db-wal` if present) while it's safe to do so, per
  [lifesteal-beta-release.md](lifesteal-beta-release.md) section 1.7. Staff never touch the copy.

---

## 1. Tool inventory

All of these are `default: op` in plugin.yml; grant them through LuckPerms. They are **not**
inherited from staff ranks automatically. "Online" means the target must be online for that
command; everything else works for offline players the server has seen.

### One-page diagnostics (`/lifesteal admin diag`, issue #1564)

Start here when you don't know yet which system a problem belongs to. Both views are read-only:
they read memory through the systems' existing accessors and the database with small indexed
lookups on the database thread (the reply comes a moment later), start no repeating task, and
can't change hearts, inventories, Elo, coins, matches or progression.

| Command | Permission | Changes data? |
|---|---|---|
| `/lifesteal admin diag player <player\|uuid>` | `joshymc.lifesteal.diagnostics` | no. Works for offline players (name the server has seen, or a UUID). Shows UUID/edition, online + world, applied hearts, combat tag, ranked hold/spectating, progression phase, and from the database: stored hearts (or UNREADABLE), the last 5 heart changes, outstanding Heart items, an open ranked roster row, PENDING/RESTORING/REVIEW ranked snapshots, open Combat NPC sessions (state, place, deadline; also unreadable ones), unsettled event inventories, bank balance + unreviewed failed transactions, pending bounty payouts/refunds. Staff protection applies (not equal/higher staff; yourself is fine). Each lookup writes a `LIFESTEAL_DIAG_PLAYER` row to `admin_logs`. |
| `/lifesteal admin diag health` | `joshymc.lifesteal.diagnostics` | no. lifesteal.yml status, hearts/ranked/combat logging/bank/bounties running or not, ranked matches in memory, standing Combat NPCs, progression status/phase/problem, whether the database answers (and how fast), and counts of ranked snapshots waiting, open Combat NPC sessions, unsettled event inventories, pending bounty payouts/refunds and unreviewed bank failures. |

`joshymc.lifesteal.diagnostics` is `default: op` with no children, and nothing else grants it.
It does not give any of the permissions below. A section that says "not set up on this server"
means that system's tables don't exist here; "couldn't be read" means that query failed (the
other sections are still shown). Use the system's own command below for details and fixes.

### Hearts and Heart items (`/lifesteal hearts`)

| Command | Permission | Changes data? |
|---|---|---|
| `/lifesteal hearts get <player>` | `joshymc.lifesteal.hearts.admin` | no (also shows playtime and withdrawal eligibility) |
| `/lifesteal hearts set\|add\|remove <player> <amount>` | `joshymc.lifesteal.hearts.admin` | **yes** (staff protection + ranked hold checked first; `HEART_SET/ADD/REMOVE` in `admin_logs`) |
| `/lifesteal hearts items <player>` | `joshymc.lifesteal.hearts.admin` | no (outstanding ISSUED serials, newest 20) |
| `/lifesteal hearts item <serial>` | `joshymc.lifesteal.hearts.admin` | no (owner, source, issue/close transactions) |
| `/lifesteal hearts recent [issued\|redeemed\|voided\|all] [page]` | `joshymc.lifesteal.hearts.admin` | no (all players, default `issued`) |
| `/lifesteal hearts void <serial> [refund]` | `joshymc.lifesteal.hearts.admin` | **yes**, once per serial (`refund` only for withdrawn items) |
| `/lifesteal hearts reviews [player]` | `joshymc.lifesteal.hearts.admin` | no (Heart items redeemed by a shared-connection account) |
| `/lifesteal hearts give <player> <amount>` | `joshymc.lifesteal.hearts.give` | **yes**, online only. Not a recovery tool (see C, D). |
| `/lifesteal hearts monitor [open\|all\|<player>\|<id> [reviewed\|dismissed\|open [note]]]` | `joshymc.lifesteal.hearts.monitor` | only the review status of a flag |
| `/alts <player>` | `joshymc.alts` | no |

### Ranked Team Wars (`/lifesteal admin ranked`)

| Command | Permission | Changes data? |
|---|---|---|
| `/lifesteal admin ranked list` | `joshymc.lifesteal.ranked.admin` | no (running matches) |
| `/lifesteal admin ranked info <match>` | `joshymc.lifesteal.ranked.admin` | no |
| `/lifesteal admin ranked cancel <match> [confirm]` | `joshymc.lifesteal.ranked.admin` | **yes**: no winner, everyone restored. Refused for that match's own players/teams. |
| `/lifesteal admin ranked remove <player> [confirm]` | `joshymc.lifesteal.ranked.admin` | **yes**, online only: restores and moves them back; before the fight it cancels the match |
| `/lifesteal admin ranked restores` | `joshymc.lifesteal.ranked.admin` | no (snapshots in REVIEW) |
| `/lifesteal admin ranked restore <player>` | `joshymc.lifesteal.ranked.admin` | online only: applies a PENDING snapshot, or explains a RESTORING/REVIEW one |
| `/lifesteal admin ranked restore <player> apply\|resolve confirm` | `joshymc.lifesteal.ranked.admin` | **yes**, online only, REVIEW snapshots only |
| `/lifesteal admin ranked spectate <match>` / `unspectate` | `joshymc.lifesteal.ranked.spectate` (or `.admin`) | no |
| `/lifesteal admin ranked queue` / `queuelog` | `joshymc.lifesteal.ranked.admin` | no |
| `/lifesteal admin ranked cancelqueue <entry\|team> [confirm]` | `joshymc.lifesteal.ranked.admin` | queue only (memory), never inventories/hearts |
| `/lifesteal admin ranked cancelproposal <proposal> [confirm]` | `joshymc.lifesteal.ranked.admin` | queue only |
| `/lifesteal admin ranked rating status\|player <p>\|team <team>\|match <match>\|failed` | `joshymc.lifesteal.ranked.admin` | no |
| `/lifesteal admin ranked rating retry <match> [confirm]` | `joshymc.lifesteal.ranked.admin` | Elo for a FAILED result only |
| `/lifesteal admin ranked stats player <p>\|team <team>\|consistency [season]\|divisions` | `joshymc.lifesteal.ranked.admin` | no |
| `/lifesteal admin ranked reviews [player]` | `joshymc.lifesteal.ranked.admin` | no (anti-farming log) |

### Combat logging and Combat NPCs (`/lifesteal combat`)

| Command | Permission | Changes data? |
|---|---|---|
| `/lifesteal combat status <player>` | `joshymc.lifesteal.combat.admin` | no (current tag / session) |
| `/lifesteal combat logs [player]` | `joshymc.lifesteal.combat.admin` | no |
| `/lifesteal combat incident <id>` | `joshymc.lifesteal.combat.admin` | no (classification, NPC left or not, heart transaction) |
| `/lifesteal combat failures` | `joshymc.lifesteal.combat.admin` | no (write failures since the last restart) |

There is no Combat NPC admin command that changes anything; open sessions are listed (read-only)
by `/lifesteal admin diag player <player>` and counted by `/lifesteal admin diag health`.

### Economy: bank and bounties

| Command | Permission | Changes data? |
|---|---|---|
| `/bank admin view <player>` / `history <player> [page]` / `transfers <player>` | `joshymc.lifesteal.bank.admin` | no |
| `/bank admin suspicious` / `failed [all]` / `summary` | `joshymc.lifesteal.bank.admin` | no |
| `/bank admin review <id> <note>` | `joshymc.lifesteal.bank.admin` | marks a failed (rolled back) transaction as investigated; moves no coins |
| `/bank admin add\|remove <player> <amount> <reason>` | `joshymc.lifesteal.bank.admin.adjust` | **yes** (capped, staff protection, AdminAudit) |
| `/bounty admin list` / `info <player>` / `history <player>` | `joshymc.lifesteal.bounty.admin` | no |
| `/bounty admin pending` / `failures [page]` / `suspicious [page]` / `audit [page]` / `check` | `joshymc.lifesteal.bounty.admin` | no |
| `/bounty admin retry` | `joshymc.lifesteal.bounty.admin` | runs the normal sweep now; idempotent, audited, can't mint coins |

### Teams, progression, event inventories, general

| Command | Permission | Changes data? |
|---|---|---|
| `/team admin info <team>` / `pvp <player> <player>` / `progression <team>` | `joshymc.lifesteal.team.admin` | no |
| `/team admin disband <team> [confirm]` / `transfer <team> <player> [confirm]` | `joshymc.lifesteal.team.admin` | **yes**; refused during ranked play |
| `/lifesteal admin launch status\|audit` | `joshymc.lifesteal.progression.admin` | no |
| `/lifesteal admin launch schedule <timestamp> [confirm]` / `activate [confirm]` | `joshymc.lifesteal.progression.admin` | **yes, Owner decision only**; a launched season can't be moved or reset |
| `/event inventory list` / `info <player\|uuid>` | `joshymc.event.inventory.recover` | no |
| `/event inventory apply <id> confirm` | `joshymc.event.inventory.recover` | **yes**, REVIEW rows with items, player online and outside the event world |
| `/event inventory resolve <id> confirm [note]` | `joshymc.event.inventory.recover` | marks REVIEW as RESOLVED; gives nothing |
| `/unstuck <player>` | `joshymc.unstuck.others` | moves a genuinely softlocked player (queues offline players) |
| `/invsee <player>` | `joshymc.invsee` (editing: `joshymc.invsee.edit`) | read-only without `.edit`. **Use read-only for investigations.** |
| `/jgamemode info` | (see staff-permissions.md) | no (server gamemode, lifesteal.yml status) |
| `/history <player>` | punishment staff | no |

---

## 2. Recovery procedures

Each procedure: **Symptoms → Initial checks → Permission → Commands → Safe procedure → Don't →
Escalate → Evidence.** The general evidence list in section 0 always applies.

### A. Player stuck in ranked

- **Symptoms:** can't use commands, drop items, open containers or teleport; "you're in a ranked
  match" after the match should have ended; stuck inside an arena; can't join/leave a team.
- **Initial checks:** `/lifesteal admin ranked list`, then `/lifesteal admin ranked info <match>`
  (state, players, restoration). `/lifesteal combat status <player>` for a combat tag. Ask if they
  are dead (restores happen on respawn) or were offline (restores happen on join).
- **Permission:** `joshymc.lifesteal.ranked.admin`.
- **Safe procedure:**
  1. Match still running normally (COUNTDOWN/ACTIVE) and the player just doesn't like it: nothing
     to do; ranked is meant to hold them.
  2. Player is dead: have them respawn. Restore runs on respawn.
  3. Match genuinely stuck (no progress, max time far exceeded, arena broken):
     `/lifesteal admin ranked remove <player>` (preview) → `... remove <player> confirm` for one
     player, or `/lifesteal admin ranked cancel <match>` → `... cancel <match> confirm` for the
     whole match. Both restore and move players back. Cancel = no winner; hearts already moved
     stay moved.
  4. If the cancel is refused because you play in that match or your team does, a different staff
     member must do it (issue #1498, by design).
  5. Stuck in a queue/proposal, not a match: `/lifesteal admin ranked queue`, then
     `cancelqueue <entry|team> confirm` or `cancelproposal <proposal> confirm`. These never touch
     inventories or hearts.
- **Don't:** teleport them out with `/tp`, change their game mode, `/heal`, give items, or use
  `/sudo` (all refused for held players, and trying to work around it breaks the restore).
- **Escalate to Owner:** the match won't cancel (error), the player stays held after cancel/remove,
  or it happens repeatedly.
- **Evidence:** match id, `info` output before and after, queue/proposal ids.

### B. Player inventory not restored (ranked)

- **Symptoms:** after a match the player has the match kit, an empty inventory, or old items.
- **Initial checks:** `/lifesteal admin ranked restores` (anything in REVIEW?), then, with the
  player online and alive, `/lifesteal admin ranked restore <player>` without a mode. It either
  restores a PENDING snapshot or tells you the state:
  - *"has no ranked inventory waiting"*: the snapshot was already restored. Investigate elsewhere
    (death after the match, trade, `/invsee` read-only).
  - *RESTORING ("database hasn't confirmed it yet")*: it is retried automatically and **never
    applied again**. Wait; do nothing.
  - *REVIEW*: the server couldn't tell whether the restore landed. Continue below.
- **Permission:** `joshymc.lifesteal.ranked.admin`.
- **Safe procedure for REVIEW:**
  1. Check the live inventory with `/invsee <player>` (read-only) and ask the player what they had
     before the match.
  2. If the pre-match items **did not** come back: `/lifesteal admin ranked restore <player> apply confirm`.
     This *replaces* the current inventory, XP and effects with the snapshot (so anything gained
     since is overwritten: tell the player to stash new items first, ideally in an ender chest
     they confirm stays untouched).
  3. If they **did** come back: `/lifesteal admin ranked restore <player> resolve confirm`. Nothing
     is applied; the row is closed.
- **Don't:** run `apply` "just in case", run it twice, run `apply` and then also give items by
  hand, or use `/admin` rollback / `/invsee` editing to rebuild the inventory.
- **Escalate to Owner:** the snapshot can't be read or applied ("see the console"), the player
  disputes what the snapshot holds, or valuable items are involved and you aren't sure.
- **Evidence:** snapshot id, match id, `restores`/`restore` output, `/invsee` screenshots before.

### C. Incorrect permanent heart count

- **Symptoms:** player says they have too few/many hearts, or their health doesn't match.
- **Initial checks:** `/lifesteal hearts get <player>`. Three possible answers:
  - normal value: compare with what the player expects. Many "wrong" counts are correct: the
    victim at the minimum keeps their heart, a killer at the maximum gains nothing (the victim
    still loses one), the same-victim cooldown, grace period, teammates/allies, ineligible kills
    (fly, god mode, vanish, over-limit enchants), and ranked forfeits. See
    [lifesteal-hearts.md](lifesteal-hearts.md) "Rules".
  - "stored X, applied as Y": out of range after a limit change; applied clamped. Not a bug.
  - "stored hearts are unreadable": a malformed row (kills involving them are refused).
  Also check `/lifesteal combat logs <player>`, `/lifesteal hearts items <player>` (withdrawn
  items still out), and `/lifesteal hearts monitor <player>`.
- **Permission:** `joshymc.lifesteal.hearts.admin`.
- **Safe procedure:**
  1. Only correct a count after you've found the specific event that went wrong (a death that
     should have been refused, a crash right after a withdrawal, etc.) and know the right number.
  2. Withdrawn item lost in a crash: that is procedure D's "void + refund", **not** `add`.
  3. Otherwise: `/lifesteal hearts add <player> 1` (or `remove`/`set`). One command, then `get`
     again. "already has N hearts; nothing changed" means stop.
  4. Unreadable row: `/lifesteal hearts set <player> <hearts>` is the only repair. Agree the value
     with a senior staff member first.
  5. Refused because they're in ranked: wait for the match or use procedure A first.
- **Don't:** give replacement hearts on the player's word alone, give Heart *items*
  (`hearts give`) as compensation (they can be traded), or "round up" for goodwill.
- **Escalate to Owner:** any correction of more than one heart, anything touching a staff member
  or their friends, alt suspicion, or a pattern across several players (possible bug).
- **Evidence:** `get` output before/after, the death/kill time, combat incident id, the reason.

### D. Suspected duplicated heart item

- **Symptoms:** more Heart items around than were withdrawn, two identical-looking items, a
  player redeeming "free" hearts.
- **Initial checks:** ask for the item; `/lifesteal hearts item <serial>` needs the serial (staff
  see it via `recent`/`items`; it isn't shown to players). `/lifesteal hearts recent redeemed`,
  `/lifesteal hearts items <player>`, `/lifesteal hearts reviews [player]`, `/alts <player>`.
- **Facts that settle most cases:** each Heart item is only real while its serial is ISSUED.
  Copies share a serial, so only one copy can ever be redeemed; the others are refused. A
  redeemed serial can't be redeemed, voided or refunded again. A forged item has no row at all.
  So a "dupe" can't create hearts; at worst it is a dead item.
- **Permission:** `joshymc.lifesteal.hearts.admin`.
- **Safe procedure:**
  1. Identify the serial and its status with `item <serial>`.
  2. If the original owner still has an ISSUED serial that has certainly left their possession
     against their will (crash), use procedure C/void below. If it is just a copy, nothing needs
     changing; the copy is worthless.
  3. If you want an outstanding ISSUED serial to stop being redeemable (e.g. a scam): only then
     `/lifesteal hearts void <serial>` (no refund). Refund only for a withdrawn item that the
     owner lost to a crash: `/lifesteal hearts void <serial> refund`.
- **Don't:** delete items from inventories with `/invsee` edit or `/clear` as "cleanup" (it
  destroys evidence), void a serial you haven't looked up, or refund a staff-granted item.
- **Escalate to Owner:** a redeemed serial that someone shows you again with a heart change, an
  item whose serial has no row but still redeemed, or anything that looks like real duplication
  of hearts (a bug). Also any alt/shared-connection case before punishing.
- **Evidence:** serial(s), `item` output, the item itself (ask the player to keep it), `reviews`
  and `/alts` output.

### E. Failed bounty payout

- **Symptoms:** hunter killed the target but got no coins; contributor wasn't refunded after
  expiry.
- **Initial checks:** `/bounty admin info <target>`, `/bounty admin history <hunter>`,
  `/bounty admin pending` (payouts/refunds not completed, with attempts and last error),
  `/bounty admin failures`, `/bounty admin check` (escrow consistency).
- **Facts:** a claim creates a PENDING payout in the same transaction; a failed payout rolls back
  and stays PENDING with its error and next retry time. Failures are retried by the sweep. Many
  "missing" payouts are refused claims (teammates, ranked, events, cooldown, ineligible attacker,
  grace period): see `history`/`audit`.
- **Permission:** `joshymc.lifesteal.bounty.admin`.
- **Safe procedure:** if `pending` shows the payout, either wait for the next retry or run
  `/bounty admin retry` once (safe to repeat, can't pay twice). Then `pending` again. If the
  same error repeats, stop and escalate.
- **Don't:** pay the hunter by hand with `/eco` or `/bank admin add`; if the PENDING payout later
  succeeds they'd be paid twice.
- **Escalate to Owner:** `check` reports any inconsistency, a payout keeps failing, or the
  payout is neither PAID nor PENDING but the claim says CLAIMED.
- **Evidence:** bounty id, payout state/error from `pending`, `check` output.

(Bank: a "failed" bank transaction was rolled back and moved nothing. Mark it investigated with
`/bank admin review <id> <note>`; use `/bank admin add|remove` only for a verified, Owner-approved
correction with a reason.)

### F. Player unable to join

- **Symptoms:** kicked right after joining, kicked with "Flying is not enabled", stuck at
  loading, or kicked repeatedly.
- **Initial checks:** the kick message and the console at their join time. `/history <player>`
  (ban?). `/lifesteal combat status <player>` (open Combat NPC session). `/event inventory info <player>`.
- **Known causes and what to do:**
  - Combat NPC row that can't be read: the join handler disconnects them **on purpose** rather than
    letting them play with an empty inventory. Don't fix by hand: escalate (see G).
  - "Flying is not enabled" kicks in unloaded chunks: `/unstuck <player>` (works offline, queues
    the move). Never enable `allow-flight`.
  - Event inventory REVIEW: doesn't block login, only keeps them out of the event world (see H).
  - Ban/whitelist/network transfer: not a recovery issue.
- **Permission:** `joshymc.unstuck.others`, `joshymc.lifesteal.combat.admin`, `joshymc.event.inventory.recover`.
- **Don't:** delete or rename `playerdata/<uuid>.dat`, run `/playerdata reset`, or remove the
  player's rows. Each recovery system recognises the player file it left; clearing it can lose a
  whole inventory permanently.
- **Escalate to Owner:** any repeat kick that isn't explained by the above, or a console stack
  trace on join.
- **Evidence:** exact kick message, console lines, UUID.

### G. Stuck Combat NPC

- **Symptoms:** a `[Combat Logger]` Mannequin standing long after 30 s; owner rejoins without
  their items; NPC drops twice; player kicked on join.
- **Initial checks:** `/lifesteal admin diag player <player>` (open session state, place and
  deadline, even for an unreadable row), `/lifesteal combat status <player>`, `/lifesteal combat logs <player>`,
  `/lifesteal combat incident <id>`, `/lifesteal combat failures`.
- **Facts:** the NPC lives for an absolute deadline (`combat-logging.npc.lifetime-seconds`), is
  non-persistent, and its session row holds the whole inventory. Expired ACTIVE sessions settle
  as SURVIVED; the owner gets the saved state on join. Untracked NPC entities are removed when
  their chunk loads. Settling is retried every 0.5 s.
- **Permission:** `joshymc.lifesteal.combat.admin`.
- **Safe procedure:** wait for the deadline, then have the owner rejoin. If the entity is still
  visible after the deadline, leave its chunk and come back (chunk load removes ghosts).
- **Don't:** kill the NPC yourself (that is a real death: items drop, hearts move), `/kill` or
  remove the entity, give the owner their items back by hand, or restart to make it go away.
- **Escalate to Owner:** the player is kicked on join (unreadable row), the NPC is still there
  after a chunk reload, or drops appear twice. There is no staff tool to settle a session or
  view its saved inventory (gap); `diag player` only shows that it exists and its state.
- **Evidence:** incident id, NPC location, screenshot, `failures` output, console lines.

### H. Failed event inventory restoration

- **Symptoms:** after leaving the event world the player has the event inventory or nothing; they
  can't re-enter the event world.
- **Initial checks:** `/event inventory list` (REVIEW rows first), `/event inventory info <player>`
  (rows, status, reason, live marker, audit).
- **Permission:** `joshymc.event.inventory.recover`.
- **Safe procedure:** ACTIVE/RESTORING/STALE rows settle on their own (join, world change). For a
  REVIEW row:
  1. Player online and **outside** the event world; check their current inventory read-only.
  2. Original items missing: `/event inventory apply <id> confirm` (claimed REVIEW → RESTORING
     first, so it can never be applied twice).
  3. Items already back, or the row holds nothing useful: `/event inventory resolve <id> confirm <note>`.
- **Don't:** apply while they're in the event world, apply and also hand items back, or edit
  `event_inventories.yml` / `event_inventory_snapshots`.
- **Escalate to Owner:** an orphan marker (no row), unreadable items, or a REVIEW you can't explain.
- **Evidence:** snapshot id, `info` output, console lines. Details: [event-inventory.md](event-inventory.md).

### I. Restart during a ranked match

- **What happens (no staff action needed):**
  - **Clean shutdown/restart:** every running match is cancelled and each online player is
    restored synchronously before the database closes. Nothing is decided at the quit (no
    forfeits, no hearts).
  - **Crash:** on startup FINISHING matches are recorded COMPLETED with their winner, everything
    else CANCELLED, and each player is restored on their next join. A restore that was RESTORING
    when the server died becomes REVIEW (procedure B).
  - Elo: a FAILED rating is retried by the 5-minute sweep (up to 5 times).
- **After the restart:** `/lifesteal admin ranked restores`, then for each affected player
  procedure B. `/lifesteal admin ranked rating failed` for rating failures; `rating retry <match>
  confirm` only for a match listed there.
- **Before a planned restart:** wait for running matches to finish or cancel them
  (`ranked list`), and check `restores` is empty. Follow [lifesteal-beta-release.md](lifesteal-beta-release.md)
  section 3.
- **Don't:** restart again to "retry" a restore, or restore players by hand.
- **Escalate to Owner:** any REVIEW you can't settle, or a match that is neither cancelled nor
  completed after startup.

### J. Suspected database corruption

- **Symptoms:** many unrelated commands answering "Couldn't read ...", SEVERE `SQLException`s,
  `database disk image is malformed`, every write rolling back.
- **Initial checks (read-only):** console log; `/lifesteal admin diag health` (does the database
  answer, and which systems are running); `/bounty admin check`;
  `/lifesteal admin ranked stats consistency`; `/lifesteal admin launch status`; `/jgamemode info`.
- **Permission:** none needed to observe; recovery is **Owner only**.
- **Safe procedure:**
  1. Stop all staff changes immediately. Tell staff in the channel.
  2. Don't restart repeatedly. Every write already rolls back on error, so the live server is
     usually safer than a restart that may fail to start.
  3. Escalate to the Owner, who decides between a jar rollback, a stop + file backup, or a full
     restore, per [lifesteal-beta-release.md](lifesteal-beta-release.md) section 7.
- **Don't:** open `data.db` in a tool, run `VACUUM`/`.recover`, delete `data.db-wal`/`-shm`, or
  copy a database file while the server is running.
- **Evidence:** console logs from the first error onward, list of affected commands/players, time.

---

## 3. Quick reference

| Problem | First command (read-only) | Fix (only after checking) | Never |
|---|---|---|---|
| Don't know where to start | `/lifesteal admin diag player <p>` / `diag health` | the system's own command | - |
| Stuck in ranked | `/lifesteal admin ranked list` / `info <match>` | `ranked remove <p> confirm` / `ranked cancel <match> confirm` | `/tp`, `/gamemode`, `/sudo` |
| Ranked items missing | `/lifesteal admin ranked restores` | `ranked restore <p>` → REVIEW: `apply confirm` **or** `resolve confirm` | apply twice, give items by hand |
| Wrong heart count | `/lifesteal hearts get <p>` | `hearts add\|remove\|set <p> <n>` once, verified | replacement Heart items |
| Lost withdrawn Heart item | `/lifesteal hearts items <p>` / `recent` | `hearts void <serial> refund` (once) | `hearts add`, `hearts give` |
| Duplicate Heart item | `/lifesteal hearts item <serial>` | usually nothing; `void <serial>` if needed | wipe inventories |
| Bounty not paid | `/bounty admin pending` | `/bounty admin retry` | pay by hand |
| Bank failure | `/bank admin failed` | `/bank admin review <id> <note>` | `add` without Owner OK |
| Can't join | console + `/lifesteal combat status <p>` | `/unstuck <p>` for fly kicks | delete `.dat`, `/playerdata reset` |
| Combat NPC stuck | `/lifesteal combat status <p>` | wait for deadline, reload chunk | kill/remove the NPC |
| Event inventory | `/event inventory info <p>` | `apply <id> confirm` **or** `resolve <id> confirm` | apply in the event world |
| Restart mid-match | `/lifesteal admin ranked restores` | procedure B per player | restart again |
| DB errors | console | Owner only | any manual DB edit |

**Escalate to the Owner** for: corrections over one heart, Owner-only actions (launch schedule,
bank adjustments, full restores), anything involving staff accounts, alt cases, repeated failures,
unreadable rows, or any "this shouldn't be possible" result.

---

## 4. Missing recovery tooling (gaps, not built here)

Documented only, unless marked otherwise.

1. **Only a short heart history.** `/lifesteal admin diag player` (issue #1564) shows the last 5
   `lifesteal_heart_audit` rows; there is no paged history or transaction-by-id view, so a longer
   dispute still needs the Owner to read the database copy.
2. **No Combat NPC session tool that settles anything.** Open sessions (also unreadable ones) are
   now listed by `diag player` / counted by `diag health`, but nothing shows a session's saved
   inventory or settles an unreadable one. An unreadable row keeps the player kicked until the
   Owner intervenes.
3. **Ranked `restore`/`remove` need the player online** (`getPlayerExact`). An offline player's
   PENDING/RESTORING/REVIEW snapshot (id, match, status, reason) is now visible with `diag player`
   and the totals with `diag health`, but not the snapshot's contents.
4. **No snapshot content viewer** for ranked or event inventory snapshots: staff decide
   apply/resolve by asking the player and looking at the live inventory.
5. **No team restore.** A disband (`/team admin disband`) or wrongful kick can't be undone;
   members must re-invite. Team bank/progression stay with the disbanded team id.
6. **No database integrity check command** (`PRAGMA integrity_check`, deliberately not added: it
   reads the whole file). `diag health` only shows whether the database answers; per-system
   consistency checks remain `/bounty admin check` and `ranked stats consistency`.
7. **Combat log write failures** (`/lifesteal combat failures`) are in memory only and are lost on
   restart: copy them before restarting.
