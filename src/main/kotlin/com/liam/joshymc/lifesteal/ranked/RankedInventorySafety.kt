package com.liam.joshymc.lifesteal.ranked

/**
 * Issue #1506 (B-2, B-3/E-2): the Bukkit-free rules that keep a ranked snapshot complete and
 * applied at most once automatically.
 *
 * **Loose items (B-2).** Items on the cursor and in the player's crafting grid (the 2x2 grid, or a
 * crafting table's 3x3 grid they have open) belong to the player but aren't inventory slots. The
 * server used to put them back on close, dropping whatever didn't fit at the player's feet, outside
 * the snapshot. Now they are placed into the snapshot's storage slots (0..35) the way vanilla would
 * (similar partial stacks first, then empty slots) *before* the snapshot is written, and the same
 * placement is applied to the live inventory only after the snapshot was stored. Anything that
 * doesn't fit refuses the match; nothing is dropped, deleted or duplicated.
 *
 * **Restoration (B-3/E-2).** A Bukkit inventory change can't be part of a SQLite transaction, so a
 * restore is a durable three-step protocol on the snapshot row:
 *
 * 1. `PENDING → RESTORING` (conditional UPDATE, with this boot's id) *before* the player is touched.
 *    If that write fails nothing changes and the snapshot stays PENDING; if it changes no row the
 *    snapshot was already taken by another restore and is never applied again.
 * 2. The inventory, XP and effects are set and the player is saved.
 * 3. `RESTORING → RESTORED`. If that write fails the row stays RESTORING: it is no longer PENDING,
 *    so no join, respawn or startup applies it again; this boot retries the mark.
 *
 * A RESTORING row left by an earlier boot (crash or kill between steps 1 and 3) is ambiguous: the
 * player's saved inventory may or may not be the restored one. It becomes `REVIEW` and is never
 * applied automatically; staff inspect the player and either apply it (`REVIEW → RESTORING →
 * RESTORED`) or resolve it as already restored (`REVIEW → RESTORED`). The snapshot data is never
 * deleted, and a player with a RESTORING/REVIEW snapshot can't enter a new match (a new snapshot
 * would otherwise be overwritten by the old one later).
 */
object RankedInventorySafety {

    // ── Snapshot statuses ───────────────────────────────────

    const val PENDING = "PENDING"
    const val RESTORING = "RESTORING"
    const val RESTORED = "RESTORED"
    const val REVIEW = "REVIEW"

    val STATUSES = setOf(PENDING, RESTORING, RESTORED, REVIEW)

    /** Statuses that still need something done: the player can't start another match meanwhile. */
    val UNSETTLED = setOf(PENDING, RESTORING, REVIEW)

    /** The only status changes a snapshot row may make (enforced by a trigger too). RESTORED is final. */
    fun canMove(from: String, to: String): Boolean = when (from) {
        PENDING -> to == RESTORING || to == RESTORED
        RESTORING -> to == RESTORED || to == REVIEW
        REVIEW -> to == RESTORING || to == RESTORED
        else -> false
    }

    /** Only PENDING is applied without a staff decision. */
    fun autoApplies(status: String): Boolean = status == PENDING

    /**
     * What startup does with a RESTORING row: one written by this very boot is still being
     * finished here (null = leave it), one from an earlier boot is ambiguous and goes to REVIEW.
     */
    fun interruptedRestoreTarget(rowBoot: String?, currentBoot: String): String? = if (rowBoot == currentBoot) null else REVIEW

    const val INTERRUPTED_REASON = "restore was started but not confirmed before the server stopped; the saved inventory may already be the restored one"

    // ── Loose items (cursor + crafting grid) ────────────────

    /** Main inventory and hotbar: where vanilla puts returned items (never armor or off-hand). */
    const val STORAGE_SLOTS = 36

    /** How the planner reads item stacks; the manager wraps Paper's ItemStack, the checks a fake. */
    interface Stacks<T> {
        /** Same item and metadata (can stack together). */
        fun similar(a: T, b: T): Boolean
        fun amount(stack: T): Int
        fun maxStack(stack: T): Int
        /** A copy of [stack] with [amount] items (never mutates [stack]). */
        fun withAmount(stack: T, amount: Int): T
    }

    data class Placement<T>(
        /** The inventory after the loose items were placed (same size as the input). */
        val slots: List<T?>,
        /** Slot indexes whose stack changed. */
        val changed: Set<Int>,
        /** What didn't fit (empty = everything fits). */
        val leftover: List<T>,
    ) {
        val fits: Boolean get() = leftover.isEmpty()
    }

    /**
     * Places [loose] into [slots] like vanilla returns items on close: into similar partial
     * stacks of the storage slots first (in slot order), then into empty storage slots. Amounts
     * are conserved exactly: placed + leftover = the loose items.
     */
    fun <T : Any> place(slots: List<T?>, loose: List<T>, stacks: Stacks<T>, storage: Int = STORAGE_SLOTS): Placement<T> {
        val out = slots.toMutableList()
        val changed = sortedSetOf<Int>()
        val leftover = mutableListOf<T>()
        val limit = minOf(storage, out.size)
        for (item in loose) {
            var remaining = stacks.amount(item)
            if (remaining <= 0) continue
            for (i in 0 until limit) {
                if (remaining == 0) break
                val current = out[i] ?: continue
                if (!stacks.similar(current, item)) continue
                val room = maxOf(1, stacks.maxStack(current)) - stacks.amount(current)
                if (room <= 0) continue
                val add = minOf(room, remaining)
                out[i] = stacks.withAmount(current, stacks.amount(current) + add)
                remaining -= add
                changed += i
            }
            val max = maxOf(1, stacks.maxStack(item))
            for (i in 0 until limit) {
                if (remaining == 0) break
                if (out[i] != null) continue
                val add = minOf(max, remaining)
                out[i] = stacks.withAmount(item, add)
                remaining -= add
                changed += i
            }
            if (remaining > 0) leftover += stacks.withAmount(item, remaining)
        }
        return Placement(out, changed, leftover)
    }

    /**
     * Open menus whose input slots the server hands back to the player on close (Bukkit
     * `InventoryType` names). Their items aren't in the snapshot, so a match refuses to start
     * while one holds anything; crafting grids are placed instead (see [place]).
     */
    val RETURNED_ON_CLOSE = setOf("ANVIL", "GRINDSTONE", "ENCHANTING", "SMITHING", "STONECUTTER", "LOOM", "CARTOGRAPHY", "MERCHANT", "BEACON")

    /** Crafting menus: the player's own 2x2 grid and the crafting table's 3x3 grid. */
    val CRAFTING_GRIDS = setOf("CRAFTING", "WORKBENCH")

    fun openMenuProblem(name: String, menuType: String, menuHasItems: Boolean): String? =
        if (menuType in RETURNED_ON_CLOSE && menuHasItems) "$name has items in an open ${menuType.lowercase()} menu (close it first)" else null

    fun looseItemsProblem(name: String, leftoverStacks: Int): String? =
        if (leftoverStacks > 0) "$name is holding items on their cursor or crafting grid that don't fit in their inventory (make room first)" else null
}
