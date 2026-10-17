# Store purchase fulfillment (Tebex)

JoshyMC has no direct Tebex integration. The Tebex plugin runs **console commands** that
you configure per package in the Tebex dashboard. `/purchase fulfill` is the command
those packages should run. It records every purchase in JoshyMC's database first, then
delivers it, even when the buyer is offline.

## The command

```
purchase fulfill <recipient> <type> <args...> [reference]
```

- **Console only.** A player (even an op) running it gets refused, and the attempt is logged.
- **`<recipient>`**: the buyer's **UUID** (with or without dashes), or their **exact**
  in-game name. Names never match a prefix. Bedrock (Floodgate) players work by their
  Floodgate UUID, or by the name the server shows for them, prefix included (e.g. `.Steve`).
- **`[reference]`**: optional, but needed for duplicate protection (see below). 1-128
  characters from `A-Z a-z 0-9 . _ : # / -`.

| Type | Arguments | Offline buyer |
|---|---|---|
| `coins` | `<amount>` (e.g. `5000`, `1.5m`, up to 2 decimals, max 10T) | Deposited right away |
| `credits` | `<amount>` (up to 1 decimal, max 1,000,000,000) | Deposited right away |
| `tokens` | `<quantity>` | Queued, delivered on join |
| `cratekey` | `<crate> <quantity>` | Queued, delivered on join |
| `creditvoucher` | `<credits> <quantity>` | Queued, delivered on join |
| `rankvoucher` | `<tier> <quantity>` | Queued, delivered on join |
| `moneyvoucher` | `<physical-voucher-id> <quantity>` | Queued, delivered on join |
| `chattagvoucher` | `<tag-id> <quantity>` | Queued, delivered on join |
| `rank` | `<tier>` (donor tiers only: scout … pioneer) | Queued, granted on join |

Item quantities go from 1 to 2304. The arguments are checked when the command runs: an
unknown crate, tier, voucher or tag, or a bad amount, is **refused** and logged as
`REJECTED`.

The command never runs other commands. Every type calls the same code as the matching
admin command (`/eco give`, `/credits give`, `/crate givekey`, `/rankvoucher give`, …).

## Setting up packages in Tebex

> **Check the placeholder names in your own dashboard.** This change was written without
> access to Tebex's documentation, so the names below are the ones Tebex usually lists,
> **not verified**. Open a package's command editor and use the placeholder list it shows
> you. Run one test purchase and check the result with `/purchase list all`.

| You need | Usually listed as |
|---|---|
| The buyer's UUID | `{uuid}` (some setups use `{id}`) |
| The buyer's name | `{username}` |
| The transaction id | `{transaction}` |

Recommended package command (one per reward line):

```
purchase fulfill {uuid} credits 500 {transaction}
purchase fulfill {uuid} cratekey legendary 3 {transaction}
purchase fulfill {uuid} rank voyager {transaction}
```

- **Use the UUID placeholder** if your store has one. With only a name, a buyer who has
  never joined the server is **held as `UNRESOLVED`** instead of guessed. Give it to the
  right player with `/purchase assign`.
- **Turn off "require player online"** for these commands. JoshyMC queues the delivery
  itself, so they can run straight away.
- **Legacy commands that still need the buyer online.** `/credits give`, `/eco give`,
  `/tokens give`, `/crate givekey`, `/creditvoucher give`, `/rankvoucher give` and
  `/voucher give` only work for an online player. If a package still uses one of them,
  keep "require player online" **on** for it. They are unchanged.
- If the reference arrives with its braces still in it (e.g. `{transaction}`), the
  placeholder wasn't filled in. JoshyMC refuses it (`REJECTED`) instead of letting every
  purchase share one reference.

### Duplicate protection: what is and isn't guaranteed

- **With a reference**, JoshyMC stores the reference together with the recipient, type,
  arguments and quantity, and that combination is unique in the database. If Tebex runs
  the same command twice, it is recorded once and granted once, even across restarts or
  when both runs arrive at the same moment. The second run is logged as
  `Duplicate store command ignored`.
- Different commands in one transaction (e.g. Credits plus a crate key), or gifts to
  different players, don't collide.
- **Without a reference**, JoshyMC can't tell a retry from a second identical purchase,
  so **every run is granted**. There's no exactly-once guarantee in that case.
- **Watch out:** if one transaction can run the *same* command more than once on purpose
  (e.g. the package runs its command once per quantity bought), all those runs share one
  key and only the first is granted. For those packages, put the quantity in the amount
  (if your dashboard has a quantity placeholder), or add something per-run to the
  reference.

## What happens to each purchase

| State | Meaning |
|---|---|
| `PENDING` | Waiting: the buyer is offline, their inventory is full, or (for currencies) they've never joined. |
| `PROCESSING` | One delivery pass has claimed it. Item deliveries stay in this state only for one server tick. Rank grants stay in it while LuckPerms confirms. |
| `COMPLETED` | Delivered. Kept forever and never granted again. |
| `FAILED` | Needs staff. `last_error` says why (crate deleted, tier already held, repeated DB/LuckPerms errors). |
| `INTERRUPTED` | The server stopped in the middle of an item delivery. See below. |
| `UNRESOLVED` | The store gave a name that has never joined. Use `/purchase assign`. |
| `REJECTED` | The command was invalid. Fix the package; cancel the row once it's handled. |
| `CANCELLED` | Closed by staff. |

- **Coins and Credits.** The deposit and the `COMPLETED` mark are one database
  transaction, so either both happen or neither does. A database error leaves the row
  `PENDING`, and it's retried every minute (10 tries, then `FAILED`). If the buyer has
  never joined, the deposit waits for their first join, so no balance is ever opened
  for a UUID nobody has played on.
- **Items.**
  - Delivered **when the player joins** (about 5 s after), and **every minute** while
    they're online.
  - Items are handed over one at a time, only as many as fit. **Nothing is ever dropped
    on the ground.** What doesn't fit stays owed (`remaining`), and the player is told to
    make room.
  - Players in Mod Mode get the items in their saved inventory, like every other
    delivery.
  - Vouchers get a fresh unique voucher id for each item, so redemption protection is
    unchanged.
- **Ranks.**
  - Granted on join with a targeted `parent add`, never `parent set`. It shares the
    donor-change lock with Rank Vouchers and the Credit Shop.
  - It's only `COMPLETED` once LuckPerms shows the group.
  - If the player already holds an equal or higher tier, nothing is granted and the row
    becomes `FAILED` for staff to decide.
  - After 3 unconfirmed grants it becomes `FAILED`.

### The one window that can't be closed

An inventory and the database can't be written in a single transaction. To keep the
window small, JoshyMC claims the row, adds the items, saves the player's data right away,
and only then records the delivery, all in the same server tick. If the server dies
inside that tick, the row stays `PROCESSING`. On the next start it becomes `INTERRUPTED`
and is **never resent automatically**, because the items may already be in the player's
saved inventory.

## Staff tools

| Command | Who | What |
|---|---|---|
| `/purchase list [open\|pending\|failed\|interrupted\|unresolved\|rejected\|completed\|all\|<player>]` | `joshymc.purchase.admin` | The newest 20 matching rows (default `open`) |
| `/purchase info <id>` | `joshymc.purchase.admin` | Full record: recipient UUID, reward, remaining, reference, times, attempts, last error |
| `/purchase retry <id>` | op + permission, or console | `FAILED` → `PENDING`. Only the still-owed `remaining` is retried. |
| `/purchase retry <id> confirm` | op + permission, or console | Same for `INTERRUPTED`. Check the player's inventory first. |
| `/purchase cancel <id>` | op + permission, or console | Close an open row. `COMPLETED` rows can't be cancelled. |
| `/purchase assign <id> <player\|uuid>` | op + permission, or console | Give an `UNRESOLVED` row to a known player |

- Retry, cancel and assign are written to `admin_logs`.
- Every state change is logged in the server console with a `[Purchases]` prefix: the
  delivery id, recipient UUID, reward, quantity, reference and error.
- At startup, JoshyMC logs how many rows are open in each state.
- The raw data is in the `purchase_deliveries` table in `plugins/Joshymc/data.db`.

## Migration

- Existing packages keep working. No existing command changed.
- Move them one at a time to `purchase fulfill`, turning off "require player online" as
  you go.
- The `purchase_deliveries` table is created on first start and is safe to create again.
  No existing data is changed.
- Purchases from before this change are **not** replayed: JoshyMC has no record of them.
