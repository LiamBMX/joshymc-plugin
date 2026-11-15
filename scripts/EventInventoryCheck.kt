// Checks the event-world inventory crash safety of issue #1550 (INV-01..04): the Bukkit-free
// reconciliation rules (EventInventoryRules), the SQLite store (EventInventoryStore) against a real
// database in fresh temp directories (closed to simulate failed writes), and a crash-injection model
// of the entry/exit protocol that "crashes" after every step and replays the join reconciliation,
// asserting the player's own items are never lost or duplicated. Run from the repo root:
//
//   ./gradlew -I scripts/event-inventory-check.init.gradle.kts eventInventoryCheck
//
// Exits non-zero on the first failed check. Real ItemStacks, player files and teleports need a
// running server; see docs/event-inventory.md for the live-server checklist.

import com.liam.joshymc.communityevent.inventory.EventInventoryRules
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.ACTIVE
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.LEGACY
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Plan
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.RESOLVED
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.RESTORED
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.RESTORING
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.REVIEW
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Row
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.STALE
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.State
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Step
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.Then
import com.liam.joshymc.communityevent.inventory.EventInventoryStore
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private inline fun throws(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

fun main() {
    rules()
    looseItems()
    legacy()
    store()
    crashes()
    wiring()
    println("Event inventory checks passed ($checks checks).")
}

// ── Rules ────────────────────────────────────────────────────

private fun plan(open: Row?, marker: Long?, markerRow: Row? = null, inEvent: Boolean, eventOnly: Boolean = false): Plan =
    EventInventoryRules.reconcile(State(open, marker, markerRow, inEvent, eventOnly))

private fun rules() {
    // Transitions
    check("RESTORED is only reopened for review", EventInventoryRules.TRANSITIONS[RESTORED] == setOf(REVIEW))
    check("RESOLVED is final", EventInventoryRules.TRANSITIONS[RESOLVED]!!.isEmpty())
    check("REVIEW is never moved to RESTORED without a restore", !EventInventoryRules.canMove(REVIEW, RESTORED))
    check("ACTIVE can't skip straight to RESTORED", !EventInventoryRules.canMove(ACTIVE, RESTORED))
    check("LEGACY can't be restored automatically", !EventInventoryRules.canMove(LEGACY, RESTORING))
    check("an interrupted restore may be re-taken", EventInventoryRules.canMove(RESTORING, RESTORING))
    check("every status has a transition entry", EventInventoryRules.STATUSES.all { it in EventInventoryRules.TRANSITIONS })

    // 1. Normal entry: nothing open, in the event world.
    check("1. entry with nothing open enters", plan(null, null, inEvent = true) == Plan(emptyList(), Then.ENTER))
    check("nothing open outside does nothing", plan(null, null, inEvent = false) == Plan(emptyList(), Then.NONE))
    // 2. Normal exit: ACTIVE + marker outside → restore.
    check("2. exit restores the active snapshot", plan(Row(5, ACTIVE), 5, inEvent = false) == Plan(listOf(Step.Restore(5, ACTIVE)), Then.NONE))
    // 7 / 10. Reconnect in the event world / duplicate callback with the marker: resume, no new snapshot.
    check("7/10. ACTIVE + marker in the event world resumes", plan(Row(5, ACTIVE), 5, inEvent = true) == Plan(emptyList(), Then.RESUME))
    // B/C. Snapshot stored but the player file never got the event inventory.
    check("B/C. ACTIVE without marker is stale (own items kept), then normal entry in the event world",
        plan(Row(5, ACTIVE), null, inEvent = true) == Plan(listOf(Step.MarkStale(5, ACTIVE, EventInventoryRules.NEVER_SAVED)), Then.ENTER))
    check("8/15. stale ACTIVE outside is never applied",
        plan(Row(5, ACTIVE), null, inEvent = false).steps.none { it is Step.Restore })
    // E. Restore started, player file still has the event inventory (marker) → apply again.
    check("E. RESTORING + marker is applied again", plan(Row(5, RESTORING), 5, inEvent = false).steps == listOf(Step.Restore(5, RESTORING)))
    check("E. RESTORING + marker in the event world restores, then a fresh entry",
        plan(Row(5, RESTORING), 5, inEvent = true) == Plan(listOf(Step.Restore(5, RESTORING)), Then.ENTER))
    // F. Restore saved, confirmation missing → confirmed, never applied twice.
    check("F. RESTORING without marker is confirmed, not applied",
        plan(Row(5, RESTORING), null, inEvent = false) == Plan(listOf(Step.MarkRestored(5, EventInventoryRules.RESTORE_ON_DISK)), Then.NONE))
    // 12. Player-data save failed after the row was settled: marker of a settled row → review.
    check("12. a marker whose snapshot is already RESTORED goes to review, nothing applied",
        plan(null, 5, Row(5, RESTORED), inEvent = false) == Plan(listOf(Step.ToReview(5, RESTORED, EventInventoryRules.MARKER_SETTLED)), Then.NONE))
    check("a marker of a STALE row goes to review and ejects from the event world",
        plan(null, 5, Row(5, STALE), inEvent = true) == Plan(listOf(Step.ToReview(5, STALE, EventInventoryRules.MARKER_SETTLED)), Then.EJECT))
    check("a marker without a row becomes an orphan review", plan(null, 9, null, inEvent = false).steps == listOf(Step.OrphanReview(9, EventInventoryRules.MARKER_NO_ROW)))
    check("a resolved marker row only clears the marker", plan(null, 9, Row(9, RESOLVED), inEvent = false) == Plan(listOf(Step.ClearMarker(9)), Then.NONE))
    check("a resolved marker row in the event world clears it and enters", plan(null, 9, Row(9, RESOLVED), inEvent = true).then == Then.ENTER)
    // REVIEW holds.
    check("REVIEW outside changes nothing", plan(Row(5, REVIEW), null, inEvent = false) == Plan(emptyList(), Then.NONE))
    check("REVIEW in the event world ejects without touching items", plan(Row(5, REVIEW), 5, inEvent = true) == Plan(emptyList(), Then.EJECT))
    // Two snapshots: stale open row and a settled marker row.
    val two = plan(Row(7, ACTIVE), 5, Row(5, RESTORED), inEvent = false)
    check("an older marker and a stale newer row: newer marked stale, older reviewed",
        two.steps == listOf(Step.MarkStale(7, ACTIVE, EventInventoryRules.NEVER_SAVED), Step.ToReview(5, RESTORED, EventInventoryRules.MARKER_SETTLED)))
    // Legacy rows (no marker existed).
    check("legacy outside the event world is stale (the file was saved outside)", plan(Row(3, LEGACY), null, inEvent = false).steps == listOf(Step.MarkStale(3, LEGACY, EventInventoryRules.LEGACY_OUTSIDE)))
    check("legacy in the event world with only event items is adopted", plan(Row(3, LEGACY), null, inEvent = true, eventOnly = true) == Plan(listOf(Step.AdoptLegacy(3)), Then.RESUME))
    check("legacy in the event world with other items goes to review + eject",
        plan(Row(3, LEGACY), null, inEvent = true, eventOnly = false) == Plan(listOf(Step.ToReview(3, LEGACY, EventInventoryRules.LEGACY_AMBIGUOUS)), Then.EJECT))
    check("an interrupted legacy adoption finishes in the event world", plan(Row(3, LEGACY), 3, inEvent = true).steps == listOf(Step.AdoptLegacy(3)))
    check("legacy with its own marker outside goes to review", plan(Row(3, LEGACY), 3, inEvent = false).steps.single() is Step.ToReview)
    check("legacy with a different marker goes to review", plan(Row(3, LEGACY), 8, inEvent = false).steps.single() is Step.ToReview)

    // Every plan with a Restore restores only a row the marker points at (never a stale guess).
    for (status in EventInventoryRules.STATUSES) for (marker in listOf(null, 5L, 6L)) for (inEvent in listOf(true, false)) for (only in listOf(true, false)) {
        val p = plan(Row(5, status).takeIf { status in EventInventoryRules.UNSETTLED }, marker, Row(6, RESTORED).takeIf { marker == 6L }, inEvent, only)
        val restores = p.steps.filterIsInstance<Step.Restore>()
        check("restore only with marker ($status, $marker, $inEvent, $only)", restores.all { it.id == marker })
        check("at most one restore per plan ($status, $marker)", restores.size <= 1)
        check("ENTER never while a review is open ($status, $marker)", !(p.then == Then.ENTER && p.steps.any { it is Step.ToReview || it is Step.OrphanReview }))
    }

    // Entry refusal
    check("11. refusal: open menu", EventInventoryRules.entryRefusal("menu", 0, 0) == "menu")
    check("5. refusal: loose items that don't fit", EventInventoryRules.entryRefusal(null, 1, 0) != null)
    check("refusal: a stack that doesn't serialize back exactly", EventInventoryRules.entryRefusal(null, 0, 2)!!.contains("2"))
    check("no refusal when everything fits", EventInventoryRules.entryRefusal(null, 0, 0) == null)
}

// ── Cursor / crafting grid (INV-04) ──────────────────────────

private data class Fake(val id: String, val amount: Int = 1, val max: Int = 64)

private object FakeStacks : RankedInventorySafety.Stacks<Fake> {
    override fun similar(a: Fake, b: Fake) = a.id == b.id
    override fun amount(stack: Fake) = stack.amount
    override fun maxStack(stack: Fake) = stack.max
    override fun withAmount(stack: Fake, amount: Int) = stack.copy(amount = amount)
}

private fun looseItems() {
    val inv = MutableList<Fake?>(41) { null }
    inv[0] = Fake("dirt", 60)
    inv[36] = Fake("boots", 1, 1)
    inv[39] = Fake("helmet", 1, 1)
    inv[40] = Fake("shield", 1, 1)
    val placed = RankedInventorySafety.place(inv, listOf(Fake("dirt", 10), Fake("diamond", 3)), FakeStacks)
    check("5. cursor/grid items fit into the snapshot", placed.fits)
    check("5. amounts conserved (dirt 70)", placed.slots.filterNotNull().filter { it.id == "dirt" }.sumOf { it.amount } == 70)
    check("4. armor and off-hand untouched by placement", placed.slots[36] == inv[36] && placed.slots[39] == inv[39] && placed.slots[40] == inv[40])
    val full = MutableList<Fake?>(41) { Fake("stone$it", 64) }
    val leftover = RankedInventorySafety.place(full, listOf(Fake("diamond", 1)), FakeStacks)
    check("3. full inventory: the loose item is reported, never dropped", !leftover.fits && leftover.leftover.single().amount == 1)
    check("menus that hand items back block entry", RankedInventorySafety.openMenuProblem("You", "ANVIL", true) != null)
}

// ── Legacy YAML layout ───────────────────────────────────────

private fun legacy() {
    val slots = EventInventoryRules.legacySlots(mapOf(0 to "a", 36 to "oldBoots", 40 to "oldShield", 99 to "ignored"), "helm", null, "legs", "boots", null)
    check("legacy: 41 slots", slots.size == 41)
    check("legacy: storage slot kept", slots[0] == "a")
    check("legacy: armor keys win like the old restore", slots[EventInventoryRules.BOOTS] == "boots" && slots[EventInventoryRules.HELMET] == "helm" && slots[EventInventoryRules.LEGGINGS] == "legs")
    check("legacy: missing chestplate = empty (old restore set null)", slots[EventInventoryRules.CHESTPLATE] == null)
    check("legacy: off-hand from inv.40 kept when armor.offhand absent", slots[EventInventoryRules.OFFHAND] == "oldShield")
    check("encode/decode round trip", EventInventoryRules.decodeSlots(EventInventoryRules.encodeSlots(listOf("x", null, "y")), 3) == listOf("x", null, "y"))
    check("decode refuses a wrong slot count", throws { EventInventoryRules.decodeSlots("x,y", 3) })
    check("encode refuses a comma", throws { EventInventoryRules.encodeSlots(listOf("a,b")) })
}

// ── Store (real SQLite) ──────────────────────────────────────

private fun <T> withDb(dir: File, block: (EventInventoryStore, DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        val s = EventInventoryStore(db)
        s.createTables()
        return block(s, db)
    } finally {
        db.shutdown()
    }
}

private val P1 = UUID(70L, 1L)
private val P2 = UUID(70L, 2L)

private fun slots(vararg items: String?): List<String?> = MutableList<String?>(41) { null }.also { for ((i, s) in items.withIndex()) it[i] = s }

private fun store() {
    val dir = Files.createTempDirectory("event-inventory-check").toFile()
    val id = withDb(dir) { s, _ ->
        val id = s.insertActive(P1, "Alice", slots("A", "B"), 2, "SURVIVAL", "boot-1", 1000)
        check("1. entry row is ACTIVE with its items", s.open(P1)?.let { it.status == ACTIVE && it.slots == slots("A", "B") && it.heldSlot == 2 } == true)
        check("9. a second open snapshot for the same player is refused", throws { s.insertActive(P1, "Alice", slots("C"), 0, "SURVIVAL", "boot-1", 1001) })
        check("another player is independent", s.insertActive(P2, "Bob", slots("X"), 0, "SURVIVAL", "boot-1", 1002) > 0)
        // 16 / 20. Two restores race for the same row: exactly one claims it.
        val first = s.move(id, ACTIVE, RESTORING, "r", "system", 1100, bootId = "boot-1", replaced = slots("E"))
        val second = s.move(id, ACTIVE, RESTORING, "r", "system", 1101, bootId = "boot-1")
        check("16/20. one claim wins, the other takes nothing", first && !second)
        check("the replaced inventory is kept on the row", s.replaced(id) == slots("E") && s.byId(id)!!.hasReplaced)
        check("disallowed transition is refused before touching the DB", throws { s.move(id, RESTORING, ACTIVE, "x", "system", 1) })
        check("the claimed restore is confirmed once", s.move(id, RESTORING, RESTORED, "done", "system", 1200) && !s.move(id, RESTORING, RESTORED, "done", "system", 1201))
        id
    }
    // Reopen = restart.
    withDb(dir) { s, db ->
        check("19. restart: the row and its claim survive", s.byId(id)?.status == RESTORED)
        check("RESTORED isn't open; the player can enter again", s.open(P1) == null)
        val id2 = s.insertActive(P1, "Alice", slots("A", "B"), 0, "SURVIVAL", "boot-2", 2000)
        check("9. multiple entries get separate rows", id2 != id)
        check("contents are immutable", throws { db.execute("UPDATE event_inventory_snapshots SET contents = 'x' WHERE id = ?", id2) })
        check("rows are never deleted", throws { db.execute("DELETE FROM event_inventory_snapshots WHERE id = ?", id2) })
        check("trigger: RESTORED can't go back to ACTIVE", throws { db.execute("UPDATE event_inventory_snapshots SET status = 'ACTIVE' WHERE id = ?", id) })
        check("trigger: ACTIVE can't jump to RESTORED", throws { db.execute("UPDATE event_inventory_snapshots SET status = 'RESTORED' WHERE id = ?", id2) })
        check("stale marking", s.move(id2, ACTIVE, STALE, EventInventoryRules.NEVER_SAVED, "system", 2100))
        check("a settled marker row can go to review", s.move(id2, STALE, REVIEW, EventInventoryRules.MARKER_SETTLED, "system", 2200))
        check("forMarker finds the player's own row", s.forMarker(P1, id2)?.id == id2)
        check("forMarker never finds another player's row", s.forMarker(P2, id2) == null)
        check("review → resolved", s.move(id2, REVIEW, RESOLVED, "ok", "staff:Mod", 2300, resolvedBy = "Mod") && s.byId(id2)!!.resolvedBy == "Mod")
        check("resolved is final", throws { db.execute("UPDATE event_inventory_snapshots SET status = 'REVIEW' WHERE id = ?", id2) })
        val orphan = s.insertOrphanReview(P1, "Alice", 999, EventInventoryRules.MARKER_NO_ROW, 2400)
        check("orphan review holds no items", s.byId(orphan)?.let { !it.hasItems && it.status == REVIEW && it.markerRef == 999L } == true)
        check("forMarker finds the orphan row for its marker", s.forMarker(P1, 999)?.id == orphan)
        check("audit rows written with every change", s.audit(P1, 50).size >= 6)
        check("list shows REVIEW first", s.unsettled().first().status == REVIEW)
        // Legacy import: all-or-nothing, idempotent, problems → REVIEW.
        val entries = listOf(
            EventInventoryStore.LegacyEntry(UUID(80L, 1L), "yaml:a", slots("L1"), "SURVIVAL", emptyList()),
            EventInventoryStore.LegacyEntry(UUID(80L, 2L), "yaml:b", slots("L2"), "SURVIVAL", listOf("inv.3 unreadable")),
        )
        check("legacy import adds both", s.importLegacy(entries, 3000) == 2)
        check("legacy without problems is LEGACY", s.open(UUID(80L, 1L))?.status == LEGACY)
        check("legacy with an unreadable item is REVIEW", s.open(UUID(80L, 2L))?.status == REVIEW)
        check("legacy re-import is a no-op (rename failed / crash after commit)", s.importLegacy(entries, 3001) == 0)
        val skipped = mutableListOf<UUID>()
        s.importLegacy(listOf(EventInventoryStore.LegacyEntry(P1, "yaml:c", slots("Z"), "SURVIVAL", emptyList())), 3002, skipped)
        check("legacy entry for a player with an open row is skipped and reported", skipped == listOf(P1))
        // 11. Failed snapshot save: closed DB throws, nothing pretends to succeed.
        db.shutdown()
        check("11. a snapshot write on a closed database throws (entry is refused)", throws { s.insertActive(P2, "Bob", slots("Q"), 0, "SURVIVAL", "b", 1) })
        check("a status move on a closed database throws", throws { s.move(orphan, REVIEW, RESOLVED, "x", "s", 1) })
        check("reads on a closed database throw (reconcile retries)", throws { s.open(P1) })
    }
}

// ── Crash-injection model of the protocol ────────────────────

/** What a player file (or the live player) holds: items, the PDC marker, and whether it is in the event world. */
private data class PState(val items: List<String>, val marker: Long?, val inEvent: Boolean)

private val ORIGINAL = listOf("diamond_sword", "netherite_chestplate", "64xgold", "cursor:totem", "grid:elytra")

/**
 * Mirrors EventInventoryKeeper's ordering. [live] is the online player, [file] the last saved player
 * file. Every operation is one step; a crash discards [live] and reloads from [file].
 */
private class Model(val s: EventInventoryStore, val uuid: UUID, start: PState) {
    var file = start
    var live = start
    var failSaves = false
    var restores = 0

    fun save() { if (!failSaves) file = live }

    /** Entry steps (the keeper's enter()): row first, then one tick of clear + marker, then save. */
    fun entrySteps(): List<() -> Unit> {
        var id = -1L
        return listOf(
            { id = s.insertActive(uuid, "P", live.items.map { it } + List(41 - live.items.size) { null }, 0, "SURVIVAL", "boot", 1) },
            { live = PState(emptyList(), id, true) },
            { save() },
            { live = live.copy(items = listOf("event_gear:shovel")) }, // event kit (18. kit isolation)
            { save() },
        )
    }

    /** Exit steps for row [id] from [from] (the keeper's restore()). */
    fun restoreSteps(id: Long, from: String, inEventAfter: Boolean): List<() -> Unit> {
        lateinit var row: EventInventoryStore.Snapshot
        var claimed = false
        return listOf(
            {
                row = s.byId(id)!!
                claimed = row.status == from && s.move(id, from, RESTORING, "r", "system", 2, bootId = "boot", replaced = live.items)
            },
            { if (claimed) { restores++; live = PState(row.slots!!.filterNotNull(), null, inEventAfter) } },
            { if (claimed) save() },
            { if (claimed) s.move(id, RESTORING, RESTORED, "done", "system", 3) },
        )
    }

    fun state(inEvent: Boolean) = State(
        s.open(uuid)?.row(), live.marker,
        live.marker?.takeIf { it != s.open(uuid)?.id }?.let { s.forMarker(uuid, it)?.row() },
        inEvent, live.items.all { it.startsWith("event_gear:") },
    )

    /** Runs a full reconcile with no crash (join after a crash, or a world change). */
    fun reconcile(inEvent: Boolean) {
        repeat(3) {
            val plan = EventInventoryRules.reconcile(state(inEvent))
            var conflict = false
            for (step in plan.steps) {
                val ok = when (step) {
                    is Step.MarkStale -> s.move(step.id, step.from, STALE, step.reason, "system", 5)
                    is Step.MarkRestored -> s.move(step.id, RESTORING, RESTORED, step.reason, "system", 5)
                    is Step.ToReview -> s.move(step.id, step.from, REVIEW, step.reason, "system", 5)
                    is Step.OrphanReview -> { s.insertOrphanReview(uuid, "P", step.marker, step.reason, 5); true }
                    is Step.Restore -> { restoreSteps(step.id, step.from, inEvent).forEach { it() }; true }
                    is Step.AdoptLegacy -> { live = PState(emptyList(), step.id, true); save(); s.move(step.id, LEGACY, ACTIVE, "adopt", "system", 5) }
                    is Step.ClearMarker -> { live = live.copy(marker = null); save(); true }
                }
                if (!ok) { conflict = true; break }
            }
            if (conflict) return@repeat
            if (plan.then == Then.ENTER) entrySteps().forEach { it() }
            return
        }
        error("reconcile didn't settle")
    }

    fun crash() { live = file }
}

private fun ownItems(p: PState) = p.items.filterNot { it.startsWith("event_gear:") }.sorted()

private fun crashes() {
    var scenario = 0
    // A..D: crash after each entry step (and with a failed save), rejoin where the file says, then leave.
    val entryCount = 5
    for (failSave in listOf(false, true)) for (k in 0..entryCount) {
        val dir = Files.createTempDirectory("event-inventory-crash").toFile()
        withDb(dir) { s, _ ->
            val uuid = UUID(90L, (++scenario).toLong())
            val m = Model(s, uuid, PState(ORIGINAL, null, false))
            m.save()
            m.live = m.live.copy(inEvent = true) // teleported into the event world
            m.failSaves = failSave
            m.entrySteps().take(k).forEach { it() }
            m.crash()
            m.failSaves = false
            // H. join reconciliation where the file stands, then leave the event world.
            m.reconcile(m.file.inEvent)
            if (m.live.inEvent) m.reconcile(false).also { m.live = m.live.copy(inEvent = false) }
            m.save()
            val name = "entry crash after step $k (failed saves: $failSave)"
            check("$name: own items exactly once", ownItems(m.file) == ORIGINAL.sorted())
            check("$name: no marker left", m.file.marker == null)
            check("$name: no event gear outside", m.file.items.none { it.startsWith("event_gear:") })
            check("$name: nothing left open", s.open(uuid) == null)
            check("$name: never restored twice", m.restores <= 1)
        }
    }
    // E..F: crash after each restore step (player in the event world, leaving), rejoin, settle.
    val restoreCount = 4
    for (failSave in listOf(false, true)) for (quit in listOf(false, true)) for (k in 0..restoreCount) {
        val dir = Files.createTempDirectory("event-inventory-crash").toFile()
        withDb(dir) { s, _ ->
            val uuid = UUID(91L, (++scenario).toLong())
            val m = Model(s, uuid, PState(ORIGINAL, null, true))
            m.entrySteps().forEach { it() }
            val id = s.open(uuid)!!.id
            m.failSaves = failSave
            // quit restores in the event world; leaving restores outside it.
            m.live = m.live.copy(inEvent = quit)
            m.restoreSteps(id, ACTIVE, inEventAfter = quit).take(k).forEach { it() }
            m.crash()
            m.failSaves = false
            m.reconcile(m.file.inEvent)
            val reviewed = s.open(uuid)?.status == REVIEW
            val name = "restore crash after step $k (quit: $quit, failed saves: $failSave)"
            if (reviewed) {
                // 12. Only a failed save that was then "confirmed" can end here: items are still in the snapshot, nothing duplicated.
                check("$name: review only after a failed save", failSave)
                check("$name: review keeps the original items in the snapshot", s.byId(id)!!.slots!!.filterNotNull().sorted() == ORIGINAL.sorted())
                check("$name: review never duplicates (the file holds only event items)", ownItems(m.file).isEmpty())
                return@withDb
            }
            if (m.live.inEvent) m.reconcile(false).also { m.live = m.live.copy(inEvent = false) }
            m.save()
            check("$name: own items exactly once", ownItems(m.file) == ORIGINAL.sorted())
            check("$name: no marker left", m.file.marker == null)
            check("$name: nothing left open", s.open(uuid) == null)
            check("$name: every snapshot ends settled (restored or stale)", s.history(uuid).all { it.status == RESTORED || it.status == STALE })
        }
    }
    // 9. Multiple entries in a row, with a crash in the middle of the second.
    run {
        val dir = Files.createTempDirectory("event-inventory-crash").toFile()
        withDb(dir) { s, _ ->
            val uuid = UUID(92L, 1L)
            val m = Model(s, uuid, PState(ORIGINAL, null, true))
            m.entrySteps().forEach { it() }
            m.reconcile(false); m.live = m.live.copy(inEvent = false); m.save()
            m.live = m.live.copy(items = m.live.items + "new_loot", inEvent = true) // 17. legitimately obtained outside
            m.save()
            m.entrySteps().forEach { it() }
            m.crash()
            m.reconcile(m.file.inEvent)
            m.reconcile(false); m.live = m.live.copy(inEvent = false); m.save()
            check("9/17. second entry keeps items gained between entries, once", ownItems(m.file) == (ORIGINAL + "new_loot").sorted())
            check("9. two snapshots, both settled", s.history(uuid).size == 2 && s.open(uuid) == null)
        }
    }
    // 2/8. Stale snapshot never overwrites newer items: entry row stored, crash before the file changed,
    // player plays on outside and gains items, then comes back.
    run {
        val dir = Files.createTempDirectory("event-inventory-crash").toFile()
        withDb(dir) { s, _ ->
            val uuid = UUID(92L, 2L)
            val m = Model(s, uuid, PState(ORIGINAL, null, false))
            m.save()
            m.entrySteps().take(1).forEach { it() }
            m.crash() // file: original, outside, no marker
            m.reconcile(false)
            check("15. stale snapshot detected on join outside", s.history(uuid).first().status == STALE)
            m.live = m.live.copy(items = m.live.items - "64xgold" + "beacon"); m.save() // traded gold for a beacon
            m.live = m.live.copy(inEvent = true)
            m.reconcile(true)
            m.reconcile(false); m.live = m.live.copy(inEvent = false); m.save()
            check("2/8. the stale snapshot never brings the gold back", ownItems(m.file) == (ORIGINAL - "64xgold" + "beacon").sorted())
        }
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val manager = source("manager/EventManager.kt")
    val keeper = source("communityevent/inventory/EventInventoryKeeper.kt")
    val yml = File("src/main/resources/plugin.yml").readText()

    check("INV-03 EventManager no longer writes event_inventories.yml", !manager.contains("savedInventoryConfig") && !manager.contains("event_inventories.yml\")"))
    check("join reconciles synchronously at LOWEST", manager.contains("@EventHandler(priority = EventPriority.LOWEST)\n    fun onJoin(") && !manager.substringAfter("fun onJoin(").substringBefore("\n    }\n").contains("runTaskLater"))
    check("quit restores before Paper saves", manager.substringAfter("fun onQuit(").substringBefore("\n    }\n").contains("onLeaveEventWorld(player, \"quit\")"))
    check("shutdown restores players still inside", manager.substringAfter("fun shutdown() {").substringBefore("\n    }\n").contains("inventories.restoreForShutdown("))
    check("failed reconciles are retried", manager.contains("inventories.retryFailed()"))

    val enter = keeper.substringAfter("private fun enter(player: Player) {").substringBefore("\n    }\n")
    check("INV-04 entry captures cursor and crafting grid", enter.contains("player.itemOnCursor") && enter.contains("grid?.matrix") && enter.contains("RankedInventorySafety.place("))
    check("INV-03 every stack is read back before storing", enter.contains("decode(b64)") && enter.contains("isSimilar"))
    check("INV-03 the snapshot is stored before anything is cleared",
        enter.indexOf("store.insertActive(") in 0 until enter.indexOf("player.setItemOnCursor(null)") &&
            enter.indexOf("store.insertActive(") < enter.indexOf("inventory.contents = arrayOfNulls"))
    check("INV-03 refused/failed entry returns before any change",
        enter.indexOf("entryRefusal(") < enter.indexOf("store.insertActive(") && enter.substringAfter("store.insertActive(").substringBefore("player.setItemOnCursor(null)").contains("return"))
    check("marker set in the same tick as the clear, then saved",
        enter.indexOf("inventory.contents = arrayOfNulls") < enter.indexOf("persistentDataContainer.set(markerKey") &&
            enter.indexOf("persistentDataContainer.set(markerKey") < enter.indexOf("savePlayer(player)"))

    val restore = keeper.substringAfter("private fun restore(player: Player, id: Long").substringBefore("\n    }\n")
    check("INV-01 restore is claimed (RESTORING) before the player is touched",
        restore.indexOf("EventInventoryRules.RESTORING, \"restore started") in 0 until restore.indexOf("inventory.contents = contents"))
    check("INV-01 marker removed with the items, saved, then confirmed",
        restore.indexOf("inventory.contents = contents") < restore.indexOf("persistentDataContainer.remove(markerKey)") &&
            restore.indexOf("persistentDataContainer.remove(markerKey)") < restore.indexOf("savePlayer(player)") &&
            restore.indexOf("savePlayer(player)") < restore.indexOf("EventInventoryRules.RESTORED"))
    check("INV-01 snapshot data is never deleted", !keeper.contains("DELETE FROM event_inventory"))
    check("unreadable snapshots go to review, nothing changed", restore.indexOf("UNREADABLE") < restore.indexOf("inventory.contents = contents"))
    check("legacy YAML is renamed, never deleted", keeper.contains("event_inventories.yml.migrated-") && !keeper.contains("file.delete()"))
    check("staff permission declared", yml.contains("joshymc.event.inventory.recover:") && yml.contains("      joshymc.event.inventory.recover: true"))
    val commands = source("communityevent/inventory/EventInventoryCommands.kt")
    check("staff actions are audited and protected", commands.contains("AdminAudit.log(") && commands.contains("protectionProblem("))
    check("staff apply needs the literal confirm", commands.contains("equals(\"confirm\", ignoreCase = true)"))
}
