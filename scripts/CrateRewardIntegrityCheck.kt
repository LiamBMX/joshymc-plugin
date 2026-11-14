// Checks crate reward integrity (issue #1529): voucher re-signing rules, the crate_pending_rewards
// transaction store against a real SQLite database (fresh temp directory, deleted afterwards),
// its migration from the pre-#1529 schema, the event-world delivery route, and the persistence
// protocol with a crash injected at every step against a model of "live inventory" vs
// "player file". The Gradle build has no test source set, so it runs through an init script:
//
//   ./gradlew -I scripts/crate-reward-integrity-check.init.gradle.kts crateRewardIntegrityCheck
//
// What this proves and what it doesn't: the ordering and recovery rules are exercised for real
// (same store, same protocol functions CrateManager calls). The model assumes what Paper does:
// `saveData()` replaces the player file atomically and nothing else saves the player inside the
// same tick. Whether a real hard kill behaves like the model needs the live Paper checklist in
// docs/crate-reward-integrity.md.
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.crate.CrateDeliveryProtocol
import com.liam.joshymc.crate.CrateDeliveryProtocol.SimulatedCrash
import com.liam.joshymc.crate.CrateDeliveryRecovery
import com.liam.joshymc.crate.CrateDeliveryRules
import com.liam.joshymc.crate.CratePendingStore
import com.liam.joshymc.crate.CratePendingStore.Status
import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.staff.PremiumVoucherRules
import com.liam.joshymc.staff.PremiumVoucherRules.Reissue
import com.liam.joshymc.staff.PremiumVoucherRules.Verdict
import java.io.File
import java.nio.file.Files
import java.util.UUID
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false }

private fun <T> withDb(block: (DatabaseManager) -> T): T {
    val dir = Files.createTempDirectory("crate-integrity").toFile()
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
        dir.deleteRecursively()
    }
}

// ── A model of one player: the live (in-memory) state and the saved player file ──────────

private data class Stack(val token: String?, val amount: Int)

private class PlayerState(var keys: Int = 0, val items: MutableList<Stack> = mutableListOf()) {
    fun copy() = PlayerState(keys, items.toMutableList())
    fun total() = items.sumOf { it.amount }
    fun marked(token: String) = items.filter { it.token == token }.sumOf { it.amount }
    fun strip(token: String?) {
        items.replaceAll { if (it.token != null && (token == null || it.token == token)) it.copy(token = null) else it }
    }
}

private class Player(capacity: Int = 1000) {
    var live = PlayerState()
    var file = PlayerState()
    var room = capacity
    /** Server crashes: memory is gone, the next join loads the file. */
    fun crashAndRejoin() { live = file.copy() }
    fun save() { file = live.copy() }
}

private enum class Crash { NONE, BEFORE_SAVE_WRITE, AFTER_SAVE_WRITE, AFTER_ACK, IN_DEPOSIT }

private const val OWNER = "00000000-0000-0000-0000-000000000001"
private fun item(amount: Int) = "item:$amount"
private fun amountOf(item: String) = item.removePrefix("item:").toInt()

/** One delivery attempt through the real protocol against the model. */
private fun deliver(store: CratePendingStore, p: Player, id: String, crash: Crash, saveFails: Boolean = false): CrateDeliveryProtocol.Result? {
    val row = store.row(id) ?: return null
    val amount = amountOf(row.item)
    val token = UUID.randomUUID().toString()
    return try {
        CrateDeliveryProtocol.deliver(
            store, id, OWNER, token, amount,
            deposit = { t ->
                if (crash == Crash.IN_DEPOSIT) throw SimulatedCrash()
                val placed = minOf(p.room, amount)
                if (placed > 0) { p.live.items += Stack(t, placed); p.room -= placed }
                if (placed < amount) (amount - placed) to item(amount - placed) else null
            },
            save = {
                if (crash == Crash.BEFORE_SAVE_WRITE) throw SimulatedCrash()
                if (saveFails) throw IllegalStateException("disk full")
                p.save()
                if (crash == Crash.AFTER_SAVE_WRITE) throw SimulatedCrash()
            },
            stripToken = { t ->
                if (crash == Crash.AFTER_ACK) throw SimulatedCrash()
                p.live.strip(t)
            },
            now = { 1L }
        )
    } catch (_: SimulatedCrash) {
        null
    }
}

/** What CrateManager does at join: reconcile DELIVERING rows from the loaded file, strip marks. */
private fun rejoin(store: CratePendingStore, p: Player) {
    for (row in store.rowsOf(OWNER, Status.DELIVERING)) {
        val marked = row.deliveryToken?.let { p.live.marked(it) } ?: 0
        CrateDeliveryProtocol.reconcile(store, row, amountOf(row.item), marked, { n -> item(n) }, 2L)
    }
    p.live.strip(null)
}

private fun owed(store: CratePendingStore): Int =
    store.rowsOf(OWNER, Status.PENDING).sumOf { amountOf(it.item) } + store.rowsOf(OWNER, Status.DELIVERING).sumOf { amountOf(it.item) }

fun main() {
    // ── Voucher re-signing (CR-P1-01) ────────────────────────────────────────────────────
    check("signed template is re-signed", PremiumVoucherRules.reissue(Verdict.SIGNED) == Reissue.RESIGN)
    check("accepted legacy template stays unsigned", PremiumVoucherRules.reissue(Verdict.LEGACY_ACCEPTED) == Reissue.UNSIGNED)
    check("forged template is refused", PremiumVoucherRules.reissue(Verdict.FORGED) == Reissue.REFUSE)
    check("refused legacy template is refused", PremiumVoucherRules.reissue(Verdict.LEGACY_REFUSED) == Reissue.REFUSE)
    check("legacy redemption unchanged (accepted)", PremiumVoucherRules.redeemable(PremiumVoucherRules.verdict(null, false, true)))
    check("legacy redemption unchanged (refused)", !PremiumVoucherRules.redeemable(PremiumVoucherRules.verdict(null, false, false)))

    val secret = ByteArray(32) { it.toByte() }
    for (kind in PremiumVoucherRules.Kind.entries) {
        val value = if (kind == PremiumVoucherRules.Kind.CREDIT) "250" else "pioneer"
        val templateUuid = UUID.randomUUID().toString()
        val templateSig = SurvivalFlight.sign(secret, PremiumVoucherRules.payload(kind, 1, templateUuid, value))
        check("${kind.id}: template verifies", SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, templateUuid, value), templateSig))
        // The bug: a fresh uuid with the template's signature never verifies.
        val winnerA = UUID.randomUUID().toString()
        val winnerB = UUID.randomUUID().toString()
        check("${kind.id}: old signature on a fresh uuid is invalid (the #1529 bug)",
            !SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, winnerA, value), templateSig))
        val sigA = SurvivalFlight.sign(secret, PremiumVoucherRules.payload(kind, 1, winnerA, value))
        val sigB = SurvivalFlight.sign(secret, PremiumVoucherRules.payload(kind, 1, winnerB, value))
        check("${kind.id}: re-signed copy A verifies", SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, winnerA, value), sigA))
        check("${kind.id}: re-signed copy B verifies", SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, winnerB, value), sigB))
        check("${kind.id}: two winners get different ids", winnerA != winnerB && sigA != sigB)
        check("${kind.id}: A's signature doesn't fit B", !SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, winnerB, value), sigA))
        check("${kind.id}: edited value is forged",
            !SurvivalFlight.verify(secret, PremiumVoucherRules.payload(kind, 1, winnerA, if (kind == PremiumVoucherRules.Kind.CREDIT) "99999" else "legend"), sigA))
        check("${kind.id}: other server's secret is forged",
            !SurvivalFlight.verify(ByteArray(32) { 7 }, PremiumVoucherRules.payload(kind, 1, winnerA, value), sigA))
    }
    // Fly Vouchers: same shape with their own payload.
    run {
        val t = UUID.randomUUID().toString()
        val sig = SurvivalFlight.sign(secret, SurvivalFlight.signedPayload(1, t, 3600))
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        check("fly: template signature doesn't cover a fresh uuid", !SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, a, 3600), sig))
        val sa = SurvivalFlight.sign(secret, SurvivalFlight.signedPayload(1, a, 3600))
        val sb = SurvivalFlight.sign(secret, SurvivalFlight.signedPayload(1, b, 3600))
        check("fly: re-signed winners verify independently",
            SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, a, 3600), sa) && SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, b, 3600), sb))
        check("fly: duration can't be edited", !SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, a, 7200), sa))
    }
    // Single use: every voucher kind reserves its id with INSERT OR IGNORE on a PRIMARY KEY
    // (CreditVoucherManager/RankVoucherManager/FlyVoucherManager, unchanged). Two fresh ids
    // reserve independently, the same id only once.
    withDb { db ->
        db.createTable("CREATE TABLE IF NOT EXISTS redemptions (voucher_uuid TEXT PRIMARY KEY)")
        val a = UUID.randomUUID().toString()
        val b = UUID.randomUUID().toString()
        check("winner A redeems", db.executeUpdate("INSERT OR IGNORE INTO redemptions (voucher_uuid) VALUES (?)", a) == 1)
        check("winner B redeems independently", db.executeUpdate("INSERT OR IGNORE INTO redemptions (voucher_uuid) VALUES (?)", b) == 1)
        check("a copy of A is rejected", db.executeUpdate("INSERT OR IGNORE INTO redemptions (voucher_uuid) VALUES (?)", a) == 0)
    }

    // ── Event-world routing (CR-P1-03) ──────────────────────────────────────────────────
    check("event world holds", CrateDeliveryRules.route(inEventWorld = true, modMode = false) == CrateDeliveryRules.Route.HOLD)
    check("event world wins over Mod Mode", CrateDeliveryRules.route(inEventWorld = true, modMode = true) == CrateDeliveryRules.Route.HOLD)
    check("Mod Mode goes to the backup", CrateDeliveryRules.route(inEventWorld = false, modMode = true) == CrateDeliveryRules.Route.BACKUP)
    check("normal world goes to the inventory", CrateDeliveryRules.route(inEventWorld = false, modMode = false) == CrateDeliveryRules.Route.INVENTORY)

    // ── Recovery rule ───────────────────────────────────────────────────────────────────
    check("no marks = not delivered", CrateDeliveryRecovery.resolve(5, 0) == CrateDeliveryRecovery.Outcome.NotDelivered)
    check("all marks = delivered", CrateDeliveryRecovery.resolve(5, 5) == CrateDeliveryRecovery.Outcome.Delivered)
    check("some marks = remainder", CrateDeliveryRecovery.resolve(5, 3) == CrateDeliveryRecovery.Outcome.Remaining(2))
    check("unreadable row = unknown", CrateDeliveryRecovery.resolve(null, 3) == CrateDeliveryRecovery.Outcome.Unknown)

    // ── Migration from the pre-#1529 table (34) ─────────────────────────────────────────
    withDb { db ->
        db.createTable("CREATE TABLE IF NOT EXISTS crate_pending_rewards (id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, item TEXT NOT NULL, label TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execute("INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES ('old1', ?, 'item:3', 'Diamonds x3', 10)", OWNER)
        db.execute("INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES ('old2', ?, 'item:1', 'Key x1', 20)", OWNER)
        val store = CratePendingStore(db)
        store.createTables()
        store.createTables() // idempotent
        val rows = store.rowsOf(OWNER, Status.PENDING)
        check("migration keeps every old row", rows.map { it.id } == listOf("old1", "old2"))
        check("migration keeps values", rows[0].item == "item:3" && rows[0].label == "Diamonds x3" && rows[0].createdAt == 10L)
        check("old rows are deliverable", store.beginDelivery("old1", OWNER, "t", 1))
        check("nothing was quarantined by the migration", store.quarantineReserved("x", 1).isEmpty())
    }

    // ── Store transitions (32, 33, 36) ─────────────────────────────────────────────────
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        check("insert", store.insert("a", OWNER, item(1), "A", 1, Status.RESERVED))
        check("duplicate transaction id refused", !store.insert("a", OWNER, item(9), "A", 1, Status.PENDING))
        check("duplicate didn't overwrite", store.row("a")?.item == item(1) && store.row("a")?.status == Status.RESERVED)
        check("RESERVED is never delivered", !store.beginDelivery("a", OWNER, "t", 1))
        check("confirm once", store.confirm("a", 1) && !store.confirm("a", 1))
        check("wrong owner can't claim", !store.beginDelivery("a", "someone-else", "t", 1))
        check("claim once", store.beginDelivery("a", OWNER, "t1", 1))
        check("second claim (double click / retry) refused", !store.beginDelivery("a", OWNER, "t2", 1))
        check("ack needs the attempt's token", !store.completeDelivery("a", "t2"))
        check("ack", store.completeDelivery("a", "t1") && store.row("a") == null)

        store.insert("r", OWNER, item(1), "R", 1, Status.RESERVED)
        check("startup quarantines RESERVED", store.quarantineReserved("crash", 2).map { it.id } == listOf("r"))
        check("REVIEW never delivered", !store.beginDelivery("r", OWNER, "t", 3))
        check("grant once", store.grant("r", "Staff", 3) && !store.grant("r", "Staff", 3))
        check("granted row is PENDING", store.row("r")?.status == Status.PENDING)
        check("granted row can't be voided afterwards", !store.void("r", "Staff", 3))
        check("granted reward delivers once", store.beginDelivery("r", OWNER, "g", 3) && store.completeDelivery("r", "g") && !store.beginDelivery("r", OWNER, "g2", 3))

        store.insert("v", OWNER, item(1), "V", 1, Status.RESERVED)
        store.quarantineReserved("crash", 2)
        check("void once", store.void("v", "Staff", 3) && !store.void("v", "Staff", 3))
        check("voided row kept as evidence", store.row("v")?.status == Status.VOIDED && store.row("v")?.note?.contains("voided by Staff") == true)
        check("voided can't be granted", !store.grant("v", "Staff", 3))
    }

    // ── Window A: key removal vs pending row, crash at every step (26-29, 35) ──────────
    for (crash in listOf(Crash.NONE, Crash.BEFORE_SAVE_WRITE, Crash.AFTER_SAVE_WRITE)) {
        withDb { db ->
            val store = CratePendingStore(db).also { it.createTables() }
            val p = Player()
            p.live.keys = 1; p.save()
            p.live.keys -= 1 // consumeOneKey (memory)
            try {
                CrateDeliveryProtocol.reserveThenCommit(store, listOf(CrateDeliveryProtocol.PendingInsert("k", OWNER, item(1), "Reward")), save = {
                    if (crash == Crash.BEFORE_SAVE_WRITE) throw SimulatedCrash()
                    p.save()
                    if (crash == Crash.AFTER_SAVE_WRITE) throw SimulatedCrash()
                }, now = { 1L })
            } catch (_: SimulatedCrash) {}
            if (crash != Crash.NONE) { p.crashAndRejoin(); store.quarantineReserved("crash", 2) }
            val status = store.row("k")?.status
            check("A/$crash: never key back AND reward deliverable", !(p.file.keys == 1 && status == Status.PENDING))
            check("A/$crash: never key gone AND no record", !(p.file.keys == 0 && status == null))
            when (crash) {
                Crash.NONE -> check("A/none: deliverable, key gone", status == Status.PENDING && p.file.keys == 0)
                else -> check("A/$crash: ambiguous -> REVIEW with evidence", status == Status.REVIEW && store.row("k")?.note == "crash")
            }
        }
    }
    // Crash before the RESERVED insert: nothing durable, key still in the file (26, 27).
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        val p = Player(); p.live.keys = 1; p.save(); p.live.keys -= 1
        p.crashAndRejoin()
        check("crash before reserve: key back, no row", p.file.keys == 1 && store.rowsOf(OWNER, Status.PENDING).isEmpty() && store.quarantineReserved("c", 1).isEmpty())
    }
    // A save that fails without a crash: rows go straight to REVIEW, never deliverable.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        val ok = CrateDeliveryProtocol.reserveThenCommit(store, listOf(CrateDeliveryProtocol.PendingInsert("f", OWNER, item(1), "R")),
            save = { throw IllegalStateException("io") }, now = { 1L })
        check("failed save -> not committed, REVIEW", !ok && store.row("f")?.status == Status.REVIEW)
    }
    // Mass open (38): several held rows commit together.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        val rows = (1..3).map { CrateDeliveryProtocol.PendingInsert("m$it", OWNER, item(1), "R$it") }
        try {
            CrateDeliveryProtocol.reserveThenCommit(store, rows, save = { throw SimulatedCrash() }, now = { 1L })
        } catch (_: SimulatedCrash) {}
        check("mass open crash: every held row to REVIEW", store.quarantineReserved("c", 2).size == 3)
        CrateDeliveryProtocol.reserveThenCommit(store, (4..5).map { CrateDeliveryProtocol.PendingInsert("m$it", OWNER, item(1), "R$it") }, save = {}, now = { 1L })
        check("mass open normal: held rows deliverable", store.rowsOf(OWNER, Status.PENDING).map { it.id } == listOf("m4", "m5"))
    }

    // ── Window B: delivery vs acknowledgement, crash at every step (30-32, 37) ─────────
    for (room in listOf(1000, 3)) {
        for (crash in Crash.entries) {
            withDb { db ->
                val store = CratePendingStore(db).also { it.createTables() }
                store.insert("d", OWNER, item(5), "Reward x5", 1, Status.PENDING)
                val p = Player(room)
                val result = deliver(store, p, "d", crash)
                if (crash != Crash.NONE) {
                    p.crashAndRejoin()
                    // A crash empties memory; the room the model had is the file's room again.
                    p.room = room - p.file.total()
                    rejoin(store, p)
                } else {
                    check("B/room=$room: result", result == if (room >= 5) CrateDeliveryProtocol.Result.Delivered else CrateDeliveryProtocol.Result.Partial(2))
                }
                val tag = "B/room=$room/$crash"
                check("$tag: delivered + still owed == won", p.live.total() + owed(store) == 5)
                check("$tag: nothing in review", store.rowsOf(OWNER, Status.REVIEW).isEmpty())
                check("$tag: no row left DELIVERING", store.rowsOf(OWNER, Status.DELIVERING).isEmpty())
                check("$tag: no marks left after rejoin/ack", p.live.items.none { it.token != null })
                // Retry until everything is delivered: still exactly 5, never more.
                p.room = 1000
                for (row in store.rowsOf(OWNER, Status.PENDING)) deliver(store, p, row.id, Crash.NONE)
                check("$tag: after retry exactly 5 delivered", p.live.total() == 5 && owed(store) == 0 && store.row("d") == null)
            }
        }
    }

    // Partial delivery, then a crash during the retry before its save: the old marks from the
    // first attempt (never saved without marks) must not count for the new attempt.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        store.insert("p", OWNER, item(5), "R", 1, Status.PENDING)
        val p = Player(3)
        check("partial first attempt", deliver(store, p, "p", Crash.NONE) == CrateDeliveryProtocol.Result.Partial(2))
        // The file still holds the first attempt's marked stack (marks are stripped in memory only).
        check("file keeps the first attempt's mark", p.file.items.any { it.token != null })
        p.room = 1000
        deliver(store, p, "p", Crash.BEFORE_SAVE_WRITE)
        p.crashAndRejoin()
        rejoin(store, p)
        check("retry crash: 3 delivered, 2 still owed", p.live.total() == 3 && owed(store) == 2)
        for (row in store.rowsOf(OWNER, Status.PENDING)) deliver(store, p, row.id, Crash.NONE)
        check("retry crash: then exactly 5", p.live.total() == 5 && owed(store) == 0)
    }

    // A save that fails while the server keeps running: the row stays DELIVERING with the marks
    // in memory, the quit saves them, the next join settles it as delivered (no second copy).
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        store.insert("u", OWNER, item(4), "R", 1, Status.PENDING)
        val p = Player()
        check("save failure -> unconfirmed", deliver(store, p, "u", Crash.NONE, saveFails = true) == CrateDeliveryProtocol.Result.Unconfirmed)
        check("unconfirmed row stays DELIVERING", store.row("u")?.status == Status.DELIVERING)
        check("a second attempt can't claim it", deliver(store, p, "u", Crash.NONE) == CrateDeliveryProtocol.Result.NotClaimed)
        p.save() // quit save
        p.crashAndRejoin()
        rejoin(store, p)
        check("unconfirmed settled as delivered once", p.live.total() == 4 && store.row("u") == null)
    }

    // Nothing fits: the row goes back to PENDING untouched and nothing is saved twice.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        store.insert("n", OWNER, item(2), "R", 1, Status.PENDING)
        val p = Player(0)
        check("nothing fits", deliver(store, p, "n", Crash.NONE) == CrateDeliveryProtocol.Result.NothingFit)
        check("row PENDING again with the same item", store.row("n")?.status == Status.PENDING && store.row("n")?.item == item(2))
    }

    // An unreadable DELIVERING row is never guessed: staff review.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        store.insert("x", OWNER, item(2), "R", 1, Status.PENDING)
        store.beginDelivery("x", OWNER, "tok", 1)
        val row = store.row("x")!!
        check("unknown -> review", CrateDeliveryProtocol.reconcile(store, row, null, 0, { "?" }, 2) == CrateDeliveryRecovery.Outcome.Unknown &&
            store.row("x")?.status == Status.REVIEW)
    }

    // Multiple pending rewards for one player (23): each delivered once, in order.
    withDb { db ->
        val store = CratePendingStore(db).also { it.createTables() }
        for (i in 1..4) store.insert("multi$i", OWNER, item(i), "R$i", i.toLong(), Status.PENDING)
        val p = Player()
        for (row in store.rowsOf(OWNER, Status.PENDING)) deliver(store, p, row.id, Crash.NONE)
        check("multiple rewards each once", p.live.total() == 1 + 2 + 3 + 4 && owed(store) == 0)
    }

    // ── Wiring ──────────────────────────────────────────────────────────────────────────
    val crateManager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    check("buildRewardItem copies vouchers through freshVoucherCopy", crateManager.contains("plugin.freshVoucherCopy(serialized)"))
    check("old id-only mint is gone", !crateManager.contains("mintFreshVoucherId"))
    check("crateProblem checks voucher templates", crateManager.contains("plugin.voucherTemplateProblem(it)"))
    check("no crate opens in the event world", crateManager.contains("Crates can't be opened in the event world"))
    check("delivery holds in the event world", crateManager.contains("CrateDeliveryRules.route(plugin.eventManager.isInEventWorld(player)"))
    check("join reconcile runs at LOWEST", Regex("EventPriority.LOWEST\\)\\s*\\n\\s*fun onPlayerJoinReconcile").containsMatchIn(crateManager))
    check("startup quarantines RESERVED", crateManager.contains("pendingStore.quarantineReserved("))
    check("no raw pending-row SQL left in CrateManager", !crateManager.contains("INSERT INTO crate_pending_rewards") && !crateManager.contains("DELETE FROM crate_pending_rewards"))
    val kit = File("src/main/kotlin/com/liam/joshymc/manager/KitManager.kt").readText()
    check("kits copy vouchers through freshVoucherCopy", kit.contains("plugin.freshVoucherCopy(item)"))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("recover permission is op", Regex("joshymc\\.crate\\.rewards\\.recover:\\s*\\n\\s*description:.*\\n\\s*default: op").containsMatchIn(pluginYml))
    val command = File("src/main/kotlin/com/liam/joshymc/command/CrateCommand.kt").readText()
    check("reconciliation needs confirm", command.contains("args.getOrNull(3).equals(\"confirm\""))
    check("reconciliation is audited", command.contains("CRATE_REWARD_REVIEW_GRANT") && command.contains("CRATE_REWARD_REVIEW_VOID"))

    println("Crate reward integrity checks passed ($checks checks).")
}
