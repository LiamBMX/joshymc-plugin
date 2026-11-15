package com.liam.joshymc.communityevent.inventory

/**
 * Issue #1550 (INV-01..04): the Bukkit-free rules that keep the event world's separate inventory
 * crash safe. Players get an empty event inventory in the event world and their own items back when
 * they leave; the original items live in one `event_inventory_snapshots` row (data.db) meanwhile.
 *
 * **Why a marker.** A SQLite write and the Minecraft player file can never be one atomic write, so
 * after a crash the snapshot row alone can't tell whether the player's saved inventory is the event
 * inventory (the snapshot must be applied) or their original one (applying it would duplicate every
 * item). The player's own persistent data container is saved in the *same* player-file write as their
 * inventory, so entry sets `joshymc:event_inventory_snapshot = <row id>` in the same tick as it empties
 * the inventory, and a restore removes it in the same tick as it puts the items back. Whatever file the
 * server loads, the marker in it says exactly which inventory that file holds:
 *
 * - marker = the open row: the file holds that row's event inventory, its original items are only in
 *   the row, so applying the row is the one correct thing (never a duplicate);
 * - no marker: the file holds the player's own items, so an ACTIVE row never reached the file (stale,
 *   kept but never applied) and a RESTORING row already did (restored).
 *
 * Only cases the marker can't explain (a marker whose row is already settled or missing, snapshots
 * imported from the old `event_inventories.yml`, unreadable snapshots) go to REVIEW for staff. A REVIEW
 * row is never applied automatically and its player can't use the event world until it is settled.
 */
object EventInventoryRules {

    // ── Statuses ────────────────────────────────────────────

    /** Snapshot stored; the event inventory is (or is about to be) live. */
    const val ACTIVE = "ACTIVE"
    /** A restore was started (written before the player is touched); not yet confirmed. */
    const val RESTORING = "RESTORING"
    /** Final: the original items were put back and saved. */
    const val RESTORED = "RESTORED"
    /** Final: the snapshot never replaced the player's items in their saved file; kept for reference, never applied. */
    const val STALE = "STALE"
    /** Ambiguous: waits for staff (`/event inventory`). Never applied automatically. */
    const val REVIEW = "REVIEW"
    /** Imported from the old `event_inventories.yml`, which had no marker; settled on the player's next join. */
    const val LEGACY = "LEGACY"
    /** Final: staff decided nothing should be applied. */
    const val RESOLVED = "RESOLVED"

    val STATUSES = listOf(ACTIVE, RESTORING, RESTORED, STALE, REVIEW, LEGACY, RESOLVED)

    /** At most one of these per player (partial UNIQUE index). */
    val UNSETTLED = setOf(ACTIVE, RESTORING, REVIEW, LEGACY)

    /** Sources of a row. */
    const val SOURCE_ENTRY = "ENTRY"
    const val SOURCE_LEGACY = "LEGACY_YAML"
    /** A review row for a marker whose snapshot row doesn't exist (no items in it). */
    const val SOURCE_ORPHAN = "ORPHAN_MARKER"

    /**
     * The only status changes a row may make (a trigger enforces the same table). RESTORING may be
     * re-taken (a restore interrupted before the player file was saved is applied again, which the
     * marker proves is safe). RESTORED and STALE only move to REVIEW, when a player file still carries
     * their marker. RESOLVED is final.
     */
    val TRANSITIONS: Map<String, Set<String>> = mapOf(
        ACTIVE to setOf(RESTORING, STALE, REVIEW),
        RESTORING to setOf(RESTORING, RESTORED, REVIEW),
        LEGACY to setOf(ACTIVE, STALE, REVIEW),
        REVIEW to setOf(RESTORING, RESOLVED),
        RESTORED to setOf(REVIEW),
        STALE to setOf(REVIEW),
        RESOLVED to emptySet(),
    )

    fun canMove(from: String, to: String): Boolean = to in (TRANSITIONS[from] ?: emptySet())

    /** Statuses a restore may start from automatically (REVIEW only by staff). */
    val AUTO_RESTORE_FROM = setOf(ACTIVE, RESTORING)

    // ── Reasons (stored in `detail`, shown to staff) ────────

    const val NEVER_SAVED = "the player's saved file never had this snapshot's event inventory (no marker); their own items were kept, the snapshot was not applied"
    const val RESTORE_ON_DISK = "the player's saved file already had the restored inventory (marker gone); confirmed"
    const val LEGACY_OUTSIDE = "old event_inventories.yml entry, but the player's saved file is outside the event world, so it already holds their own items; not applied"
    const val LEGACY_ADOPTED = "old event_inventories.yml entry; the player was in the event world holding only event items, so it is their active snapshot"
    const val LEGACY_AMBIGUOUS = "old event_inventories.yml entry, but the player is in the event world holding non-event items; staff must compare before anything is applied"
    const val LEGACY_WITH_MARKER = "old event_inventories.yml entry while the player's file carries a newer event marker"
    const val MARKER_SETTLED = "the player's saved file still carries this snapshot's marker (its event inventory) although the snapshot was already settled; possibly a failed player-data save or a rolled-back player file"
    const val MARKER_NO_ROW = "the player's saved file carries an event inventory marker whose snapshot row doesn't exist"
    const val UNREADABLE = "the snapshot couldn't be read; nothing was changed"

    // ── Join / entry / exit reconciliation ─────────────────

    data class Row(val id: Long, val status: String, val hasItems: Boolean = true)

    /**
     * What the server knows about one online player.
     *
     * @param open their unsettled row, if any (at most one).
     * @param marker the snapshot id in their persistent data (= in the player file they were loaded from).
     * @param markerRow the row [marker] points at when it isn't [open] (null = no such row for this player).
     * @param inEventWorld they're standing in the event world now.
     * @param liveEventOnly their live inventory and cursor hold nothing but event gear (or nothing).
     */
    data class State(
        val open: Row?,
        val marker: Long?,
        val markerRow: Row?,
        val inEventWorld: Boolean,
        val liveEventOnly: Boolean,
    )

    sealed interface Step {
        val id: Long?
        data class MarkStale(override val id: Long, val from: String, val reason: String) : Step
        data class MarkRestored(override val id: Long, val reason: String) : Step
        data class ToReview(override val id: Long, val from: String, val reason: String) : Step
        /** A REVIEW row recording a marker that has no row. */
        data class OrphanReview(val marker: Long, val reason: String) : Step { override val id: Long? get() = null }
        /** Apply the row: the file holds its event inventory (marker = row). */
        data class Restore(override val id: Long, val from: String) : Step
        /** LEGACY → ACTIVE: the live inventory is event-only, so the old snapshot becomes the active one. */
        data class AdoptLegacy(override val id: Long) : Step
        /** Staff resolved the row the marker points at: remove the marker, change nothing else. */
        data class ClearMarker(val marker: Long) : Step { override val id: Long? get() = null }
    }

    enum class Then {
        /** Nothing more. */
        NONE,
        /** In the event world with nothing open: store a new snapshot and switch to the event inventory. */
        ENTER,
        /** In the event world with their ACTIVE snapshot: keep using it (no new snapshot, nothing cleared). */
        RESUME,
        /** In the event world but a review blocks it: move them out with their current items untouched. */
        EJECT,
    }

    data class Plan(val steps: List<Step>, val then: Then)

    private fun hold(s: State) = if (s.inEventWorld) Then.EJECT else Then.NONE

    /**
     * Settles [s]. Steps run in order; if one fails (database error) the rest is skipped and the whole
     * plan is tried again later, which is safe because every step is a conditional update decided
     * from the marker, never from in-memory state.
     */
    fun reconcile(s: State): Plan {
        val steps = mutableListOf<Step>()
        var markerExplained = s.marker == null
        val open = s.open
        if (open != null) {
            if (s.marker == open.id) {
                markerExplained = true
                when (open.status) {
                    ACTIVE -> {
                        if (s.inEventWorld) return Plan(steps, Then.RESUME)
                        steps += Step.Restore(open.id, ACTIVE)
                    }
                    RESTORING -> steps += Step.Restore(open.id, RESTORING)
                    // Only an interrupted adoption sets a LEGACY row's own id as marker (the player was in
                    // the event world with event-only items): finish it there, never guess elsewhere.
                    LEGACY -> {
                        if (s.inEventWorld) return Plan(steps + Step.AdoptLegacy(open.id), Then.RESUME)
                        steps += Step.ToReview(open.id, LEGACY, LEGACY_WITH_MARKER)
                        return Plan(steps, hold(s))
                    }
                    else -> return Plan(steps, hold(s))
                }
            } else {
                when (open.status) {
                    ACTIVE -> steps += Step.MarkStale(open.id, ACTIVE, NEVER_SAVED)
                    RESTORING -> steps += Step.MarkRestored(open.id, RESTORE_ON_DISK)
                    LEGACY -> when {
                        s.marker != null -> {
                            steps += Step.ToReview(open.id, LEGACY, LEGACY_WITH_MARKER)
                            return Plan(steps, hold(s))
                        }
                        !s.inEventWorld -> steps += Step.MarkStale(open.id, LEGACY, LEGACY_OUTSIDE)
                        s.liveEventOnly -> return Plan(steps + Step.AdoptLegacy(open.id), Then.RESUME)
                        else -> {
                            steps += Step.ToReview(open.id, LEGACY, LEGACY_AMBIGUOUS)
                            return Plan(steps, Then.EJECT)
                        }
                    }
                    else -> return Plan(steps, hold(s)) // REVIEW: waits for staff
                }
            }
        }
        if (!markerExplained) {
            val marker = s.marker!!
            val row = s.markerRow
            when {
                row == null -> steps += Step.OrphanReview(marker, MARKER_NO_ROW)
                row.status == RESOLVED -> {
                    steps += Step.ClearMarker(marker)
                    return Plan(steps, if (s.inEventWorld) Then.ENTER else Then.NONE)
                }
                row.status == RESTORED || row.status == STALE -> steps += Step.ToReview(row.id, row.status, MARKER_SETTLED)
                // An unsettled row that isn't [open] can't exist (one per player); never guess.
                else -> steps += Step.OrphanReview(marker, MARKER_SETTLED)
            }
            return Plan(steps, hold(s))
        }
        return Plan(steps, if (s.inEventWorld) Then.ENTER else Then.NONE)
    }

    // ── Entry checks ────────────────────────────────────────

    fun entryRefusal(openMenuProblem: String?, leftoverStacks: Int, serializationMismatches: Int): String? = when {
        openMenuProblem != null -> openMenuProblem
        leftoverStacks > 0 -> "the items on your cursor or crafting grid don't fit in your inventory (make room, then come back)"
        serializationMismatches > 0 -> "$serializationMismatches of your items couldn't be saved exactly"
        else -> null
    }

    // ── Legacy YAML (event_inventories.yml) ────────────────

    /** Bukkit `PlayerInventory.contents` layout: 0..35 storage, 36 boots, 37 leggings, 38 chestplate, 39 helmet, 40 off-hand. */
    const val SLOT_COUNT = 41
    const val BOOTS = 36
    const val LEGGINGS = 37
    const val CHESTPLATE = 38
    const val HELMET = 39
    const val OFFHAND = 40

    /**
     * The 41 slots of an old YAML entry: `inv.<n>` (the old code saved every contents index, armor
     * included) then the explicit `armor.*` keys, which the old restore applied last and so win.
     */
    fun <T : Any> legacySlots(inv: Map<Int, T>, helmet: T?, chestplate: T?, leggings: T?, boots: T?, offhand: T?): List<T?> {
        val out = MutableList<T?>(SLOT_COUNT) { null }
        for ((slot, item) in inv) if (slot in 0 until SLOT_COUNT) out[slot] = item
        out[HELMET] = helmet
        out[CHESTPLATE] = chestplate
        out[LEGGINGS] = leggings
        out[BOOTS] = boots
        if (offhand != null) out[OFFHAND] = offhand
        return out
    }

    // ── Encoding ────────────────────────────────────────────

    /** Opaque serialized stacks (base64, no commas), null = empty slot. */
    fun encodeSlots(slots: List<String?>): String {
        require(slots.none { it != null && (it.isEmpty() || ',' in it) }) { "a serialized slot is empty or contains a comma" }
        return slots.joinToString(",") { it ?: "" }
    }

    fun decodeSlots(raw: String?, count: Int): List<String?> {
        val parts = if (raw.isNullOrEmpty()) List(count) { "" } else raw.split(',')
        require(parts.size == count) { "snapshot has ${parts.size} slots, expected $count" }
        return parts.map { it.ifEmpty { null } }
    }
}
