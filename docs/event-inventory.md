# Event world inventory (issue #1550)

Players in the event world use a separate, empty event inventory; their own items come back when
they leave. This is shared by Survival and Lifesteal (one `EventManager` per server). Before
issue #1550 the original items were kept in `event_inventories.yml`, which could lose them
(INV-01), bring back a stale copy (INV-02), fail silently (INV-03) and drop cursor/crafting-grid
items (INV-04).

Code: `communityevent/inventory/` — `EventInventoryRules` (Bukkit-free decisions),
`EventInventoryStore` (Bukkit-free SQLite), `EventInventoryKeeper` (Paper side),
`EventInventoryCommands` (`/event inventory`). Checks:
`./gradlew -I scripts/event-inventory-check.init.gradle.kts eventInventoryCheck`.

## Persistence strategy

- **SQLite** (`data.db`, the existing `DatabaseManager`): `event_inventory_snapshots`, one row per
  entry, never deleted; the stored items, slot count, held slot, gamemode and owner are immutable
  (trigger). Status changes are conditional UPDATEs and a trigger allows only
  `EventInventoryRules.TRANSITIONS`. A partial UNIQUE index allows one unsettled row per player.
  Every change writes `event_inventory_audit` in the same transaction. Every write throws on
  failure; nothing is swallowed.
- **Marker in the player file.** A database write and the Minecraft player file can't be one atomic
  write, and `player.saveData()` doesn't report failures. So entry stores
  `joshymc:event_inventory_snapshot = <row id>` in the player's persistent data container **in the
  same tick** it empties the inventory, and a restore removes it in the same tick it puts the items
  back. The container is saved in the same player-file write as the inventory, so whichever file the
  server loads, its marker says which inventory that file holds. Join reconciliation decides from
  the marker, never from memory.
- All 41 slots (storage, armor, off-hand) are stored with `serializeAsBytes` (base64), and every
  stack is deserialized and compared (`isSimilar` + amount) before the row is written. Items on the
  cursor and in the crafting grid (2x2, or an open crafting table) are placed into the snapshot with
  the ranked rule `RankedInventorySafety.place`; if they don't fit, if a menu that hands items back
  on close (anvil, smithing, …) holds items, or if any stack doesn't read back exactly, entry is
  refused and nothing changes.

## Statuses

| Status | Meaning |
|---|---|
| ACTIVE | Snapshot stored; the event inventory is live (or about to be). |
| RESTORING | A restore claimed the row (before the player was touched); a copy of what it replaced is kept on the row. |
| RESTORED | Final: items back and saved. |
| STALE | Final: the snapshot never replaced the player's items in their file. Kept, never applied. |
| LEGACY | Imported from `event_inventories.yml` (no marker existed); settled on the player's next join. |
| REVIEW | Ambiguous; waits for staff. Never applied automatically; the player can't use the event world meanwhile. |
| RESOLVED | Final: staff decided nothing should be applied. |

## Protocol

Entry: snapshot checked → row ACTIVE → (one tick) cursor/grid/inventory emptied, marker set,
Adventure mode → `saveData()`. A refused or failed snapshot moves the player out of the event world
next tick with their items untouched.

Exit (leaving the world, quitting inside it, server shutdown): ACTIVE → RESTORING (with the replaced
inventory) → (one tick) items, held slot and gamemode back, marker removed → `saveData()` →
RESTORED. Quit and shutdown restore while the player is still inside, so their offline file always
holds their own items; they get a fresh snapshot when they log back into the event world (same as
before).

Join (`PlayerJoinEvent` LOWEST, synchronous, so nothing given on join is overwritten):
`EventInventoryRules.reconcile` from the open row and the marker. A database error leaves
everything as it is; the player is moved out of the event world if they're inside and the
reconcile is retried every 30 s and on their next world change.

## Crash recovery

| Crash point | Player file at next join | Recovery | Automatic? |
|---|---|---|---|
| A. before the snapshot row is written | own items, no marker, no row | nothing to do | yes |
| B. row written, inventory not cleared | own items, no marker, row ACTIVE | row → STALE (never applied) | yes |
| C. cleared, player file not saved yet | the previous file: own items, no marker | row → STALE | yes |
| D. inside the event world | event inventory + marker (in the event world) | resume the ACTIVE snapshot; restored when they leave | yes |
| E. restore applied, player file not saved | event inventory + marker, row RESTORING | applied again (the marker proves the file never got it) | yes |
| F. player file saved, row not confirmed | own items, no marker, row RESTORING | row → RESTORED, not applied again | yes |
| G. during a database write | SQLite rolls the transaction back: the row is in its previous state | one of the cases above | yes |
| H. during join reconciliation | each step is a conditional update decided from the marker; the next join redoes only what's left | yes |
| `saveData()` silently failed after a restore was confirmed, then crash | event inventory + marker of a RESTORED row | **REVIEW** (snapshot kept, nothing applied, player moved out of the event world) | staff |
| marker whose row doesn't exist (DB restored from backup, …) | marker only | **REVIEW** (orphan row, no items) | staff |
| old YAML entry, player outside the event world | own items (the old code restored in the same tick it left) | LEGACY → STALE | yes |
| old YAML entry, in the event world holding only event items | event inventory | LEGACY → ACTIVE (adopted) | yes |
| old YAML entry, in the event world with other items | ambiguous | **REVIEW**, moved out with items untouched | staff |
| old YAML entry with an unreadable item | — | imported as **REVIEW** | staff |

The crash-injection model in the check crashes after every entry and restore step, with and
without failed saves, replays the join, and asserts the player's own items exist exactly once.

## Staff recovery

`joshymc.event.inventory.recover` (op, child of `joshymc.event`; console always). Never given to hosts.

- `/event inventory list` — counts per status and every unsettled row (REVIEW first) with UUID,
  status, last update and reason.
- `/event inventory info <player|uuid>` — the player's rows (status, created, restore started,
  whether a replaced copy is kept, reason, resolver), their live marker if online, recent audit rows.
- `/event inventory apply <id> confirm` — REVIEW rows with items only; the player must be online and
  outside the event world. The row is claimed REVIEW → RESTORING first (so it can never be applied
  twice) and what the player held is kept on the row. It **replaces** the current inventory: compare
  with `info` first.
- `/event inventory resolve <id> confirm [note]` — REVIEW → RESOLVED without giving anything; clears
  the player's marker (on their next join if offline).

Both actions check staff protection (`protectionProblem`) and write `admin_logs`
(`EVENT_INVENTORY_APPLY` / `_RESOLVE`, and `_REFUSED`). There is no command that copies a snapshot
onto a player outside these rules.

## Migration

On start the old `event_inventories.yml` is imported in one transaction as LEGACY rows (REVIEW
when an item can't be read), keyed by a content hash (`legacy_key` UNIQUE, so a re-run imports
nothing twice), then renamed to `event_inventories.yml.migrated-<time>`; it is never deleted. A
file that can't be parsed, or has non-player entries, is left untouched and nothing is imported.
The new code never writes the YAML.

## Unchanged

Event kits, gear tagging, rewards, scheduling, access rules, `/spawn` lock, the 2x2 grid of Survival
players outside the event world, crate/purchase holds while in the event world (`isInEventWorld`).

## Live Paper checklist (test server only)

1. Enter the event world with a full inventory, armor, off-hand, an item on the cursor (open
   inventory, pick up a stack, teleport via console) and items in the 2x2 grid: all come back on
   `/spawn`, nothing dropped. With a full inventory plus a cursor item: entry is refused, nothing changes.
2. Enter with an anvil holding items open: refused.
3. Quit inside, rejoin: event inventory again, own items back on leaving. `/event inventory info` shows RESTORED rows.
4. `kill -9` the server while a player is inside; restart, rejoin: resumes; leave: items back once.
5. `kill -9` right after `/spawn` (before autosave): rejoin: items once (RESTORING applied again, or confirmed).
6. Stop the server cleanly with players inside: items restored before the save; rejoin inside gives a fresh snapshot.
7. Make `data.db` read-only (or lock it) and enter: moved out with items untouched, severe log.
8. Put an old `event_inventories.yml` in place: imported and renamed; join outside → STALE; join inside with only event gear → adopted.
9. Hosted events (Spleef/Sumo/Sharks/CPvP/Mobs) on Survival and Lifesteal: kits given and stripped as before.

## Limitations

- `saveData()` failures are invisible to plugins; the marker makes them safe but a silently failed
  save after a confirmed restore ends in REVIEW rather than automatic recovery.
- Rolling back a player's `.dat` from a backup can resurrect an old marker; that goes to REVIEW.
- Staff in Mod Mode inside the event world: Mod Mode's own backup (a later LOWEST join handler) is unchanged.
- XP and potion effects are not part of the event inventory (they weren't before either).
