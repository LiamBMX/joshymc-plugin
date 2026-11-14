# Crate reward integrity (issue #1529)

Crates Phase 9A: signed voucher rewards, event-world reward loss, and crash-safe key/reward
transactions. Checks: `./gradlew -I scripts/crate-reward-integrity-check.init.gradle.kts crateRewardIntegrityCheck`.

## 1. Voucher rewards (CR-P1-01 + Fly Vouchers)

A crate reward stored as a Base64 snapshot of a voucher carries that voucher's `voucher_uuid`
and, for Credit/Rank (issue #1383) and Fly (issue #1304) Vouchers, a signature over it.

- **Root cause.** `CrateManager.buildRewardItem` gave Credit and Rank copies a fresh
  `voucher_uuid` but kept the template's `voucher_signature`, so every crate-won copy failed
  verification (and raised a "Forged Voucher Signature" anti-dupe alert). Fly Vouchers weren't
  re-minted at all, so every winner held the same `voucher_uuid` and only the first could redeem.
  `KitManager.claimKit` had the same Credit Voucher copy (issue #833 pattern).
- **Fix.** `util/VoucherCopies.kt` (`freshVoucherCopy`, `voucherTemplateProblem`) is the one
  path for handing out a voucher template. Each manager makes its own copy:
  `CreditVoucherManager.reissue`, `RankVoucherManager.reissue`, `FlyVoucherManager.reissue`
  (fresh uuid, amount 1, signature for the new uuid via the existing signing code; Chat Tag
  Vouchers only get a fresh uuid, they are unsigned).
- **Rules** (Bukkit-free `PremiumVoucherRules.reissue`): a correctly signed template is signed
  again for its new uuid; an unsigned legacy template stays unsigned (so it redeems exactly as
  long as `vouchers.accept-unsigned-legacy` allows); a forged template, or an unsigned one while
  legacy vouchers are refused, is **never** copied. `crateProblem` refuses such a crate before any
  key is taken ("Admin: crate ... reward #n ... is a Credit Voucher with an invalid signature").
  Fly Vouchers must verify (`FlyVoucherManager.verify`) to be copied.
- Redemption is unchanged: signature check, single-use id (`INSERT OR IGNORE` on the primary key
  of each redemptions table), Rank tier order / LuckPerms confirmation, Fly LuckPerms timed node.
  The rank stored on a Rank Voucher (what the signature covers) is copied as is, so no new rank,
  no Pioneer path, no broader grant is possible: only an already valid voucher can be copied.

## 2. Event world (CR-P1-03)

The event world swaps in a temporary inventory (`EventManager.saveInventory/restoreInventory`)
that is wiped on exit. Before, `onPlayerJoin` (and an animation finishing after a teleport) could
put a pending reward straight into it.

- `CrateDeliveryRules.route` (Bukkit-free): in the event world (`eventManager.isInEventWorld`,
  which also covers the 2-tick join gap) every row is **held**: it stays PENDING, untouched.
  Mod Mode delivers into `modmode_backups` in one DB transaction; otherwise the live inventory.
- Every delivery path goes through `claimPendingReward` → the route: join (40 ticks), the 30 s
  retry, an animation finishing (the player is told it is held), shutdown/reload settlement.
- No crate opens in the event world (`openableCrate`: "Your key was not used"), which also covers
  Instant/Select/Select 3/mass open and `/crate force`, whose rewards go to the live inventory.
- Leaving the event world: `onWorldChange` (MONITOR, 20 ticks later, after EventManager restored
  the inventory) delivers held rows; the retry task is the backstop. Each row is delivered once
  (conditional claim), so leaving and the retry can't both deliver it.
- Crate keys given inside the event world still go to /overflow (issue #1178), unchanged.

## 3. Transactions (CR-P1-04)

`crate_pending_rewards` gains `status`, `delivery_token`, `note`, `updated_at` (added with
`ALTER TABLE` after a `PRAGMA table_info` check; every existing row becomes `PENDING`, i.e.
deliverable exactly as before; nothing is deleted or reset). Bukkit-free `crate/CratePendingStore`.

| Status | Meaning |
|---|---|
| RESERVED | Written in the tick a key left the live inventory, before the player was saved. Never delivered. |
| PENDING | Deliverable (the key's removal is saved, or no key was involved). |
| DELIVERING | One delivery attempt owns it (conditional `PENDING → DELIVERING`) with a fresh token. |
| REVIEW | Ambiguous after a crash, held with evidence for staff. Never delivered or deleted automatically. |
| VOIDED | Staff decided it isn't owed. Kept. |

A delivered row is deleted only after the player was saved (that DELETE is the acknowledgement).

### Persistence ordering (`crate/CrateDeliveryProtocol`)

Animated RANDOM opens (Spin, Pulse, animated Instant) and mass-open rewards that didn't fit:

1. crate validation (`openableCrate`/`crateProblem`) → key validation (PDC) → key removed from
   the live inventory (memory) → reward drawn → reward item built (fresh signed voucher)
2. row inserted **RESERVED**
3. `player.saveData()` (the key removal is now on disk)
4. row **RESERVED → PENDING**

Delivery (`claimPendingReward`, inventory route):

1. row **PENDING → DELIVERING** with a fresh token (only one caller wins: double clicks, join +
   retry, shutdown + animation can't both deliver)
2. the stack, tagged `joshymc:crate_delivery = token`, goes into the inventory
3. `player.saveData()`
4. row deleted (or, if part didn't fit, keeps only the rest and goes back to PENDING)
5. the tag is stripped (memory; any left-over tag is stripped at the next join)

Mod Mode route: steps 1, 2 and 4 in one SQLite transaction (the real inventory is a DB row).

Instant (non-animated), Select and Select 3: the key and the reward both change only the live
inventory in the same tick, nothing is in the database, so they're saved (or lost) together with
the player file. That's consistent by construction and unchanged.

### Crash at each point

Assumption (Paper/vanilla): `saveData()` writes the player file to a temp file and renames it, so
a crash leaves either the old or the new file; autosave can't run inside the same tick.

| Crash point | State after restart | Result |
|---|---|---|
| before the key was taken / before reward selection | nothing durable | key still owned, no reward (26) |
| after selection, before key removal | nothing durable | same (27) |
| after the RESERVED insert, before the save finished | RESERVED, file still has the key | **REVIEW** (28) |
| after the save, before RESERVED → PENDING | RESERVED, file lacks the key | **REVIEW** (35) |
| after PENDING committed | PENDING, file lacks the key | delivered normally (29) |
| after the claim, before the save | DELIVERING, file has no tagged stack | back to PENDING, delivered again (30) |
| after the save, before the acknowledgement | DELIVERING, file has the tagged stack | acknowledged, not delivered again (30) |
| part delivered, crash before ack | DELIVERING, file has some tagged items | the rest stays PENDING |
| after the acknowledgement | row gone / remainder PENDING | tag stripped at join (31) |

The two RESERVED rows are the only points where the plugin can't prove which side of the save the
crash hit (keys are interchangeable, so the file can't tell "this key" apart). The window is one
synchronous `saveData()` call. Those rows are **never** granted or dropped automatically: at
startup they become REVIEW (logged with id, owner, label), the owner is told a reward is waiting
for a staff check. A `saveData()` that throws without a crash sends the rows to REVIEW at once.

Reconciliation of DELIVERING rows runs at `PlayerJoinEvent` LOWEST, synchronously, against the
inventory as loaded from the file, before the event world (2 ticks) or Mod Mode swap it. Each
attempt has its own token, so tags left from an earlier partial attempt never count for a newer
one. A DELIVERING row whose stored item can't be decoded goes to REVIEW.

### Staff reconciliation

`/crate rewards review` lists REVIEW rows (id, player, reward label, since, note).
`/crate rewards grant <id> confirm` makes it PENDING (delivered once by the normal path);
`/crate rewards void <id> confirm` marks it VOIDED (kept). Permission
`joshymc.crate.rewards.recover` (op). Without `confirm` the row is only shown. Each change is a
conditional UPDATE (`WHERE status = 'REVIEW'`), so repeating it does nothing, and is written to
AdminAudit (`CRATE_REWARD_REVIEW_GRANT` / `CRATE_REWARD_REVIEW_VOID`). Before granting, check the
player's inventory/key count and the server log around the crash.

## 4. What the automated checks prove

`CrateRewardIntegrityCheck` (Bukkit-free, real SQLite): reissue rules and signature behaviour
(the old signature fails on a fresh uuid, re-signed copies verify, two winners get different ids,
edited values/other secrets fail), single-use reservation semantics, routing, migration of
pre-#1529 rows, every store transition (duplicate ids, double claims, grant/void once), and the
protocol run with a crash injected at every step against a model of live inventory vs player
file (and room for partial deliveries). It also checks the wiring in CrateManager/KitManager/
CrateCommand/plugin.yml. It does **not** run Paper: whether a real hard kill behaves like the
model, and the Bukkit side of redemption, need the live checklist below.

## 5. Live Paper checklist (TEST server only)

Use isolated test accounts and disposable inventories; back up `plugins/Joshymc/data.db` and
`world/playerdata` before the crash tests. Never on production.

1. Put a signed Credit Voucher in a crate (editor), win it, redeem: Credits added, no anti-dupe alert.
2. Same with a Rank Voucher for a tier above the test account's: rank granted once.
3. Two accounts win the same Fly Voucher reward: both redeem; redeeming a copy of one is refused.
4. Quit during a Spin and during a Pulse: reward delivered once on rejoin.
5. Quit during a Spin, rejoin inside the event world: nothing delivered there; leave the event
   world: delivered once (inventory restored first).
6. Full inventory → reward held; join the event world; leave; free a slot: delivered once.
7. `/reload` (or stop) while a held reward belongs to a player in the event world: still held, delivered after leaving.
8. Normal restart during an animation: delivered once.
9. Forced kill (`kill -9`) right after opening an animated crate (several tries): after restart
   either the row was PENDING and the key gone, or it shows in `/crate rewards review`; never key + reward.
10. Repeated logins after the crash: no second delivery, no `crate_delivery` tag left on items.
11. Several held rewards for one player: each arrives once.
12. Mass open with an almost full inventory, interrupted (quit/kill) halfway: unopened keys stay, opened ones' rewards exist once.
13. `/crate rewards review`, then `grant <id>` (shows only), `grant <id> confirm` (delivers once), repeat (refused); `void` another (kept as VOIDED); check `admin_logs`.
14. Count keys and rewards before/after each case: nothing duplicated or lost.

## 6. Limitations

- The two RESERVED windows need a staff decision; that's deliberate (no automatic duplicate or loss).
- Mod Mode mass opens deposit into the backup row while the key leaves the live inventory, as before #1529.
- Select / Select 3 / Instant still drop items that don't fit at the player's feet (`giveItemSafely`), as before; their fit checks run first.
- A delivered tagged stack doesn't merge into existing partial stacks until the tag is stripped (same tick), so a held reward needs a free slot, which the retry already waits for.
