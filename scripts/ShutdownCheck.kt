// Checks the plugin shutdown hardening (issue #1325): ShutdownSequence's step isolation and
// the two SQLite managers' shutdown. The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/shutdown-check.init.gradle.kts shutdownCheck
//
// The databases live in a fresh temp directory that is deleted afterwards; nothing touches
// a server's data. Exits non-zero on the first failed check.

import com.liam.joshymc.lifecycle.ShutdownSequence
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PlayerDatabaseManager
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Collections
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private class Capture : Handler() {
    val records: MutableList<LogRecord> = Collections.synchronizedList(mutableListOf())
    override fun publish(record: LogRecord) { records += record }
    override fun flush() {}
    override fun close() {}
    fun messages(level: Level? = null): List<String> =
        synchronized(records) { records.filter { level == null || it.level == level }.map { it.message } }
}

private fun logger(): Pair<Logger, Capture> {
    val capture = Capture()
    val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        level = Level.ALL
        addHandler(capture)
    }
    return logger to capture
}

private inline fun throwsSql(block: () -> Unit): Boolean = try {
    block()
    false
} catch (_: SQLException) {
    true
}

private fun count(db: File, table: String): Int =
    DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
        c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) } }
    }

private fun walIsEmpty(db: File): Boolean {
    val wal = File(db.path + "-wal")
    return !wal.exists() || wal.length() == 0L
}

fun main() {
    sequenceChecks()
    val root = Files.createTempDirectory("joshymc-shutdown-check").toFile()
    try {
        databaseChecks(File(root, "db"))
        playerDatabaseChecks(File(root, "player"))
    } finally {
        root.deleteRecursively()
    }
    println("Shutdown checks passed ($checks checks).")
}

// ── ShutdownSequence ─────────────────────────────────────────

private fun sequenceChecks() {
    // Normal shutdown: every step, in order.
    run {
        val (log, capture) = logger()
        val calls = mutableListOf<String>()
        val seq = ShutdownSequence(log)
        for (name in listOf("a", "b", "c")) seq.step(name) { calls += name }
        val report = seq.run()
        check("normal: order", calls == listOf("a", "b", "c"))
        check("normal: report", report.ok && report.completed == calls && report.skipped.isEmpty())
        check("normal: nothing severe", capture.messages(Level.SEVERE).isEmpty())
    }

    // One step throws: the rest still run, the failure is logged once with its exception.
    run {
        val (log, capture) = logger()
        val calls = mutableListOf<String>()
        val boom = IllegalStateException("boom")
        val seq = ShutdownSequence(log)
        seq.step("first") { calls += "first" }
        seq.step("broken") { calls += "broken"; throw boom }
        seq.step("databaseManager.shutdown") { calls += "db" }
        val report = seq.run()
        check("one failure: later steps run", calls == listOf("first", "broken", "db"))
        check("one failure: report", report.failed == listOf("broken") && report.completed == listOf("first", "databaseManager.shutdown"))
        val failures = capture.records.filter { it.level == Level.SEVERE && it.thrown != null }
        check("one failure: logged once with the exception", failures.size == 1 && failures[0].thrown === boom)
        check("one failure: log names the step", failures[0].message.contains("broken") && failures[0].message.contains("boom"))
        check("one failure: summary names it", capture.messages(Level.SEVERE).any { it.contains("1 of 3") && it.contains("broken") })
        check("one failure: not reported as completed", "broken" !in report.completed)
    }

    // Several steps throw (including an Error such as NoClassDefFoundError after a jar swap).
    run {
        val (log, _) = logger()
        val calls = mutableListOf<String>()
        val seq = ShutdownSequence(log)
        seq.step("a") { throw RuntimeException("a") }
        seq.step("b") { calls += "b" }
        seq.step("c") { throw NoClassDefFoundError("c") }
        seq.step("d") { throw UninitializedPropertyAccessException("d") }
        seq.step("playerDatabaseManager.shutdown") { calls += "pdb" }
        seq.step("databaseManager.shutdown") { calls += "db" }
        val report = seq.run()
        check("multiple failures: remaining steps run", calls == listOf("b", "pdb", "db"))
        check("multiple failures: all recorded", report.failed == listOf("a", "c", "d"))
    }

    // Uninitialized managers are skipped, not called and not reported as failures.
    run {
        val (log, capture) = logger()
        var called = false
        val seq = ShutdownSequence(log)
        seq.step("never started", { false }) { called = true }
        seq.step("databaseManager.shutdown") {}
        val report = seq.run()
        check("uninitialized: not called", !called)
        check("uninitialized: skipped", report.skipped == listOf("never started") && report.ok)
        check("uninitialized: no severe log", capture.messages(Level.SEVERE).isEmpty())
    }

    // Repeated run: nothing runs twice (no duplicate final saves).
    run {
        val (log, capture) = logger()
        var saves = 0
        val seq = ShutdownSequence(log)
        seq.step("final save") { saves++ }
        val first = seq.run()
        val second = seq.run()
        check("repeat: ran once", saves == 1)
        check("repeat: same report", first == second)
        check("repeat: warned", capture.messages(Level.WARNING).any { it.contains("Already ran") })
        check("repeat: can't add steps after running", try { seq.step("late") {}; false } catch (_: IllegalStateException) { true })
    }

    // A fatal JVM error still lets the databases close, then propagates.
    run {
        val (log, _) = logger()
        var dbClosed = false
        val seq = ShutdownSequence(log)
        seq.step("overflowing") { throw StackOverflowError("deep") }
        seq.step("databaseManager.shutdown") { dbClosed = true }
        val thrown = try { seq.run(); null } catch (e: StackOverflowError) { e }
        check("fatal: database still closed", dbClosed)
        check("fatal: rethrown after the steps", thrown?.message == "deep")
    }
}

// ── DatabaseManager (data.db) ────────────────────────────────

private fun databaseChecks(dir: File) {
    val dbFile = File(dir, "data.db")
    val create = "CREATE TABLE IF NOT EXISTS t (id INTEGER PRIMARY KEY, v TEXT)"

    // Queued writes are all committed by shutdown, and the WAL is folded into data.db.
    run {
        val (log, capture) = logger()
        val db = DatabaseManager(dir, log)
        db.start()
        db.createTable(create)
        repeat(500) { i -> db.async("insert $i") { db.execute("INSERT INTO t (v) VALUES (?)", "q$i") } }
        db.transaction {
            db.execute("INSERT INTO t (v) VALUES ('committed-1')")
            db.execute("INSERT INTO t (v) VALUES ('committed-2')")
        }
        try {
            db.transaction {
                db.execute("INSERT INTO t (v) VALUES ('rolled-back')")
                throw IllegalStateException("abort")
            }
        } catch (_: IllegalStateException) {
        }
        db.shutdown()
        check("db: queued + committed writes saved, rollback not", count(dbFile, "t") == 502)
        check("db: WAL checkpointed", walIsEmpty(dbFile))
        check("db: close logged", capture.messages(Level.INFO).any { it.contains("Connection closed") })

        // No database operations after closure.
        check("db: execute after close throws", throwsSql { db.execute("INSERT INTO t (v) VALUES ('late')") })
        check("db: query after close throws", throwsSql { db.query("SELECT v FROM t") { it.getString(1) } })
        check("db: transaction after close throws", throwsSql { db.transaction { } })
        check("db: insertReturningId after close throws", throwsSql { db.insertReturningId("INSERT INTO t (v) VALUES ('late')") })
        var lateRan = false
        db.async("late write") { lateRan = true }
        check("db: late async not run", !lateRan)
        check("db: late async logged as not saved", capture.messages(Level.WARNING).any { it.contains("late write") && it.contains("NOT saved") })
        check("db: nothing written after close", count(dbFile, "t") == 502)

        // Repeated shutdown is a no-op.
        val closes = capture.messages(Level.INFO).count { it.contains("Connection closed") }
        db.shutdown()
        check("db: second shutdown does nothing", capture.messages(Level.INFO).count { it.contains("Connection closed") } == closes)
    }

    // A failed queued write is reported, and every other queued write still lands.
    run {
        val (log, _) = logger()
        val db = DatabaseManager(dir, log)
        db.start()
        db.execute("DELETE FROM t")
        // Hold the worker inside a queued item so the rest are still queued when shutdown starts.
        val gate = java.util.concurrent.CountDownLatch(1)
        db.async("hold") { gate.await() }
        db.async("good 1") { db.execute("INSERT INTO t (v) VALUES ('g1')") }
        db.async("bad write") { db.execute("INSERT INTO missing_table (v) VALUES ('x')") }
        db.async("good 2") { db.execute("INSERT INTO t (v) VALUES ('g2')") }
        thread { Thread.sleep(200); gate.countDown() }
        val error = try { db.shutdown(); null } catch (e: IllegalStateException) { e }
        check("db: failed queued write makes shutdown fail", error != null && error.message!!.contains("bad write"))
        check("db: other queued writes still saved", count(dbFile, "t") == 2)
        check("db: closed even after the failure", throwsSql { db.execute("SELECT 1") })
    }

    // Writes racing a shutdown are either saved or logged as not saved: never silently lost.
    run {
        val (log, capture) = logger()
        val db = DatabaseManager(dir, log)
        db.start()
        db.execute("DELETE FROM t")
        val perThread = 300
        val writers = (0 until 4).map { w ->
            thread {
                repeat(perThread) { i ->
                    db.async("race $w-$i") { db.execute("INSERT INTO t (v) VALUES (?)", "$w-$i") }
                    if (i % 50 == 0) Thread.sleep(1)
                }
            }
        }
        Thread.sleep(5)
        db.shutdown()
        writers.forEach { it.join() }
        val saved = count(dbFile, "t")
        val dropped = capture.messages(Level.WARNING).count { it.contains("NOT saved") }
        check("db: racing writes all accounted for ($saved saved + $dropped dropped)", saved + dropped == 4 * perThread)
        check("db: no queued write failed", capture.messages(Level.WARNING).none { it.contains("failed") })
    }

    // Never started (startup failed before it): shutdown is quiet, use afterwards refused.
    run {
        val (log, _) = logger()
        val db = DatabaseManager(File(dir, "never"), log)
        val error = try { db.shutdown(); null } catch (e: Exception) { e }
        check("db: shutdown of a never-started manager doesn't throw", error == null)
        check("db: never-started manager refuses use after shutdown", throwsSql { db.execute("SELECT 1") })
    }
}

// ── PlayerDatabaseManager (playerdata.db) ─────────────────────

private fun playerDatabaseChecks(dir: File) {
    val dbFile = File(dir, "playerdata.db")
    run {
        val (log, capture) = logger()
        val db = PlayerDatabaseManager(dir, log)
        db.start()
        db.createTable("CREATE TABLE IF NOT EXISTS p (id INTEGER PRIMARY KEY, v TEXT)")
        db.transaction {
            repeat(50) { i -> db.execute("INSERT INTO p (v) VALUES (?)", "p$i") }
        }
        db.markMigrated("check_v1")
        db.shutdown()
        check("player db: committed rows saved", count(dbFile, "p") == 50)
        check("player db: migration row saved", count(dbFile, "playerdata_migrations") == 1)
        check("player db: WAL checkpointed", walIsEmpty(dbFile))
        check("player db: execute after close throws", throwsSql { db.execute("INSERT INTO p (v) VALUES ('late')") })
        check("player db: query after close throws", throwsSql { db.hasMigrated("check_v1") })
        val closes = capture.messages(Level.INFO).count { it.contains("Player database closed") }
        db.shutdown()
        check("player db: closed once", closes == 1 && capture.messages(Level.INFO).count { it.contains("Player database closed") } == 1)
    }
    run {
        val (log, _) = logger()
        val db = PlayerDatabaseManager(File(dir, "never"), log)
        val error = try { db.shutdown(); null } catch (e: Exception) { e }
        check("player db: shutdown of a never-started manager doesn't throw", error == null)
    }
}
