package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * data.db access. One JDBC connection, owned by [lock]: every statement, query and
 * transaction runs while holding it, so two threads can never share the connection or
 * each other's autoCommit/commit/rollback state (issue #1112).
 *
 * Writes that don't need a result can go through [async], which queues them for the
 * single "JoshyMC-DB" thread instead of running them on the server thread. Queued work
 * keeps submit order: a synchronous call first runs anything still queued, so a read
 * always sees every write submitted before it (e.g. a quit flush followed by a rejoin load).
 *
 * [shutdown] runs once (issue #1325): it stops queueing, runs everything already queued,
 * checkpoints the WAL and closes. After that every call fails with an [SQLException], and
 * late [async] work is logged as not saved instead of vanishing.
 */
class DatabaseManager(private val dataFolder: File, private val logger: Logger) : SqliteDatabase {

    constructor(plugin: Joshymc) : this(plugin.dataFolder, plugin.logger)

    private lateinit var connection: Connection

    private val lock = ReentrantLock()
    /** Transaction nesting depth; only touched while holding [lock]. */
    private var transactionDepth = 0
    /** True while [drainPending] runs, so queued work that touches the DB doesn't re-drain. */
    private var draining = false

    private class PendingWork(val description: String, val block: () -> Unit)
    private val pending = ConcurrentLinkedQueue<PendingWork>()
    private val wakeup = Semaphore(0)
    /** Held while reading [accepting] and adding to [pending], so nothing is queued after shutdown's final drain. */
    private val queueLock = Any()
    @Volatile private var accepting = false
    /** Set by [shutdown] while holding [lock]; no statement runs once it's true. */
    @Volatile private var closed = false
    private var worker: Thread? = null
    /** Last time a failure was logged per description, so a broken DB doesn't spam every flush. */
    private val lastFailureLog = ConcurrentHashMap<String, Long>()
    /** Queued work that failed after shutdown began (on the DB thread or inline), reported by [shutdown]. */
    private val shutdownFailures = ConcurrentLinkedQueue<String>()

    fun start() {
        val dbFile = File(dataFolder, "data.db")
        dataFolder.mkdirs()

        lock.withLock {
            connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

            // Enable WAL mode for better concurrent read performance
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
        }

        accepting = true
        worker = Thread({ workerLoop() }, "JoshyMC-DB").apply {
            isDaemon = true
            start()
        }

        logger.info("[Database] Loaded server database: ${dbFile.name}")
    }

    /**
     * Runs every queued write, checkpoints the WAL and closes the connection. Safe to call
     * again and when [start] never ran or failed. Never waits forever: if another thread
     * still holds the connection after the timeouts, it is left open and this throws. Also
     * throws (after closing) when a queued write failed or the close itself failed, so the
     * caller reports a failed step instead of a clean shutdown.
     */
    fun shutdown() {
        // Stop taking new queued work (later async() calls run inline), then let the
        // worker finish what's queued before the connection goes away.
        val queued = synchronized(queueLock) {
            accepting = false
            pending.size
        }
        worker?.let { thread ->
            wakeup.release()
            try {
                thread.join(TimeUnit.SECONDS.toMillis(WORKER_JOIN_SECONDS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) logger.warning("[Database] DB thread still busy after ${WORKER_JOIN_SECONDS}s; finishing queued writes on shutdown.")
        }
        worker = null

        val acquired = try {
            lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) {
            // Never close a connection another thread is still using. What it already
            // committed is safe in data.db / data.db-wal; what is still queued is not.
            throw IllegalStateException(
                "data.db still in use after another ${LOCK_WAIT_SECONDS}s; left it open, " +
                    "${pending.size} queued write(s) were NOT saved"
            )
        }
        try {
            if (closed) return
            drainPending()
            closed = true
            val failed = shutdownFailures.toList()
            if (!::connection.isInitialized) return

            var closeError: Exception? = null
            try {
                if (!connection.isClosed) {
                    // Force WAL contents into data.db itself before closing. Without this,
                    // recently-committed rows only live in data.db-wal — if a restart/redeploy
                    // ever loses or skips that sidecar file (e.g. a backup step that only
                    // grabs data.db), the last writes before shutdown would appear to vanish.
                    try {
                        connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                    } catch (e: Exception) {
                        logger.warning("[Database] WAL checkpoint on shutdown failed (committed rows stay in data.db-wal): ${e.message}")
                    }
                    connection.close()
                }
            } catch (e: Exception) {
                closeError = e
            }

            if (failed.isNotEmpty()) {
                throw IllegalStateException(
                    "${failed.size} write(s) failed during shutdown and were NOT saved: " +
                        failed.distinct().joinToString(),
                    closeError
                )
            }
            if (closeError != null) throw IllegalStateException("closing data.db failed", closeError)
            logger.info("[Database] Connection closed" + (if (queued > 0) " after running $queued queued write(s)." else "."))
        } finally {
            lock.unlock()
        }
    }

    /**
     * Queue database work that doesn't need a result (batch flushes, logging) for the DB
     * thread. Failures are logged with [description] and never reach the caller. Snapshot
     * any mutable game state before calling: [block] runs on another thread.
     * Once shutdown has begun it runs inline, so late writes from onDisable still land.
     */
    fun async(description: String, block: () -> Unit) {
        synchronized(queueLock) {
            if (accepting) {
                pending.add(PendingWork(description, block))
                wakeup.release()
                return
            }
        }
        lock.withLock {
            if (closed) {
                logRateLimited("closed:$description") { "[Database] '$description' was NOT saved: data.db is already closed." }
                return
            }
            drainPending()
            runLogged(PendingWork(description, block))
        }
    }

    private fun workerLoop() {
        while (true) {
            try {
                wakeup.acquire()
            } catch (_: InterruptedException) {
                return
            }
            lock.withLock { drainPending() }
            if (!accepting && pending.isEmpty()) return
        }
    }

    /** Runs queued work in order. Caller holds [lock]; skipped inside a transaction so queued work never joins it. */
    private fun drainPending() {
        if (draining || transactionDepth > 0) return
        draining = true
        try {
            while (true) {
                val work = pending.poll() ?: break
                runLogged(work)
            }
        } finally {
            draining = false
        }
    }

    /** Runs [work]; a failure is logged (rate-limited per description) and, during shutdown, kept for [shutdown]'s report. */
    private fun runLogged(work: PendingWork) {
        try {
            work.block()
        } catch (e: Exception) {
            if (!accepting) shutdownFailures.add(work.description)
            logRateLimited(work.description) { "[Database] Queued '${work.description}' failed: ${e.message}" }
        }
    }

    private inline fun logRateLimited(key: String, message: () -> String) {
        val now = System.currentTimeMillis()
        val last = lastFailureLog[key] ?: 0L
        if (now - last >= 60_000L) {
            lastFailureLog[key] = now
            logger.warning(message())
        }
    }

    /** Every connection use goes through here: take the lock, run anything still queued, then [block]. */
    private inline fun <T> locked(block: () -> T): T = lock.withLock {
        if (closed) throw SQLException("data.db is closed (the plugin has shut down)")
        drainPending()
        block()
    }

    /**
     * Execute a statement that doesn't return results (CREATE, INSERT, UPDATE, DELETE).
     */
    override fun execute(sql: String, vararg params: Any?) {
        locked {
            connection.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
                stmt.executeUpdate()
            }
        }
    }

    /**
     * Execute a statement and return the number of affected rows.
     * Used for atomic check-and-delete to prevent race condition dupes.
     */
    override fun executeUpdate(sql: String, vararg params: Any?): Int = locked {
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    /**
     * Run an INSERT and return the rowid it created, or null when it inserted nothing
     * (e.g. INSERT OR IGNORE hit a conflict). The insert and the `last_insert_rowid()`
     * read share one hold of [lock], so another thread's insert can't land between them
     * and hand back its id instead (issue #1159). Joins an enclosing [transaction].
     */
    fun insertReturningId(sql: String, vararg params: Any?): Int? = locked {
        val inserted = connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
        if (inserted <= 0) return@locked null
        connection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT last_insert_rowid()").use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }

    /**
     * Execute a query and map each row to a result using the provided mapper.
     */
    override fun <T> query(sql: String, vararg params: Any?, mapper: (java.sql.ResultSet) -> T): List<T> = locked {
        val results = mutableListOf<T>()
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        results
    }

    /**
     * Execute a query and return the first result, or null.
     */
    override fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (java.sql.ResultSet) -> T): T? {
        return query(sql, *params, mapper = mapper).firstOrNull()
    }

    /**
     * Run multiple statements in a single transaction. The lock is held for the whole
     * block, so no other thread's statements can land inside it or flip autoCommit
     * underneath it. A nested call joins the outer transaction instead of committing early.
     */
    override fun transaction(block: () -> Unit) {
        locked {
            if (transactionDepth > 0) {
                transactionDepth++
                try {
                    block()
                } finally {
                    transactionDepth--
                }
                return
            }
            connection.autoCommit = false
            transactionDepth = 1
            try {
                block()
                connection.commit()
            } catch (e: Throwable) {
                try {
                    connection.rollback()
                } catch (rollbackError: Exception) {
                    logger.warning("[Database] Rollback failed: ${rollbackError.message}")
                }
                throw e
            } finally {
                transactionDepth = 0
                try {
                    connection.autoCommit = true
                } catch (e: Exception) {
                    logger.warning("[Database] Could not restore autoCommit: ${e.message}")
                }
            }
        }
    }

    /**
     * Create a table if it doesn't exist. Called by individual managers during init.
     */
    override fun createTable(sql: String) {
        locked {
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private companion object {
        /** How long shutdown lets the DB thread finish the queue on its own. */
        const val WORKER_JOIN_SECONDS = 10L
        /** How much longer shutdown waits for whoever still holds the connection. */
        const val LOCK_WAIT_SECONDS = 20L
    }
}
