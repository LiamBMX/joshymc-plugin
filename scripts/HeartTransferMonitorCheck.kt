// Checks heart transfer monitoring (issue #1508, audit finding C-2): the Bukkit-free signal rules
// and settings, the review log against a real SQLite database (fresh temp directories, deleted
// afterwards) in front of the real HeartStore, including restarts, duplicates, rejected transfers
// and failures, and the wiring/privacy rules in the plugin source. The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/heart-transfer-monitor-check.init.gradle.kts heartTransferMonitorCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (notices in chat, /lifesteal
// hearts monitor) is NOT covered here: see docs/lifesteal-hearts.md, "Heart transfer monitoring".

import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.Decision
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.hearts.HeartStore.PvpResult
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorConfig
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorRules
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorRules.Signal
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorRules.Source
import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorSettings
import com.liam.joshymc.lifesteal.hearts.monitor.HeartTransferMonitorStore
import com.liam.joshymc.lifesteal.hearts.monitor.HeartTransferMonitorStore.Outcome
import com.liam.joshymc.lifesteal.hearts.monitor.HeartTransferMonitorStore.Status
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.SqliteDatabase
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
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

private fun <T> withDb(dir: File, block: (DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
    }
}

private fun <T> withTemp(block: (File) -> T): T {
    val dir = Files.createTempDirectory("joshymc-heart-monitor").toFile()
    try {
        return block(dir)
    } finally {
        dir.deleteRecursively()
    }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val LIMITS = HeartRules.Limits(starting = 10, minimum = 1, maximum = 20)
private const val COOLDOWN = 30 * 60_000L
private const val T0 = 1_800_000_000_000L
private const val MIN = 60_000L
private val S = HeartMonitorSettings.DEFAULTS

private fun connections(db: SqliteDatabase, vararg rows: Pair<UUID, String>) {
    db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT NOT NULL, connection_hash TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (uuid, connection_hash))")
    for ((uuid, hash) in rows) db.execute("INSERT OR IGNORE INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, ?, ?)", uuid.toString(), hash, T0)
}

/** Hearts + monitor tables, the way the plugin creates them (heart store first, then the monitor). */
private fun stores(db: SqliteDatabase): Pair<HeartStore, HeartTransferMonitorStore> {
    val hearts = HeartStore(db).also { it.createTables() }
    val monitor = HeartTransferMonitorStore(db).also { it.createTables() }
    return hearts to monitor
}

private fun players(hearts: HeartStore, n: Int, start: Int = 10): List<UUID> = List(n) { UUID.randomUUID().also { u -> hearts.ensure(u, start, T0) } }

private fun request(killer: UUID, victim: UUID, at: Long, key: String = "pvp:test:$victim:$at") =
    HeartStore.PvpRequest(key, killer, victim, at, COOLDOWN, LIMITS)

/** What LifestealHeartManager.monitorTransfer builds from a committed result. */
private fun transferOf(result: PvpResult, req: HeartStore.PvpRequest, source: Source = Source.KILL, matchId: String? = null): HeartMonitorRules.Transfer {
    val done = result as PvpResult.Done
    return HeartMonitorRules.Transfer(done.transactionId, req.dedupeKey, source, req.killer, req.victim, req.now, done.killerBefore, done.killerAfter, done.victimBefore, done.victimAfter, matchId)
}

/** A transfer through the real heart store, then the monitor's evaluation of it (when it moved a heart). */
private fun kill(hearts: HeartStore, monitor: HeartTransferMonitorStore, killer: UUID, victim: UUID, at: Long, settings: HeartMonitorSettings = S, source: Source = Source.KILL): Pair<PvpResult, Outcome?> {
    val req = request(killer, victim, at)
    val result = hearts.transferPvp(req)
    val outcome = if (result is PvpResult.Done && result.decision is Decision.Transfer) monitor.evaluate(transferOf(result, req, source), settings, at + 1) else null
    return result to outcome
}

fun main() {
    settings()
    rules()
    store()
    restartAndDuplicates()
    rejectedAndFailures()
    efficiency()
    wiring()
    println("OK: $checks heart transfer monitoring checks passed.")
}

// ── Settings ───────────────────────────────────────────────

private fun bundled(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    @Suppress("UNCHECKED_CAST")
    return Yaml().load<Map<String, Any?>>(text)
}

private fun parse(section: Map<String, Any?>?) = HeartMonitorConfig.parse { path ->
    if (path.isEmpty()) section else section?.get(path)
}

private fun settings() {
    @Suppress("UNCHECKED_CAST")
    val section = bundled()[HeartMonitorConfig.BASE] as Map<String, Any?>?
    check("settings: bundled lifesteal.yml has the section", section != null)
    val parsed = parse(section)
    check("settings: bundled section parses without problems", parsed.settings != null && parsed.problems.isEmpty())
    check("settings: bundled values are the code defaults", parsed.settings == HeartMonitorSettings.DEFAULTS)
    check("settings: conservative defaults (on, logging, 24h, B4 C3 D3, notify throttled, 180d)",
        S.enabled && S.windowHours == 24 && S.sharedConnection && S.repeatedTransfers == 4 && S.oneWayTransfers == 3 &&
            S.associatedAccounts == 3 && S.notifyStaff && S.notifyCooldownMinutes == 60 && S.retentionDays == 180)
    val missing = parse(null)
    check("settings: missing section = defaults with a warning", missing.settings == S && missing.problems.size == 1 && !missing.problems[0].severe)
    val bad = parse(section!! + ("window-hours" to 0))
    check("settings: out-of-range window turns monitoring off", bad.settings == null && bad.problems.any { it.severe })
    val notBool = parse(section + ("enabled" to "yes"))
    check("settings: non-boolean switch turns monitoring off", notBool.settings == null)
    val partial = parse(section - "retention-days")
    check("settings: a missing key uses its default", partial.settings?.retentionDays == 180 && partial.problems.none { it.severe })
    check("settings: thresholds may be 0 (off)", parse(section + ("repeated-transfers" to 0)).settings?.repeatedTransfers == 0)
    // Issue rule: the monitoring section never changes the heart limits or the cooldown.
    val lifesteal = File("src/main/resources/lifesteal.yml").readText()
    check("settings: heart limits unchanged in lifesteal.yml (10 / 1..20)",
        "  starting: 10\n" in lifesteal && "  maximum: 20\n" in lifesteal && "  minimum: 1\n" in lifesteal)
    check("settings: same-victim cooldown still 30", Regex("same-victim-cooldown-minutes: 30\\b").containsMatchIn(lifesteal))
}

// ── Rules ──────────────────────────────────────────────────

private fun counts(shared: Boolean = false, forward: Int = 1, reverse: Int = 0, associated: Int = 0) =
    HeartMonitorRules.Counts(shared, forward, reverse, associated)

private fun rules() {
    check("rules: one transfer between unrelated accounts raises nothing", HeartMonitorRules.evaluate(counts(), S).isEmpty())
    check("rules: three one-way transfers between unrelated accounts raise nothing (C needs another signal)",
        HeartMonitorRules.evaluate(counts(forward = 3), S).isEmpty())
    check("rules: A shared connection", HeartMonitorRules.evaluate(counts(shared = true), S) == listOf(Signal.SHARED_CONNECTION))
    check("rules: B repeated counts both directions", HeartMonitorRules.evaluate(counts(forward = 2, reverse = 2), S) == listOf(Signal.REPEATED_TRANSFERS))
    check("rules: B below threshold", HeartMonitorRules.evaluate(counts(forward = 2, reverse = 1), S).isEmpty())
    check("rules: B + C one-way", HeartMonitorRules.evaluate(counts(forward = 4), S) == listOf(Signal.REPEATED_TRANSFERS, Signal.ONE_WAY))
    check("rules: C needs no heart back", Signal.ONE_WAY !in HeartMonitorRules.evaluate(counts(forward = 4, reverse = 1), S))
    check("rules: A + C", HeartMonitorRules.evaluate(counts(shared = true, forward = 3), S) == listOf(Signal.SHARED_CONNECTION, Signal.ONE_WAY))
    check("rules: D associated accounts", HeartMonitorRules.evaluate(counts(associated = 3), S) == listOf(Signal.ASSOCIATED_ACCOUNTS))
    check("rules: D below threshold", HeartMonitorRules.evaluate(counts(associated = 2), S).isEmpty())
    check("rules: everything", HeartMonitorRules.evaluate(counts(true, 5, 0, 4), S) ==
        listOf(Signal.SHARED_CONNECTION, Signal.REPEATED_TRANSFERS, Signal.ONE_WAY, Signal.ASSOCIATED_ACCOUNTS))
    check("rules: disabled raises nothing", HeartMonitorRules.evaluate(counts(true, 9, 0, 9), S.copy(enabled = false)).isEmpty())
    check("rules: shared-connection off also turns D off", HeartMonitorRules.evaluate(counts(true, 1, 0, 9), S.copy(sharedConnection = false)).isEmpty())
    check("rules: threshold 0 = off", HeartMonitorRules.evaluate(counts(forward = 50), S.copy(repeatedTransfers = 0)).isEmpty())
    check("rules: reason text", HeartMonitorRules.reason(listOf(Signal.SHARED_CONNECTION, Signal.REPEATED_TRANSFERS)) == "Shared connection + Repeated transfers")
    check("rules: signal codes round-trip", Signal.parse(Signal.entries.joinToString(",") { it.name }) == Signal.entries.toList())
    val a = UUID.randomUUID()
    val b = UUID.randomUUID()
    check("rules: pair key is unordered", HeartMonitorRules.pairKey(a, b) == HeartMonitorRules.pairKey(b, a))
    check("rules: forfeit match id", HeartMonitorRules.forfeitMatchId("rankedforfeit:m-123:$a") == "m-123")
    check("rules: no match id elsewhere", HeartMonitorRules.forfeitMatchId("pvp:boot:$a:5") == null)
    check("rules: connection-evidence signals", HeartMonitorRules.usesConnectionEvidence(Signal.SHARED_CONNECTION) &&
        HeartMonitorRules.usesConnectionEvidence(Signal.ASSOCIATED_ACCOUNTS) && !HeartMonitorRules.usesConnectionEvidence(Signal.REPEATED_TRANSFERS))
}

// ── Store ──────────────────────────────────────────────────

private fun store() = withTemp { dir ->
    withDb(dir) { db ->
        val (hearts, monitor) = stores(db)

        // 1. Normal PvP transfer still works, unrelated accounts aren't flagged for it.
        val (k, v) = players(hearts, 2)
        val (r1, o1) = kill(hearts, monitor, k, v, T0)
        check("store: normal PvP transfer still moves exactly one heart", r1 is PvpResult.Done && r1.decision is Decision.Transfer &&
            hearts.load(k)!!.hearts == 11 && hearts.load(v)!!.hearts == 9)
        check("store: unrelated accounts with one transfer are not flagged", o1 == Outcome.Clean && monitor.count() == 0)

        // 4. Shared-connection evidence creates a flag with everything staff need.
        val (main, alt) = players(hearts, 2)
        connections(db, main to "h-home", alt to "h-home")
        monitor.createTables() // the plugin's refresh adds the hash index once AltManager's table exists
        val (r2, o2) = kill(hearts, monitor, main, alt, T0 + MIN)
        val flag = (o2 as? Outcome.Flagged)?.flag
        check("store: shared connection creates a flag", flag != null && flag.signals == listOf(Signal.SHARED_CONNECTION))
        check("store: flag records the transaction, players, hearts, source and status",
            flag != null && flag.transactionId == (r2 as PvpResult.Done).transactionId && flag.killer == main && flag.victim == alt &&
                flag.killerBefore == 10 && flag.killerAfter == 11 && flag.victimBefore == 10 && flag.victimAfter == 9 &&
                flag.source == Source.KILL && flag.status == Status.OPEN && flag.forward == 1 && flag.reverse == 0 && flag.windowHours == 24)
        check("store: first flag of a pair notifies staff", (o2 as Outcome.Flagged).notify && flag!!.notified)
        val columns = db.query("PRAGMA table_info(lifesteal_heart_monitor_flags)") { it.getString("name") }
        check("store: no connection hash or address is stored", columns.none { "hash" in it || "ip" == it || "address" in it } &&
            db.query("SELECT * FROM lifesteal_heart_monitor_flags") { rs -> (1..rs.metaData.columnCount).map { rs.getString(it) } }
                .flatten().none { it == "h-home" })

        // Notifications are throttled per pair, every flag is still recorded.
        val (_, o3) = kill(hearts, monitor, alt, main, T0 + 40 * MIN)
        check("store: reverse transfer of the same pair is flagged again", o3 is Outcome.Flagged)
        check("store: but staff aren't told again within the cooldown", (o3 as Outcome.Flagged).notify.not() && !o3.flag.notified)
        val (_, o4) = kill(hearts, monitor, main, alt, T0 + 120 * MIN)
        check("store: after the notify cooldown staff are told again", (o4 as Outcome.Flagged).notify)
        val quiet = S.copy(notifyStaff = false)
        val (c, d) = players(hearts, 2)
        connections(db, c to "h-cafe", d to "h-cafe")
        val (_, o5) = kill(hearts, monitor, c, d, T0, quiet)
        check("store: notify-staff false records without notifying", o5 is Outcome.Flagged && !o5.notify)

        // 6 + 7. Repeated transfers (respecting the 30-minute cooldown) and one-way farming.
        val (strong, weak) = players(hearts, 2)
        val outcomes = (0 until 4).map { kill(hearts, monitor, strong, weak, T0 + it * 31 * MIN) }
        check("store: every transfer respected the cooldown and moved a heart", outcomes.all { (r, _) -> r is PvpResult.Done && r.decision is Decision.Transfer })
        check("store: below the threshold nothing is flagged (3 one-way alone isn't enough)", outcomes.take(3).all { it.second == Outcome.Clean })
        val fourth = outcomes[3].second as? Outcome.Flagged
        check("store: 4th transfer in the window = repeated + one-way", fourth != null && fourth.flag.signals == listOf(Signal.REPEATED_TRANSFERS, Signal.ONE_WAY) &&
            fourth.flag.forward == 4 && fourth.flag.reverse == 0)
        val (x, y) = players(hearts, 2)
        val trade = listOf(x to y, y to x, x to y, y to x).mapIndexed { i, (kk, vv) -> kill(hearts, monitor, kk, vv, T0 + i * 31 * MIN).second }
        check("store: exchanging hearts back and forth = repeated, not one-way",
            (trade[3] as? Outcome.Flagged)?.flag?.signals == listOf(Signal.REPEATED_TRANSFERS))
        val (p, q) = players(hearts, 2)
        val spread = (0 until 4).map { kill(hearts, monitor, p, q, T0 + it * 25 * 3_600_000L).second }
        check("store: the same transfers spread over days stay unflagged (window)", spread.all { it == Outcome.Clean })

        // 8. Associated accounts: three alts on one connection feeding one recipient.
        val recipient = players(hearts, 1)[0]
        val alts = players(hearts, 3)
        connections(db, alts[0] to "h-dorm", alts[1] to "h-dorm", alts[2] to "h-dorm")
        val feeding = alts.mapIndexed { i, a -> kill(hearts, monitor, recipient, a, T0 + i * MIN).second }
        check("store: two associated donors aren't enough", feeding[0] == Outcome.Clean && feeding[1] == Outcome.Clean)
        val third = feeding[2] as? Outcome.Flagged
        check("store: third associated donor flags the recipient", third != null && Signal.ASSOCIATED_ACCOUNTS in third.flag.signals &&
            third.flag.associatedAccounts == 3 && Signal.SHARED_CONNECTION !in third.flag.signals)
        val lone = players(hearts, 3)
        val solo = players(hearts, 1)[0]
        connections(db, lone[0] to "h-a", lone[1] to "h-b", lone[2] to "h-c")
        check("store: donors on different connections are not associated",
            lone.mapIndexed { i, a -> kill(hearts, monitor, solo, a, T0 + i * MIN).second }.all { it == Outcome.Clean })

        // 3. Ranked forfeits and every other source are recorded with their source and match.
        val (rk, rv) = players(hearts, 2)
        connections(db, rk to "h-lan", rv to "h-lan")
        val key = "rankedforfeit:m-77:$rv"
        val forfeit = HeartStore.PvpRequest(key, rk, rv, T0, COOLDOWN, LIMITS, note = "ranked forfeit")
        val fr = hearts.transferPvp(forfeit)
        check("store: ranked forfeit transfer still works", fr is PvpResult.Done && fr.decision is Decision.Transfer && hearts.load(rv)!!.hearts == 9)
        val fo = monitor.evaluate(transferOf(fr, forfeit, Source.RANKED_FORFEIT, HeartMonitorRules.forfeitMatchId(key)), S, T0 + 1)
        check("store: ranked forfeit is monitored with its match id", fo is Outcome.Flagged && fo.flag.source == Source.RANKED_FORFEIT &&
            fo.flag.matchId == "m-77" && fo.flag.dedupeKey == key)
        val (rk2, rv2) = players(hearts, 2)
        connections(db, rk2 to "h-lan2", rv2 to "h-lan2")
        val (rr, ro) = kill(hearts, monitor, rk2, rv2, T0, source = Source.RANKED_KILL)
        check("store: ranked kill transfer still works and is monitored", rr is PvpResult.Done && rr.decision is Decision.Transfer &&
            (ro as? Outcome.Flagged)?.flag?.source == Source.RANKED_KILL)

        // 17. Disabled monitoring: transfers unchanged, nothing written.
        val (dk, dv) = players(hearts, 2)
        connections(db, dk to "h-off", dv to "h-off")
        val before = monitor.count()
        val (dr, dOut) = kill(hearts, monitor, dk, dv, T0, S.copy(enabled = false))
        check("store: disabled monitoring leaves the transfer working and writes nothing",
            dr is PvpResult.Done && dr.decision is Decision.Transfer && dOut == Outcome.Clean && monitor.count() == before)

        // Listing and review status.
        val open = monitor.flags(status = Status.OPEN, limit = 100)
        check("store: lists open flags newest first", open.isNotEmpty() && open.zipWithNext().all { (a1, b1) -> a1.at >= b1.at })
        check("store: flags by player find both roles", monitor.flags(main, limit = 100).all { it.killer == main || it.victim == main } &&
            monitor.flags(alt, limit = 100).size == monitor.flags(main, limit = 100).size)
        val heartsBefore = hearts.load(main)!!.hearts to hearts.load(alt)!!.hearts
        check("store: mark reviewed", monitor.setStatus(flag!!.id, Status.REVIEWED, "staff", "family, same house", T0))
        check("store: marking twice changes nothing", !monitor.setStatus(flag.id, Status.REVIEWED, "staff", null, T0))
        val reviewed = monitor.flag(flag.id)!!
        check("store: review recorded", reviewed.status == Status.REVIEWED && reviewed.reviewedBy == "staff" && reviewed.reviewNote == "family, same house")
        check("store: reviewing changes no heart", (hearts.load(main)!!.hearts to hearts.load(alt)!!.hearts) == heartsBefore)

        // Retention: only closed flags past retention are deleted.
        val openCount = monitor.count(Status.OPEN)
        check("store: retention 0 keeps everything", monitor.purge(0, T0 + 10_000L * 86_400_000L) == 0)
        val removed = monitor.purge(180, T0 + 181L * 86_400_000L)
        check("store: retention deletes only reviewed/dismissed flags", removed == 1 && monitor.flag(flag.id) == null && monitor.count(Status.OPEN) == openCount)
    }
}

// ── Restart, duplicates ────────────────────────────────────

private fun restartAndDuplicates() = withTemp { dir ->
    val main = UUID.randomUUID()
    val alt = UUID.randomUUID()
    var txId = ""
    var transfer: HeartMonitorRules.Transfer? = null
    withDb(dir) { db ->
        val (hearts, monitor) = stores(db)
        hearts.ensure(main, 10, T0)
        hearts.ensure(alt, 10, T0)
        connections(db, main to "h1", alt to "h1")
        val req = request(main, alt, T0)
        val result = hearts.transferPvp(req)
        transfer = transferOf(result, req)
        txId = (result as PvpResult.Done).transactionId
        check("dup: first evaluation flags", monitor.evaluate(transfer!!, S, T0 + 1) is Outcome.Flagged)
        check("dup: evaluating the same transfer again adds nothing", monitor.evaluate(transfer!!, S, T0 + 2) == Outcome.AlreadyFlagged && monitor.count() == 1)
        check("dup: a repeated death event is a Duplicate and never reaches the monitor", hearts.transferPvp(req) == PvpResult.Duplicate && monitor.count() == 1)
    }
    // 10. Survives a restart (new DatabaseManager on the same data.db), and dedupe still holds.
    withDb(dir) { db ->
        val (hearts, monitor) = stores(db)
        val kept = monitor.flags(limit = 10)
        check("restart: flag survives", kept.size == 1 && kept[0].transactionId == txId && kept[0].signals == listOf(Signal.SHARED_CONNECTION))
        check("restart: re-evaluating after restart adds nothing", monitor.evaluate(transfer!!, S, T0 + 3) == Outcome.AlreadyFlagged && monitor.count() == 1)
        check("restart: hearts unchanged by the monitor", hearts.load(main)!!.hearts == 11 && hearts.load(alt)!!.hearts == 9)
        check("restart: the notify throttle is persisted", monitor.flags(limit = 1)[0].notified)
    }
}

// ── Rejected transfers, failures ───────────────────────────

private fun rejectedAndFailures() = withTemp { dir ->
    withDb(dir) { db ->
        val (hearts, monitor) = stores(db)
        val (main, alt) = players(hearts, 2)
        connections(db, main to "hx", alt to "hx")
        val first = request(main, alt, T0)
        val ok = hearts.transferPvp(first)
        check("rejected: first transfer moves a heart", ok is PvpResult.Done && ok.decision is Decision.Transfer)

        // 13. The cooldown is untouched: the next kill within 30 minutes moves nothing.
        val second = request(main, alt, T0 + 10 * MIN)
        val cooled = hearts.transferPvp(second)
        check("rejected: 30-minute cooldown unchanged", cooled is PvpResult.Done && cooled.decision is Decision.Cooldown && hearts.load(alt)!!.hearts == 9)
        // 12. A rejected transfer is never logged as a transfer, even if someone asked.
        val fake = HeartMonitorRules.Transfer((cooled as PvpResult.Done).transactionId, second.dedupeKey, Source.KILL, main, alt, second.now, 11, 12, 9, 8)
        check("rejected: a cooldown rejection is never logged as a transfer", monitor.evaluate(fake, S, T0 + 11 * MIN) == Outcome.NotATransfer && monitor.count() == 0)
        val unknown = HeartMonitorRules.Transfer(UUID.randomUUID().toString(), "nope", Source.KILL, main, alt, T0, 1, 2, 2, 1)
        check("rejected: an unknown transaction is never logged", monitor.evaluate(unknown, S, T0) == Outcome.NotATransfer)
        val swapped = transferOf(ok, first).copy(killer = alt, victim = main)
        check("rejected: a transaction with other players is never logged", monitor.evaluate(swapped, S, T0) == Outcome.NotATransfer)

        // 14. Minimum: a victim on 1 heart loses nothing, so nothing is monitored.
        val last = players(hearts, 1, start = 1)[0]
        val atMin = hearts.transferPvp(request(main, last, T0))
        check("rejected: final-heart protection unchanged", atMin is PvpResult.Done && atMin.decision == Decision.VictimAtMinimum && hearts.load(last)!!.hearts == 1)
        check("rejected: final-heart rejection is not a transfer", monitor.evaluate(transferOf(atMin, request(main, last, T0)).copy(), S, T0) == Outcome.NotATransfer)
        // 15. Maximum: a killer on 20 takes nothing.
        val maxed = players(hearts, 1, start = 20)[0]
        val maxReq = request(maxed, alt, T0 + 20 * MIN)
        val atMax = hearts.transferPvp(maxReq)
        check("rejected: maximum unchanged", atMax is PvpResult.Done && atMax.decision == Decision.KillerAtMaximum && hearts.load(maxed)!!.hearts == 20)
        check("rejected: killer-at-maximum is not a transfer", monitor.evaluate(transferOf(atMax, maxReq), S, T0) == Outcome.NotATransfer)

        // 16. A monitoring failure neither duplicates nor reverses hearts.
        val (k2, v2) = players(hearts, 2)
        val req = request(k2, v2, T0)
        val done = hearts.transferPvp(req)
        db.execute("DROP TABLE lifesteal_heart_monitor_flags")
        val failed = runCatching { monitor.evaluate(transferOf(done, req), S, T0 + 1) }
        check("failure: a broken monitor throws (the plugin logs and drops it)", failed.isFailure)
        check("failure: hearts stay as the committed transfer left them", hearts.load(k2)!!.hearts == 11 && hearts.load(v2)!!.hearts == 9)
        check("failure: the same death can't be processed again", hearts.transferPvp(req) == PvpResult.Duplicate && hearts.load(v2)!!.hearts == 9)
        monitor.createTables()
        check("failure: monitoring recovers after the table is back", monitor.evaluate(transferOf(done, req), S, T0 + 2) == Outcome.Clean)
    }
}

// ── Efficiency ─────────────────────────────────────────────

private fun efficiency() = withTemp { dir ->
    withDb(dir) { db ->
        stores(db)
        connections(db)
        HeartTransferMonitorStore(db).createTables()
        fun plan(sql: String, vararg params: Any?): String =
            db.query("EXPLAIN QUERY PLAN $sql", *params) { it.getString("detail") }.joinToString(" | ")
        val pair = plan(
            "SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE killer = ? AND victim = ? AND created_at >= ? AND created_at <= ? AND type = 'PVP' AND outcome = 'TRANSFERRED'",
            "a", "b", 0, 1
        )
        check("efficiency: pair counts use the pair index ($pair)", "idx_lifesteal_heart_transactions_pair" in pair && "SCAN lifesteal_heart_transactions" !in pair)
        val donors = plan(
            "SELECT DISTINCT victim FROM lifesteal_heart_transactions WHERE killer = ? AND created_at >= ? AND created_at <= ? AND type = 'PVP' AND outcome = 'TRANSFERRED' AND victim IS NOT NULL LIMIT 50",
            "a", 0, 1
        )
        check("efficiency: donor lookup uses an index ($donors)", "USING INDEX" in donors || "USING COVERING INDEX" in donors)
        val shared = plan(
            "SELECT COUNT(DISTINCT x.uuid) AS n FROM player_connections x JOIN player_connections y ON y.connection_hash = x.connection_hash AND y.uuid <> x.uuid WHERE x.uuid IN (?, ?) AND y.uuid IN (?, ?, ?)",
            "a", "b", "a", "b", "c"
        )
        check("efficiency: connection join uses indexes ($shared)", "SCAN x" !in shared && "SCAN y" !in shared)
        val throttle = plan("SELECT 1 AS hit FROM lifesteal_heart_monitor_flags WHERE pair_key = ? AND notified = 1 AND created_at > ? LIMIT 1", "p", 0)
        check("efficiency: notify throttle uses the pair index ($throttle)", "idx_lifesteal_heart_monitor_pair" in throttle)
        val store = source("lifesteal/hearts/monitor/HeartTransferMonitorStore.kt")
        check("efficiency: signal D reads a bounded number of accounts", "LIMIT ?" in store && "MAX_DONORS = 50" in store)
    }
}

// ── Wiring, privacy, no punishment ─────────────────────────

private fun wiring() {
    val manager = source("lifesteal/hearts/LifestealHeartManager.kt")
    check("wiring: still exactly three transferPvp calls", Regex("store\\.transferPvp\\(").findAll(manager).count() == 3)
    check("wiring: every heart path hands its result to the monitor",
        Regex("monitorTransfer\\(result, request, ").findAll(manager).count() == 3 &&
            "HeartMonitorRules.Source.COMBAT_NPC" in manager && "HeartMonitorRules.Source.RANKED_FORFEIT" in manager &&
            "HeartMonitorRules.Source.RANKED_KILL" in manager && "HeartMonitorRules.Source.RANKED_ENVIRONMENT" in manager &&
            "HeartMonitorRules.Source.COMBAT_TAG" in manager)
    check("wiring: only real transfers are monitored", "if (result.decision !is HeartRules.Decision.Transfer) return" in manager)
    for (fn in listOf("fun onDeath(", "fun onCombatNpcDeath(", "fun onRankedForfeit(")) {
        val body = manager.substringAfter(fn).substringBefore("\n    fun ").substringBefore("\n    private fun ")
        check("wiring: $fn monitors after the transfer committed", body.indexOf("store.transferPvp(request)") in 0 until body.indexOf("monitorTransfer("))
        check("wiring: $fn monitors only inside the Done branch", body.indexOf("is HeartStore.PvpResult.Done ->") in 0 until body.indexOf("monitorTransfer("))
    }
    check("wiring: a monitor failure can't reach the transfer", Regex("""try \{\s*monitor\.onTransfer\(""").containsMatchIn(manager))

    val monitor = source("lifesteal/hearts/monitor/HeartTransferMonitor.kt")
    check("wiring: evaluation runs on the database worker, not the main thread", "plugin.databaseManager.async(\"heart monitor" in monitor)
    check("wiring: worker errors are caught and only logged", "the transfer itself is unaffected" in monitor)
    check("wiring: staff chat goes back to the main thread", "Bukkit.getScheduler().runTask(plugin" in monitor)
    // 18. Only staff with the monitor permission are told; the command is gated the same way.
    check("privacy: notices only to holders of the monitor permission", "if (!player.hasPermission(PERMISSION)) continue" in monitor)
    check("privacy: messages go through commsManager.send", "plugin.commsManager.send(player, message)" in monitor)
    check("privacy: /admin log row names no connection signal", monitor.substringAfter("\"HEART_MONITOR_FLAG\"").substringBefore(")\n").let {
        "reason(" !in it && "signals.joinToString" !in it && "SHARED" !in it
    })
    val command = source("lifesteal/hearts/LifestealHeartsCommand.kt")
    check("privacy: /lifesteal hearts monitor needs its own permission",
        command.substringAfter("private fun monitor(").substringBefore("Msg.noPermission").contains("!sender.hasPermission(MONITOR_PERMISSION)"))
    check("privacy: the monitor subcommand is checked before the admin permission",
        command.indexOf("monitor(sender, args)") in 0 until command.indexOf("if (!sender.hasPermission(PERMISSION))"))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("privacy: permission registered, default op", Regex("joshymc\\.lifesteal\\.hearts\\.monitor:\\n\\s+description: .+\\n\\s+default: op").containsMatchIn(pluginYml))
    val all = monitor + source("lifesteal/hearts/monitor/HeartTransferMonitorStore.kt") + source("lifesteal/hearts/monitor/HeartTransferMonitoring.kt")
    // 19. Players are never told: no message to the killer/victim, no broadcast.
    check("privacy: nothing is sent to the players involved or broadcast",
        "send(killer" !in all && "send(victim" !in all && "Bukkit.broadcast" !in all && "broadcast(" !in all && "sendMessage" !in all)
    check("privacy: no hash or address is ever selected", "SELECT connection_hash" !in all && "hostAddress" !in all && "getAddress" !in all)
    // 20. No automatic punishment, rollback, Elo or account change.
    val storeSource = source("lifesteal/hearts/monitor/HeartTransferMonitorStore.kt")
    for (forbidden in listOf("punishmentManager", "adminChange(", "transferPvp(", "UPDATE lifesteal_hearts", "lifesteal_heart_cooldowns", "kick(", ".ban(",
        "lifesteal_ranked_ratings", "disband", "lifestealRatingManager", "lifestealTeamManager"))
        check("no punishment: monitor never uses $forbidden", forbidden !in monitor + storeSource)
    val written = Regex("(INTO|UPDATE|DELETE FROM)\\s+(\\w+)").findAll(storeSource).map { it.groupValues[2] }.toList()
    check("no punishment: the store only writes its own table ($written)", written.isNotEmpty() && written.all { it == "lifesteal_heart_monitor_flags" })

    val config = source("lifesteal/LifestealConfigManager.kt")
    check("wiring: settings read separately from the rest of lifesteal.yml", "loadHeartMonitoring(yaml)" in config && "HeartMonitorConfig.parse" in config)
    check("wiring: monitor refreshed with the heart system, guarded", Regex("""try \{\s*monitor\.refresh\(\)""").containsMatchIn(manager))
}
