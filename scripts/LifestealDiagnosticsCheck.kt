// Checks the read-only Lifesteal diagnostics (issue #1564): access rules, the Bukkit-free store
// against a real SQLite database whose tables are created by the systems' own stores (fresh temp
// directories, deleted afterwards), that a report never writes anything, missing/offline players,
// no ranked match, pending ranked restores, a closed or empty database, and the wiring. The Gradle
// build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-diagnostics-check.init.gradle.kts lifestealDiagnosticsCheck
//
// Exits non-zero on the first failed check. The in-game command itself (main thread / DB thread
// hand-off, staff ranks from LuckPerms) is not covered here; see docs/lifesteal-staff-recovery.md.

import com.liam.joshymc.communityevent.inventory.EventInventoryStore
import com.liam.joshymc.lifesteal.bank.BankStore
import com.liam.joshymc.lifesteal.bounty.BountyStore
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcStore
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnostics
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnostics.Tone
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnosticsStore
import com.liam.joshymc.lifesteal.diagnostics.LifestealDiagnosticsStore.Section
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.ranked.RankedStore
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

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val NOW = 1_800_000_000_000L
private val PLAYER = UUID.randomUUID()
private val REVIEWED = UUID.randomUUID()
private val UNKNOWN = UUID.randomUUID()

private object NoWallet : BankStore.Wallet, BountyStore.Wallet {
    override fun balance(uuid: UUID): Double = 0.0
    override fun withdraw(uuid: UUID, coins: Double): Boolean = false
    override fun deposit(uuid: UUID, coins: Double): Boolean = false
}

fun main() {
    access()
    val dirs = mutableListOf<File>()
    try {
        stored(dirs)
        emptyDatabase(dirs)
        closedDatabase(dirs)
    } finally {
        dirs.forEach { it.deleteRecursively() }
    }
    rendering()
    wiring()
    println("Lifesteal diagnostics checks passed: $checks")
}

private fun access() {
    check("no permission refused", LifestealDiagnostics.accessProblem(hasPermission = false, hosted = true) != null)
    check("no permission refused even where not hosted", LifestealDiagnostics.accessProblem(hasPermission = false, hosted = false) != null)
    check("not hosted refused", LifestealDiagnostics.accessProblem(hasPermission = true, hosted = false) != null)
    check("permission + hosted allowed", LifestealDiagnostics.accessProblem(hasPermission = true, hosted = true) == null)
    check("self lookup allowed whatever the staff rule says", LifestealDiagnostics.targetProblem(self = true, protection = "higher rank") == null)
    check("higher staff target refused", LifestealDiagnostics.targetProblem(self = false, protection = "higher rank") == "higher rank")
    check("ordinary target allowed", LifestealDiagnostics.targetProblem(self = false, protection = null) == null)
}

private fun openDb(dirs: MutableList<File>): DatabaseManager {
    val dir = Files.createTempDirectory("lifesteal-diag-check").toFile()
    dirs += dir
    return DatabaseManager(dir, quietLogger()).also { it.start() }
}

private fun createAll(db: DatabaseManager) {
    HeartStore(db).createTables()
    RankedStore(db).createTables()
    CombatNpcStore(db).createTables()
    EventInventoryStore(db).createTables()
    BankStore(db, NoWallet).createTables()
    BountyStore(db, NoWallet).createTables()
}

/** Every row of every table, so a report that wrote anything at all is caught. */
private fun fingerprint(db: DatabaseManager): String {
    val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name") { it.getString(1) }
    return tables.joinToString("\n") { table ->
        table + ":" + db.query("SELECT * FROM \"$table\"") { rs ->
            (1..rs.metaData.columnCount).joinToString("|") { rs.getString(it) ?: "∅" }
        }.sorted().joinToString(";")
    }
}

private fun changes(db: DatabaseManager): Long = db.queryFirst("SELECT total_changes()") { it.getLong(1) }!!

private fun seed(db: DatabaseManager) {
    val p = PLAYER.toString()
    db.execute("INSERT INTO lifesteal_hearts (uuid, hearts, version, created_at, updated_at, last_transaction) VALUES (?, 7, 3, ?, ?, 'tx-abcdef12')", p, NOW - 100_000, NOW - 1_000)
    db.execute("INSERT INTO lifesteal_heart_audit (transaction_id, uuid, operation, previous_hearts, new_hearts, actor, reason, created_at) VALUES ('t1', ?, 'PVP_LOSS', 8, 7, NULL, 'killed', ?)", p, NOW - 2_000)
    db.execute("INSERT INTO lifesteal_heart_items (serial, status, source, issued_to, issue_transaction, issued_at) VALUES ('s1', 'ISSUED', 'WITHDRAW', ?, 't2', ?)", p, NOW - 3_000)
    db.execute("INSERT INTO lifesteal_heart_items (serial, status, source, issued_to, issue_transaction, issued_at) VALUES ('s2', 'REDEEMED', 'WITHDRAW', ?, 't3', ?)", p, NOW - 3_000)
    db.execute(
        "INSERT INTO lifesteal_ranked_matches (match_id, season_id, arena_id, team_a_id, team_a_name, team_b_id, team_b_name, size, kind, state, created_by, created_at) " +
            "VALUES ('m-1', 'season-1', 'arena', 'ta', 'A', 'tb', 'B', 2, 'PUBLIC', 'CANCELLED', 'staff', ?)",
        NOW - 60_000,
    )
    db.execute(
        "INSERT INTO lifesteal_ranked_snapshots (snapshot_id, match_id, uuid, contents, slot_count, held_slot, status, created_at) VALUES ('snap-pending-1', 'm-1', ?, '', 41, 0, 'PENDING', ?)",
        p, NOW - 50_000,
    )
    db.execute(
        "INSERT INTO lifesteal_ranked_snapshots (snapshot_id, match_id, uuid, contents, slot_count, held_slot, status, created_at, review_reason) VALUES ('snap-review-1', 'm-1', ?, '', 41, 0, 'REVIEW', ?, 'crash')",
        REVIEWED.toString(), NOW - 50_000,
    )
    db.execute(
        "INSERT INTO lifesteal_combat_npcs (id, owner, owner_name, state, created_at, expires_at, world, x, y, z, yaw, pitch, health, max_health_base, absorption, fire_ticks, air, " +
            "inventory, held_slot, xp_level, xp_progress, xp_total, effects, effects_at, updated_at) VALUES ('npc-1', ?, 'Tester', 'ACTIVE', ?, ?, 'lifesteal', 10.5, 64, -3.2, 0, 0, 20, 20, 0, 0, 300, " +
            "'not even base64', 0, 0, 0, 0, '', ?, ?)",
        p, NOW - 10_000, NOW + 20_000, NOW, NOW,
    )
    db.execute("INSERT INTO event_inventory_snapshots (uuid, status, source, created_at, updated_at, detail) VALUES (?, 'REVIEW', 'ENTRY', ?, ?, 'ambiguous')", p, NOW - 5_000, NOW - 5_000)
    db.execute("INSERT INTO lifesteal_bank_accounts (uuid, balance_cents, created_at, updated_at) VALUES (?, 123456, ?, ?)", p, NOW, NOW)
    db.execute("INSERT INTO lifesteal_bank_ledger (tx_id, uuid, at, type, amount_cents, state, source) VALUES ('b1', ?, ?, 'DEPOSIT', 500, 'FAILED', 'player')", p, NOW)
}

private fun stored(dirs: MutableList<File>) {
    val db = openDb(dirs)
    createAll(db)
    seed(db)
    val store = LifestealDiagnosticsStore(db)
    val before = fingerprint(db)
    val changesBefore = changes(db)

    val report = store.player(PLAYER)
    check("database answered", report.databaseProblem == null)
    val hearts = (report.hearts as? Section.Ok)?.value
    check("stored hearts read", hearts?.hearts == 7L && hearts.version == 3L && hearts.lastTransaction == "tx-abcdef12")
    check("heart history read (newest first, bounded)", (report.heartAudit as? Section.Ok)?.value?.map { it.operation } == listOf("PVP_LOSS"))
    check("only ISSUED heart items counted", (report.outstandingHeartItems as? Section.Ok)?.value == 1)
    check("no active ranked match: no open roster row", report.rankedOpen == Section.Ok(null))
    val snaps = (report.rankedSnapshots as? Section.Ok)?.value
    check("pending ranked restore found for this player only", snaps?.map { it.snapshotId to it.status } == listOf("snap-pending-1" to "PENDING"))
    val npcs = (report.combatNpcs as? Section.Ok)?.value
    check("open Combat NPC session found without decoding its inventory", npcs?.singleOrNull()?.let { it.id == "npc-1" && it.state == "ACTIVE" && it.world == "lifesteal" } == true)
    check("unsettled event inventory found", (report.eventInventory as? Section.Ok)?.value?.singleOrNull()?.status == "REVIEW")
    val bank = (report.bank as? Section.Ok)?.value
    check("bank balance + failed unreviewed", bank?.balanceCents == 123456L && bank.failedUnreviewed == 1)
    val bounty = (report.bounty as? Section.Ok)?.value
    check("no bounty data = nothing pending", bounty != null && bounty.activeOnPlayerCents == null && bounty.pendingPayouts.isEmpty() && bounty.pendingRefunds == 0)

    val missing = store.player(UNKNOWN)
    check("missing player: no error", missing.databaseProblem == null)
    check("missing player: no heart record", missing.hearts == Section.Ok(null))
    check("missing player: nothing waiting", missing.rankedSnapshots == Section.Ok(emptyList<Any>()) && missing.combatNpcs == Section.Ok(emptyList<Any>()) &&
        missing.eventInventory == Section.Ok(emptyList<Any>()) && missing.bank == Section.Ok(null) && missing.heartAudit == Section.Ok(emptyList<Any>()))

    val reviewed = store.player(REVIEWED)
    check("REVIEW snapshot shown with its reason", (reviewed.rankedSnapshots as? Section.Ok)?.value?.singleOrNull()?.let { it.status == "REVIEW" && it.reviewReason == "crash" } == true)

    val health = store.health()
    check("health: database answering", health.databaseProblem == null && health.latencyMillis != null)
    check("health: ranked snapshots by status", health.rankedSnapshots == Section.Ok(mapOf("PENDING" to 1, "REVIEW" to 1)))
    check("health: combat NPCs open", health.combatNpcsOpen == Section.Ok(mapOf("ACTIVE" to 1)))
    check("health: event inventories", health.eventInventory == Section.Ok(mapOf("REVIEW" to 1)))
    check("health: bounty + bank counts", health.bountyPayoutsPending == Section.Ok(0) && health.bountyRefundsPending == Section.Ok(0) && health.bankFailedUnreviewed == Section.Ok(1))

    check("read-only: no row changed by any report", changes(db) == changesBefore)
    check("read-only: every table identical", fingerprint(db) == before)

    // The player view's queries use the tables' indexes, never a scan of a whole table.
    val plans = listOf(
        "SELECT COUNT(*) FROM lifesteal_heart_items WHERE issued_to = ? AND status = 'ISSUED'",
        "SELECT snapshot_id FROM lifesteal_ranked_snapshots WHERE uuid = ? AND status IN ('PENDING', 'RESTORING', 'REVIEW')",
        "SELECT id FROM lifesteal_combat_npcs WHERE owner = ? AND state <> 'COMPLETE'",
        "SELECT operation FROM lifesteal_heart_audit WHERE uuid = ? ORDER BY created_at DESC LIMIT 5",
        "SELECT match_id FROM lifesteal_ranked_participants WHERE uuid = ? AND open = 1",
        "SELECT COUNT(*) FROM lifesteal_bank_ledger WHERE uuid = ? AND state = 'FAILED' AND reviewed_at IS NULL",
    )
    for (sql in plans) {
        val plan = db.query("EXPLAIN QUERY PLAN $sql", PLAYER.toString()) { it.getString("detail") }.joinToString(" / ")
        check("indexed lookup ($plan) for: $sql", "USING" in plan && !Regex("^SCAN [a-z_]+$").matches(plan.trim()))
    }
    db.shutdown()
}

private fun emptyDatabase(dirs: MutableList<File>) {
    val db = openDb(dirs)
    val store = LifestealDiagnosticsStore(db)
    val r = store.player(PLAYER)
    check("no tables: database fine", r.databaseProblem == null)
    check("no tables: every section NotCreated", listOf(r.hearts, r.heartAudit, r.outstandingHeartItems, r.rankedOpen, r.rankedSnapshots, r.combatNpcs, r.eventInventory, r.bank, r.bounty).all { it == Section.NotCreated })
    val h = store.health()
    check("no tables: health sections NotCreated", h.databaseProblem == null && h.rankedSnapshots == Section.NotCreated && h.bankFailedUnreviewed == Section.NotCreated)
    // A broken table (a column missing) only fails its own section.
    db.execute("CREATE TABLE lifesteal_hearts (uuid TEXT PRIMARY KEY)")
    val broken = store.player(PLAYER)
    check("broken table: only its section failed", broken.hearts is Section.Failed && broken.rankedSnapshots == Section.NotCreated)
    db.shutdown()
}

private fun closedDatabase(dirs: MutableList<File>) {
    val db = openDb(dirs)
    createAll(db)
    db.shutdown()
    val store = LifestealDiagnosticsStore(db)
    val r = try { store.player(PLAYER) } catch (e: Exception) { null }
    check("closed database: player report never throws", r != null)
    check("closed database: reported as unavailable", r!!.databaseProblem != null && r.hearts is Section.Failed && r.bounty is Section.Failed)
    val h = try { store.health() } catch (e: Exception) { null }
    check("closed database: health never throws and says so", h != null && h.databaseProblem != null && h.latencyMillis == null)
}

private fun live(online: Boolean, match: LifestealDiagnostics.MatchView? = null) = LifestealDiagnostics.Live(
    uuid = PLAYER, name = "Tester", online = online, bedrock = false,
    world = if (online) "lifesteal" else null, inLifestealWorld = if (online) true else null,
    loadedHearts = if (online) 7 else null, combatTagMillisLeft = null, rankedMatch = match,
    spectatingMatch = null, pendingRestoreFlag = false, progressionPhase = "Normal gameplay",
)

private fun rendering() {
    val offline = LifestealDiagnostics.playerLines(live(online = false), null, NOW, "database closed")
    check("offline player shown as offline", offline.any { "Offline" in it.text })
    check("no ranked match line", offline.any { it.text.contains("not held by a match") && it.tone == Tone.OK })
    check("database unavailable shown, nothing invented", offline.last().let { it.tone == Tone.BAD && "database closed" in it.text } && offline.none { "Hearts:" in it.text && "stored" in it.text })

    val held = LifestealDiagnostics.playerLines(live(true, LifestealDiagnostics.MatchView("m-9", "ACTIVE", "PUBLIC", false)), null, NOW)
    check("held by a match shows id and state", held.any { "m-9" in it.text && "ACTIVE" in it.text })

    val failed: Section<Nothing> = Section.Failed("disk I/O error")
    val report = LifestealDiagnosticsStore.PlayerReport(null, failed, Section.Ok(emptyList()), Section.Ok(0), Section.Ok(null),
        Section.Ok(listOf(LifestealDiagnosticsStore.RankedSnapshot("snap-pending-1", "m-1", "PENDING", NOW - 1_000, null, null))),
        Section.NotCreated, Section.Ok(emptyList()), Section.Ok(null), Section.Ok(LifestealDiagnosticsStore.Bounty(null, emptyList(), 0)))
    val lines = LifestealDiagnostics.playerLines(live(online = false), report, NOW)
    check("failed section shown as couldn't be read", lines.any { it.tone == Tone.BAD && "Hearts: couldn't be read (disk I/O error)" in it.text })
    check("other sections still shown after a failure", lines.any { "Ranked inventory PENDING" in it.text })
    check("pending restore explains what happens next", lines.any { "restores on join/respawn" in it.text })
    check("not set up sections are informational", lines.any { "Combat NPC: not set up" in it.text && it.tone == Tone.INFO })
    check("missing heart record", LifestealDiagnostics.playerLines(live(false), report.copy(hearts = Section.Ok(null)), NOW).any { "no record" in it.text })
    val unreadable = report.copy(hearts = Section.Ok(LifestealDiagnosticsStore.HeartRow(null, 1, NOW, NOW, null)))
    check("unreadable hearts flagged", LifestealDiagnostics.playerLines(live(false), unreadable, NOW).any { "UNREADABLE" in it.text && it.tone == Tone.BAD })

    val healthLive = LifestealDiagnostics.HealthLive(null, true, emptyList(), true, 0, true, true, "loaded", "READY", "Normal gameplay", null, databaseOpen = false)
    val closed = LifestealDiagnostics.healthLines(healthLive, null, "closed")
    check("health: closed database reported", closed.any { "Database: closed" in it.text && it.tone == Tone.BAD })
    check("health: service not started is unknown, not active", closed.any { "Hearts: unknown" in it.text })
    check("coins formatting", LifestealDiagnostics.coins(123456) == "$1,234.56")
}

private fun wiring() {
    val store = source("lifesteal/diagnostics/LifestealDiagnosticsStore.kt")
    for (word in listOf("INSERT", "UPDATE ", "DELETE", "ALTER", "DROP", "REPLACE", "execute(", "executeUpdate", ".transaction", "transaction {", "createTable", "insertReturningId", "PRAGMA integrity_check", "VACUUM")) {
        check("store is read-only (no $word)", word !in store)
    }
    val command = source("lifesteal/diagnostics/LifestealDiagnosticsCommand.kt")
    val executeBody = command.substringAfter("fun execute(").substringBefore("fun complete(")
    check("permission checked first", executeBody.indexOf("accessProblem") in 0 until executeBody.indexOf("when (args"))
    check("stored state read on the database thread", "databaseManager.async(\"lifesteal diagnostics player\")" in command && "databaseManager.async(\"lifesteal diagnostics health\")" in command)
    check("reply goes back on the main thread", "runTask(plugin" in command)
    check("no recurring task", "runTaskTimer" !in command && "scheduleSyncRepeating" !in command)
    check("no stored player references", !Regex("""(val|var) \w+ *= *(mutableMapOf|HashMap|mutableSetOf|HashSet)""").containsMatchIn(command.substringBefore("fun execute(")))
    check("staff protection for other players", "protectionProblem(sender, target)" in command && "targetProblem(self, protection)" in command)
    check("lookups audited", "AdminAudit.log(plugin, sender, \"LIFESTEAL_DIAG_PLAYER\"" in command)
    for (call in listOf("adminChange", "voidItem", "grantItems", "restore(", "cancel", "setOverride", "scheduleLaunch", "activateLaunch", "withdraw", "deposit", "transferPvp", "finalize", "retry")) {
        check("command never calls $call", call !in command)
    }
    val router = source("command/ServerTransferCommand.kt")
    check("routed under /lifesteal admin diag", "args.getOrNull(1)?.lowercase() == \"diag\"" in router && "diagnostics.execute(sender, args.drop(2))" in router)
    check("diag routed before the ranked admin tools", router.indexOf("== \"diag\"") < router.indexOf("ranked.handles(args.getOrNull(1)"))

    val yml = File("src/main/resources/plugin.yml").readText()
    val block = yml.substringAfter("  joshymc.lifesteal.diagnostics:\n").substringBefore("\n  joshymc.")
    check("permission declared op-only", "default: op" in block)
    check("permission grants nothing else", "children" !in block)
    check("no other permission grants diagnostics", "joshymc.lifesteal.diagnostics: true" !in yml)
}
