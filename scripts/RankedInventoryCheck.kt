// Checks the Ranked Team Wars inventory safety fixes (issue #1506, closed-beta findings B-2 and
// B-3/E-2): cursor and crafting-grid items are placed into the snapshot (never dropped, lost or
// duplicated; matches refuse when they don't fit), and the durable restore protocol
// (PENDING -> RESTORING -> RESTORED, interrupted restores -> REVIEW for staff) against a real SQLite
// database in fresh temp directories, reopened to simulate restarts, with the database closed to
// simulate failed writes. Run from the repo root:
//
//   ./gradlew -I scripts/ranked-inventory-check.init.gradle.kts rankedInventoryCheck
//
// Exits non-zero on the first failed check. Real ItemStacks (serialization needs a running server),
// open menus, cursor items and player data saves are NOT covered here; see docs/lifesteal-ranked-beta.md
// section L for the live-server checklist.

import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.Box
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety.PENDING
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety.RESTORED
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety.RESTORING
import com.liam.joshymc.lifesteal.ranked.RankedInventorySafety.REVIEW
import com.liam.joshymc.lifesteal.ranked.RankedPlayerState
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.manager.DatabaseManager
import java.io.File
import java.nio.file.Files
import java.util.Base64
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
    looseItems()
    statuses()
    store()
    legacyRows()
    wiring()
    println("Ranked inventory safety checks passed ($checks checks).")
}

// ── B-2: cursor and crafting grid ────────────────────────────

/** A stand-in item stack: id + metadata (custom item id, enchants, durability, PDC, model...) + amount. */
private data class Fake(val id: String, val meta: String = "", val amount: Int = 1, val max: Int = 64)

private object FakeStacks : RankedInventorySafety.Stacks<Fake> {
    override fun similar(a: Fake, b: Fake) = a.id == b.id && a.meta == b.meta
    override fun amount(stack: Fake) = stack.amount
    override fun maxStack(stack: Fake) = stack.max
    override fun withAmount(stack: Fake, amount: Int) = stack.copy(amount = amount)
}

private fun inv(vararg filled: Pair<Int, Fake>): List<Fake?> = MutableList<Fake?>(41) { null }.also { for ((i, f) in filled) it[i] = f }

private fun total(items: Iterable<Fake?>, id: String, meta: String = ""): Int = items.filterNotNull().filter { it.id == id && it.meta == meta }.sumOf { it.amount }

private fun looseItems() {
    val sword = Fake("netherite_sword", "custom_item_id=autumns_edge;ce_lifesteal=3;damage=37;model=autumns_edge;pdc=joshymc:x", 1, 1)
    val armor = Fake("netherite_chestplate", "prot4", 1, 1)

    // 1. Empty cursor: nothing changes.
    val base = inv(0 to Fake("cobblestone", amount = 10), 36 to armor)
    val none = RankedInventorySafety.place(base, emptyList(), FakeStacks)
    check("1. empty cursor and grid change nothing", none.fits && none.changed.isEmpty() && none.slots == base)

    // 2. One item on the cursor lands in the first empty storage slot.
    val one = RankedInventorySafety.place(base, listOf(Fake("diamond", amount = 3)), FakeStacks)
    check("2. a cursor item lands in the first empty storage slot", one.fits && one.slots[1] == Fake("diamond", amount = 3) && one.changed == setOf(1))

    // 3. A full stack on the cursor tops up a similar partial stack, the rest goes to an empty slot.
    val full = RankedInventorySafety.place(base, listOf(Fake("cobblestone", amount = 64)), FakeStacks)
    check("3. a full stack tops up the similar partial stack first", full.slots[0]?.amount == 64 && full.slots[1]?.amount == 10)
    check("3. amounts are conserved exactly", total(full.slots, "cobblestone") == 74 && full.leftover.isEmpty())

    // 4. Custom metadata is kept and never merged into a different stack.
    val custom = RankedInventorySafety.place(inv(0 to sword.copy(meta = "other")), listOf(sword), FakeStacks)
    check("4. a custom item keeps its exact metadata", custom.slots[1] == sword && custom.slots[0]?.meta == "other")
    val similarMeta = RankedInventorySafety.place(inv(0 to Fake("arrow", "potion=slow", 10)), listOf(Fake("arrow", "", 5)), FakeStacks)
    check("4. stacks only merge when the metadata matches", similarMeta.slots[0]?.amount == 10 && similarMeta.slots[1] == Fake("arrow", "", 5))

    // 5 / 6. Crafting grid with one item and with several.
    val grid1 = RankedInventorySafety.place(base, listOf(Fake("oak_planks", amount = 1)), FakeStacks)
    check("5. a crafting grid with one item is kept", total(grid1.slots, "oak_planks") == 1)
    val grid4 = listOf(Fake("oak_planks", amount = 2), Fake("stick", amount = 1), Fake("oak_planks", amount = 2), Fake("diamond", amount = 1))
    val multi = RankedInventorySafety.place(base, listOf(Fake("gold_ingot", amount = 7)) + grid4, FakeStacks)
    check("6. cursor + a full 2x2 grid are all kept, amounts exact", total(multi.slots, "oak_planks") == 4 && total(multi.slots, "stick") == 1 &&
        total(multi.slots, "diamond") == 1 && total(multi.slots, "gold_ingot") == 7 && multi.fits)
    val grid9 = List(9) { Fake("iron_ingot", amount = 64) }
    val bench = RankedInventorySafety.place(base, grid9, FakeStacks)
    check("6. an open crafting table's 3x3 grid is kept too", total(bench.slots, "iron_ingot") == 9 * 64 && bench.fits)

    // 7. The crafting result is never captured (it is derived from the grid): only grid stacks are placed.
    val crafting = source("lifesteal/ranked/LifestealRankedManager.kt")
    check("7. the crafting result slot is never captured, and is cleared with the grid",
        crafting.contains("grid?.matrix?.forEach") && !crafting.contains("grid?.result?.let { add(") && crafting.contains("g.result = null"))

    // 8. A full inventory: the loose items don't fit and the match is refused (nothing dropped).
    val packed = MutableList<Fake?>(41) { Fake("dirt", "slot$it", 64) }
    val refused = RankedInventorySafety.place(packed, listOf(sword, Fake("diamond", amount = 5)), FakeStacks)
    check("8. a full inventory can't take the cursor items", !refused.fits && refused.leftover.size == 2 && refused.changed.isEmpty())
    check("8. leftovers keep their exact amount and metadata", refused.leftover == listOf(sword, Fake("diamond", amount = 5)))
    check("8. the refusal names the problem", RankedInventorySafety.looseItemsProblem("Steve", refused.leftover.size)?.contains("cursor or crafting grid") == true &&
        RankedInventorySafety.looseItemsProblem("Steve", 0) == null)
    val partial = RankedInventorySafety.place(MutableList<Fake?>(41) { if (it == 5) Fake("diamond", amount = 60) else Fake("dirt", "slot$it", 64) }, listOf(Fake("diamond", amount = 10)), FakeStacks)
    check("8. a partial fit still refuses (never splits a stack between inventory and floor)", !partial.fits && partial.leftover == listOf(Fake("diamond", amount = 6)))

    // 9. Never armor or off-hand: returned items only go into storage slots 0..35.
    val armorFree = inv(*(0 until 36).map { it to Fake("dirt", "slot$it", 64) }.toTypedArray())
    val noArmor = RankedInventorySafety.place(armorFree, listOf(Fake("diamond")), FakeStacks)
    check("9. loose items never land in armor or off-hand slots", !noArmor.fits && (36..40).all { noArmor.slots[it] == null })

    // 10. No duplication: every placed amount comes from the loose list exactly once.
    val loose = listOf(Fake("cobblestone", amount = 30), Fake("cobblestone", amount = 40), sword)
    val placed = RankedInventorySafety.place(base, loose, FakeStacks)
    check("10. no duplication: inventory gains exactly the loose items", total(placed.slots, "cobblestone") == 10 + 70 && total(placed.slots, sword.id, sword.meta) == 1)
    check("10. the input list is never mutated", base[1] == null && base[0]?.amount == 10)

    // Menus that hand their items back on close.
    check("open anvil with items refuses", RankedInventorySafety.openMenuProblem("Steve", "ANVIL", true) != null)
    check("open anvil without items is fine", RankedInventorySafety.openMenuProblem("Steve", "ANVIL", false) == null)
    check("an open chest's items aren't the player's (never refused)", RankedInventorySafety.openMenuProblem("Steve", "CHEST", true) == null)
    check("crafting grids are placed, not refused", RankedInventorySafety.CRAFTING_GRIDS == setOf("CRAFTING", "WORKBENCH") &&
        RankedInventorySafety.openMenuProblem("Steve", "WORKBENCH", true) == null)
}

// ── State machine ────────────────────────────────────────────

private fun statuses() {
    check("PENDING -> RESTORING", RankedInventorySafety.canMove(PENDING, RESTORING))
    check("RESTORING -> RESTORED", RankedInventorySafety.canMove(RESTORING, RESTORED))
    check("RESTORING -> REVIEW", RankedInventorySafety.canMove(RESTORING, REVIEW))
    check("REVIEW -> RESTORING (staff apply)", RankedInventorySafety.canMove(REVIEW, RESTORING))
    check("REVIEW -> RESTORED (staff resolve)", RankedInventorySafety.canMove(REVIEW, RESTORED))
    check("RESTORED is final", RankedInventorySafety.STATUSES.none { RankedInventorySafety.canMove(RESTORED, it) })
    check("nothing goes back to PENDING", RankedInventorySafety.STATUSES.none { RankedInventorySafety.canMove(it, PENDING) })
    check("only PENDING is applied automatically", RankedInventorySafety.STATUSES.filter { RankedInventorySafety.autoApplies(it) } == listOf(PENDING))
    check("a RESTORING row from this boot is left alone", RankedInventorySafety.interruptedRestoreTarget("boot-1", "boot-1") == null)
    check("a RESTORING row from another boot needs review", RankedInventorySafety.interruptedRestoreTarget("boot-0", "boot-1") == REVIEW)
    check("a RESTORING row with no boot needs review", RankedInventorySafety.interruptedRestoreTarget(null, "boot-1") == REVIEW)
}

// ── Store (real SQLite) ──────────────────────────────────────

private fun <T> withDb(dir: File, block: (RankedStore, DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        val s = RankedStore(db)
        s.createTables()
        return block(s, db)
    } finally {
        db.shutdown()
    }
}

private fun item(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray())

private val A1 = UUID(50L, 1L)
private val A2 = UUID(50L, 2L)
private val B1 = UUID(60L, 1L)
private val B2 = UUID(60L, 2L)
private val ALL = listOf(A1, A2, B1, B2)

private fun arena(id: String) = RankedArena(
    id = id, displayName = "Pit", world = "lifesteal", bounds = Box.of(0, 60, 0, 40, 80, 40),
    teamA = listOf(Point(5.5, 64.0, 5.5)), teamB = listOf(Point(35.5, 64.0, 35.5)), spectators = listOf(Point(20.5, 90.0, 20.5)), enabled = true,
)

private fun newMatch(id: String, arena: String, now: Long) =
    RankedStore.NewMatch(id, "season-1", arena, "team-$id-a", "Alpha", "team-$id-b", "Bravo", 2, false, "queue:x", now)

private fun participants(id: String, uuids: List<UUID>) =
    listOf(MatchParticipant(uuids[0], Side.A, "team-$id-a"), MatchParticipant(uuids[1], Side.A, "team-$id-a"), MatchParticipant(uuids[2], Side.B, "team-$id-b"), MatchParticipant(uuids[3], Side.B, "team-$id-b"))

private fun snap(uuid: UUID, vararg slots: String?): RankedStore.SnapshotData {
    val list = MutableList<String?>(41) { null }
    for ((i, s) in slots.withIndex()) list[i] = s
    return RankedStore.SnapshotData(uuid, list, 0, "lifesteal", Point(1.0, 70.0, 1.0), RankedPlayerState.Xp(30, 0.5f, 1395), emptyList())
}

private fun store() {
    val dir = Files.createTempDirectory("ranked-inventory-check").toFile()
    val customSword = item("netherite_sword{custom_item_id:autumns_edge,ce:3,Damage:37,model:autumns_edge}")
    val cursorItem = item("diamond x64 (was on the cursor)")
    val gridItem = item("oak_planks x2 (was in the crafting grid)")
    try {
        withDb(dir) { s, _ ->
            s.createArena(arena("pit"), 1)
            s.updateArena(arena("pit").copy(version = s.arena("pit")!!.arena.version), 2)
            s.createArena(arena("pit2"), 3)
            s.updateArena(arena("pit2").copy(version = s.arena("pit2")!!.arena.version), 4)

            // 11 / 16. Successful persistence, bound to player and match; cursor/grid items are in the slots.
            s.insertMatch(newMatch("m1", "pit", 10), participants("m1", ALL), listOf(snap(A1, customSword, cursorItem, gridItem), snap(A2), snap(B1), snap(B2)))
            val a1 = s.pendingSnapshot(A1)!!
            check("11. the snapshot is stored with the cursor and crafting-grid items in their slots", a1.slots[0] == customSword && a1.slots[1] == cursorItem && a1.slots[2] == gridItem)
            check("16. the snapshot is bound to the player and the match", a1.uuid == A1 && a1.matchId == "m1" && a1.id == s.snapshotId("m1", A1))
            check("36. XP fields are kept", a1.xpLevel == 30 && a1.xpTotal == 1395 && a1.effects == "")

            // 12 / 13 / 15. A failed persistence writes nothing; a repeated snapshot for the same player is refused.
            check("15. a second open snapshot for a player is refused", throws {
                s.insertMatch(newMatch("m2", "pit2", 11), participants("m2", listOf(A1, UUID(70L, 1), UUID(70L, 2), UUID(70L, 3))),
                    listOf(snap(A1, item("new")), snap(UUID(70L, 1)), snap(UUID(70L, 2)), snap(UUID(70L, 3))))
            })
            check("12. a failed persistence writes nothing", s.match("m2") == null && s.snapshotsOf("m2").isEmpty() && s.pendingSnapshot(A1)?.matchId == "m1")

            // End the match (completion).
            s.moveState("m1", com.liam.joshymc.lifesteal.ranked.MatchState.PREPARING, com.liam.joshymc.lifesteal.ranked.MatchState.COUNTDOWN, 12)
            s.moveState("m1", com.liam.joshymc.lifesteal.ranked.MatchState.COUNTDOWN, com.liam.joshymc.lifesteal.ranked.MatchState.ACTIVE, 13)
            s.finish("m1", Side.A, "team-m1-a", 14)
            s.complete("m1", 15)

            // 17. Successful restoration: begin, (apply), confirm.
            check("17. begin restore (PENDING -> RESTORING)", s.beginRestore(a1.id, PENDING, "boot-1", "match completed", 20))
            check("17. a RESTORING snapshot is no longer pending (no join or respawn applies it)", s.pendingSnapshot(A1) == null && A1 !in s.pendingSnapshotOwners())
            check("17. while RESTORING the player counts as unsettled", s.unsettledSnapshot(A1)?.status == RESTORING && A1 in s.unsettledSnapshotOwners())
            check("17. confirm (RESTORING -> RESTORED)", s.markRestored(a1.id, "match completed", 21) && s.snapshot(a1.id)?.status == RESTORED)
            check("17. settled", s.unsettledSnapshot(A1) == null)

            // 23 / 24 / 26. Duplicate requests (join + respawn in the same tick, a second completion): the second begin takes nothing.
            val a2 = s.pendingSnapshot(A2)!!
            check("24. the first of two concurrent restores takes the snapshot", s.beginRestore(a2.id, PENDING, "boot-1", "rejoined", 22))
            check("24. the second concurrent restore takes nothing (never applied twice)", !s.beginRestore(a2.id, PENDING, "boot-1", "respawned", 22))
            s.markRestored(a2.id, "rejoined", 23)
            check("23 / 26. a restore after completion takes nothing", !s.beginRestore(a2.id, PENDING, "boot-1", "match completed", 24) && !s.markRestored(a2.id, "again", 24))
            check("the trigger test setup itself succeeds", !throws { dbUpdate(File(dir, "raw-ok"), revert = false) })
            check("RESTORED can't be moved back to PENDING (trigger)", throws { dbUpdate(File(dir, "raw-bad"), revert = true) })
        }

        // 18. Begin on a closed database throws; after reopening, the snapshot is still PENDING.
        run {
            val db = DatabaseManager(dir, quietLogger())
            db.start()
            val s = RankedStore(db)
            s.createTables()
            val b1 = s.pendingSnapshot(B1)!!
            db.shutdown()
            check("18. a database failure before restoration throws (the caller changes nothing)", throws { s.beginRestore(b1.id, PENDING, "boot-1", "rejoined", 30) })
        }
        withDb(dir) { s, _ ->
            check("18. after a failed begin the snapshot is still PENDING and complete", s.pendingSnapshot(B1)?.status == PENDING && s.pendingSnapshot(B1)?.slots?.size == 41)
            check("22. nothing to review after a failed begin", s.flagInterruptedRestores("boot-2", 31).isEmpty())
        }

        // 19 / 20. A failure during the lifecycle, after the inventory was set: begin committed, confirm fails.
        run {
            val db = DatabaseManager(dir, quietLogger())
            db.start()
            val s = RankedStore(db)
            s.createTables()
            val b1 = s.pendingSnapshot(B1)!!
            check("19. begin committed", s.beginRestore(b1.id, PENDING, "boot-2", "rejoined", 40))
            // ... the inventory is set and saved here, then the database fails:
            db.shutdown()
            check("20. the confirm after the inventory change fails", throws { s.markRestored(b1.id, "rejoined", 41) })
        }

        // 21 / 22. Reconnect / restart after the uncertain restore: never applied again, flagged for staff.
        withDb(dir) { s, _ ->
            check("21. after an uncertain restore the snapshot is not pending (a reconnect applies nothing)", s.pendingSnapshot(B1) == null && B1 !in s.pendingSnapshotOwners())
            check("this boot's own RESTORING rows are left alone", s.flagInterruptedRestores("boot-2", 50).isEmpty())
            val flagged = s.flagInterruptedRestores("boot-3", 51)
            check("22. a restart flags the interrupted restore for review", flagged.map { it.uuid } == listOf(B1) && flagged.single().status == REVIEW)
            check("22. flagging runs once", s.flagInterruptedRestores("boot-3", 52).isEmpty())
            val review = s.unsettledSnapshot(B1)!!
            check("29. the ambiguous state is REVIEW with a reason", review.status == REVIEW && review.reviewReason == RankedInventorySafety.INTERRUPTED_REASON)
            check("30. the recovery data stays available for staff", review.slots.size == 41 && s.reviewSnapshots(10).map { it.id } == listOf(review.id) && review.restoreStartedAt == 40L)
            check("29. a REVIEW snapshot is never applied by the automatic path", !s.beginRestore(review.id, PENDING, "boot-3", "rejoined", 53) && !s.markRestored(review.id, "rejoined", 53))
            check("match restoration shows REVIEW", s.match("m1")?.restoration == "REVIEW")
            check("events record the review", s.events("m1", 50).any { it.kind == "RESTORE_REVIEW" && it.uuid == B1 })

            // 25. An old snapshot must not overwrite a newer match: a player with a REVIEW snapshot can't enter a new match.
            check("25. a player waiting for review can't be snapshotted for a new match", throws {
                s.insertMatch(newMatch("m3", "pit2", 54), participants("m3", listOf(B1, UUID(80L, 1), UUID(80L, 2), UUID(80L, 3))),
                    listOf(snap(B1, item("newer")), snap(UUID(80L, 1)), snap(UUID(80L, 2)), snap(UUID(80L, 3))))
            })
            check("25. nothing was written for the refused match", s.match("m3") == null)

            // Staff resolve: it already came back.
            check("staff resolve: REVIEW -> RESTORED, nothing applied", s.resolveReview(review.id, "resolved by staff", 55) && s.snapshot(review.id)?.status == RESTORED)
            check("resolve happens once", !s.resolveReview(review.id, "again", 56))
            check("the player can play again after the decision", s.unsettledSnapshot(B1) == null)
        }

        // 27 / 28. Cancellation and disconnect forfeit: a cancelled match's snapshot is restored once; the disconnected one waits.
        withDb(dir) { s, _ ->
            val p = listOf(UUID(90L, 1), UUID(90L, 2), UUID(91L, 1), UUID(91L, 2))
            s.insertMatch(newMatch("m4", "pit2", 60), participants("m4", p), p.map { snap(it, customSword) })
            s.moveState("m4", com.liam.joshymc.lifesteal.ranked.MatchState.PREPARING, com.liam.joshymc.lifesteal.ranked.MatchState.COUNTDOWN, 61)
            s.moveState("m4", com.liam.joshymc.lifesteal.ranked.MatchState.COUNTDOWN, com.liam.joshymc.lifesteal.ranked.MatchState.ACTIVE, 62)
            s.defeat("m4", p[3], com.liam.joshymc.lifesteal.ranked.DefeatReason.DISCONNECT, 63)
            check("cancel", s.cancel("m4", "staff", 64))
            for (u in p.dropLast(1)) {
                val id = s.pendingSnapshot(u)!!.id
                check("27. cancelled match restores $u once", s.beginRestore(id, PENDING, "boot-4", "match cancelled", 65) && s.markRestored(id, "match cancelled", 65) &&
                    !s.beginRestore(id, PENDING, "boot-4", "match cancelled", 66))
            }
            val forfeit = s.pendingSnapshot(p[3])!!
            check("28. the disconnected player's snapshot waits for their return", forfeit.matchId == "m4" && forfeit.slots[0] == customSword)
            check("28. their rejoin restores it once", s.beginRestore(forfeit.id, PENDING, "boot-4", "rejoined", 70) && s.markRestored(forfeit.id, "rejoined", 70) &&
                !s.beginRestore(forfeit.id, PENDING, "boot-4", "respawned", 71))
            check("the match restoration is COMPLETE once everything is RESTORED", s.match("m4")?.restoration == "COMPLETE")

            // Staff apply of a review: REVIEW -> RESTORING -> RESTORED.
            val q = listOf(UUID(92L, 1), UUID(92L, 2), UUID(93L, 1), UUID(93L, 2))
            s.insertMatch(newMatch("m5", "pit2", 80), participants("m5", q), q.map { snap(it) })
            s.cancel("m5", "test", 81)
            val id = s.pendingSnapshot(q[0])!!.id
            s.beginRestore(id, PENDING, "boot-old", "rejoined", 82)
            check("crash leaves RESTORING", s.flagInterruptedRestores("boot-4", 83).map { it.id } == listOf(id))
            check("the automatic path can't start from REVIEW as if PENDING", !s.beginRestore(id, PENDING, "boot-4", "rejoined", 84))
            check("staff apply starts from REVIEW", s.beginRestore(id, REVIEW, "boot-4", "staff applied review", 85) && s.markRestored(id, "staff applied review", 86))
            check("a begin from RESTORED is refused before the database", throws { s.beginRestore(id, RESTORED, "boot-4", "x", 87) })
            for (u in q.drop(1)) { val sid = s.pendingSnapshot(u)!!.id; s.beginRestore(sid, PENDING, "boot-4", "t", 88); s.markRestored(sid, "t", 88) }
            check("38. recovery processes nothing twice", s.flagInterruptedRestores("boot-5", 90).isEmpty() && s.unsettledSnapshotOwners().isEmpty())
        }
    } finally {
        dir.deleteRecursively()
    }
}

/** Tries to move a RESTORED snapshot back to PENDING with raw SQL; the trigger must refuse it. */
private fun dbUpdate(dir: File, revert: Boolean) {
    val db = DatabaseManager(dir.apply { mkdirs() }, quietLogger())
    db.start()
    try {
        val s = RankedStore(db)
        s.createTables()
        s.createArena(arena("x"), 1)
        s.updateArena(arena("x").copy(version = s.arena("x")!!.arena.version), 2)
        val p = listOf(UUID(1L, 1), UUID(1L, 2), UUID(2L, 1), UUID(2L, 2))
        s.insertMatch(newMatch("raw", "x", 3), participants("raw", p), p.map { snap(it) })
        val id = s.pendingSnapshot(p[0])!!.id
        s.markRestored(id, "t", 4)
        if (revert) db.execute("UPDATE lifesteal_ranked_snapshots SET status = 'PENDING' WHERE snapshot_id = ?", id)
        else db.execute("UPDATE lifesteal_ranked_snapshots SET restore_reason = 'note' WHERE snapshot_id = ?", id)
    } finally {
        db.shutdown()
    }
}

// ── 14. Snapshots made before #1506 ──────────────────────────

private fun legacyRows() {
    val dir = Files.createTempDirectory("ranked-inventory-legacy").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            // The pre-#1506 (and pre-#1490) snapshot table, with one PENDING and one RESTORED row.
            db.createTable(
                """CREATE TABLE lifesteal_ranked_snapshots (
                    snapshot_id TEXT PRIMARY KEY, match_id TEXT NOT NULL, uuid TEXT NOT NULL, contents TEXT NOT NULL,
                    slot_count INTEGER NOT NULL, held_slot INTEGER NOT NULL, return_world TEXT,
                    return_x REAL, return_y REAL, return_z REAL, return_yaw REAL, return_pitch REAL,
                    status TEXT NOT NULL, created_at INTEGER NOT NULL, restored_at INTEGER, restore_reason TEXT,
                    UNIQUE (match_id, uuid))""".trimIndent()
            )
            val old = UUID(99L, 1L)
            val slots = RankedStore.encodeSlots(List(41) { if (it == 0) item("legacy sword") else null })
            db.execute("INSERT INTO lifesteal_ranked_snapshots (snapshot_id, match_id, uuid, contents, slot_count, held_slot, status, created_at) VALUES ('old:1', 'old', ?, ?, 41, 0, 'PENDING', 1)", old.toString(), slots)
            db.execute("INSERT INTO lifesteal_ranked_snapshots (snapshot_id, match_id, uuid, contents, slot_count, held_slot, status, created_at) VALUES ('old:2', 'old2', ?, ?, 41, 0, 'RESTORED', 1)", old.toString(), slots)
            val s = RankedStore(db)
            s.createTables()
            check("14. migration keeps an old PENDING snapshot pending and readable", s.pendingSnapshot(old)?.slots?.get(0) == item("legacy sword"))
            check("14. old rows have no review data and no XP (left untouched)", s.pendingSnapshot(old)?.let { it.reviewReason == null && it.restoreStartedAt == null && it.xpLevel == null } == true)
            check("14. old RESTORED rows stay RESTORED", s.snapshot("old:2")?.status == RESTORED)
            check("14. old PENDING rows are never moved to review by the migration", s.flagInterruptedRestores("boot", 2).isEmpty() && s.unsettledSnapshotOwners().isEmpty())
            check("14. an old PENDING snapshot goes through the new protocol", s.beginRestore("old:1", PENDING, "boot", "rejoined", 3) && s.markRestored("old:1", "rejoined", 4))
            check("14. migrating twice is harmless", !throws { s.createTables() })
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val command = source("lifesteal/ranked/LifestealRankedCommand.kt")
    val store = source("lifesteal/ranked/RankedStore.kt")

    val captureBody = manager.substringAfter("private fun capture(player: Player): Captured {").substringBefore("\n    }\n")
    check("B-2 capture only reads: it never closes the menu, clears the cursor or changes a slot",
        !captureBody.contains("closeInventory") && !captureBody.contains("setItemOnCursor") && !captureBody.contains("setItem(") && !captureBody.contains("matrix ="))
    check("B-2 capture includes the cursor and the crafting grid", captureBody.contains("player.itemOnCursor") && captureBody.contains("grid?.matrix") && captureBody.contains("RankedInventorySafety.place("))
    check("B-2 capture copies stacks (metadata kept byte for byte by serializeAsBytes)", captureBody.contains("add(it.clone())") && captureBody.contains("serializeAsBytes()"))
    check("B-2 capture refuses rather than dropping", captureBody.contains("looseItemsProblem(") && captureBody.contains("openMenuProblem(") && captureBody.contains("throw CaptureRefused("))
    val start = manager.substringAfter("private fun startMatch(").substringBefore("\n    }\n")
    check("13. the live inventory changes only after the snapshot was stored",
        start.indexOf("players.map { capture(it) }") in 0 until start.indexOf("store.insertMatch(") && start.indexOf("store.insertMatch(") < start.indexOf("for (c in captures) applyCapture(c)"))
    check("13. a refused capture or a failed insert returns before anything is applied",
        start.indexOf("catch (e: CaptureRefused)") < start.indexOf("applyCapture(") && start.indexOf("couldn't be recorded, so it didn't start") < start.indexOf("applyCapture("))
    check("12. snapshot failures are audited", manager.contains("\"RANKED_SNAPSHOT_REFUSED\"") && manager.contains("\"RANKED_SNAPSHOT_FAILED\""))
    val applyBody = manager.substringAfter("private fun applyCapture(c: Captured) {").substringBefore("\n    }\n")
    check("9. applying clears cursor and grid before moving items and closing (nothing left to drop)",
        applyBody.indexOf("player.setItemOnCursor(null)") in 0 until applyBody.indexOf("player.inventory.setItem(slot, stack)") &&
            applyBody.indexOf("g.matrix = arrayOfNulls") < applyBody.indexOf("player.inventory.setItem(slot, stack)") &&
            applyBody.indexOf("player.inventory.setItem(slot, stack)") < applyBody.indexOf("player.closeInventory()"))

    val restoreBody = manager.substringAfter("private fun restore(player: Player, snapshot: RankedStore.Snapshot").substringBefore("\n    }\n")
    check("B-3 the restore intent is durable before the player is touched",
        restoreBody.indexOf("store.beginRestore(") in 0 until restoreBody.indexOf("player.setItemOnCursor(null)") &&
            restoreBody.indexOf("store.beginRestore(") < restoreBody.indexOf("inventory.contents = contents"))
    check("B-3 a failed begin returns before any change", restoreBody.substringAfter("store.beginRestore(").substringBefore("if (!claimed)").contains("return false"))
    check("B-3 a begin that takes nothing applies nothing", restoreBody.substringAfter("if (!claimed) {").substringBefore("}").contains("return false") &&
        manager.contains("\"RANKED_RESTORE_DUPLICATE_PREVENTED\""))
    check("B-3 apply, save, then confirm", restoreBody.indexOf("inventory.contents = contents") < restoreBody.indexOf("player.saveData()") &&
        restoreBody.indexOf("player.saveData()") < restoreBody.indexOf("store.markRestored("))
    check("B-3 a failed confirm is never treated as PENDING again (remembered, retried)", restoreBody.contains("unconfirmed[snapshot.id] = Unconfirmed(") &&
        !restoreBody.contains("status != \"PENDING\"") && manager.contains("\"RANKED_RESTORE_MARK_FAILED\""))
    check("B-3 the confirm is retried on join, restore, staff restore and shutdown",
        Regex("retryUnconfirmed\\(\\)").findAll(manager).count() >= 5 && manager.substringAfter("fun stop() {").substringBefore("\n    }\n").contains("retryUnconfirmed()"))
    check("E-2 interrupted restores are flagged once per boot, with this boot's id",
        manager.substringAfter("if (!recoveredThisBoot) {").substringBefore("}").contains("flagInterruptedRestores()") && manager.contains("store.flagInterruptedRestores(bootId,") &&
            manager.contains("store.beginRestore(snapshot.id, from, bootId,") && manager.contains("\"RANKED_RESTORE_REVIEW\""))
    check("25. players with an unsettled restore can't enter a match", manager.contains("pendingRestore = pendingRestore.contains(uuid) || unsettled.contains(uuid)"))
    check("24. the in-process re-entry guard stays", restoreBody.contains("if (!restoring.add(uuid)) {"))
    check("restore still discards cursor/grid/menu items (match items, never dropped)",
        restoreBody.contains("RankedInventorySafety.CRAFTING_GRIDS") && restoreBody.contains("RankedInventorySafety.RETURNED_ON_CLOSE") && restoreBody.indexOf("top.clear()") < restoreBody.indexOf("player.closeInventory()"))
    check("36. XP/effects are still applied inside the one restore", restoreBody.indexOf("applyState(player, snapshot, state)") in restoreBody.indexOf("inventory.contents = contents") until restoreBody.indexOf("player.saveData()"))
    check("33/34. hearts and Elo are untouched by restoration", !restoreBody.contains("heart", ignoreCase = true) && !restoreBody.contains("rating", ignoreCase = true))

    check("staff: a REVIEW snapshot is applied or resolved only with an explicit choice and confirm",
        command.contains("if (mode != null && !confirmed(a, 2))") && command.contains("ranked.adminRestore(sender, target, mode)") && command.contains("\"RANKED_RESTORE_"))
    check("staff: apply starts from REVIEW, resolve applies nothing", manager.contains("from = RankedInventorySafety.REVIEW)") && manager.contains("store.resolveReview(open.id,"))
    check("staff: review list", command.contains("\"restores\" ->") && command.contains("ranked.reviewSnapshots()"))
    check("store: one unsettled snapshot per player and a status trigger",
        store.contains("idx_lifesteal_ranked_snapshots_unsettled") && store.contains("trg_lifesteal_ranked_snapshots_status"))
    check("store: the migration only adds columns", Regex("ALTER TABLE lifesteal_ranked_snapshots ADD COLUMN").findAll(store).count() == 2 && !store.contains("DROP TABLE"))
}
