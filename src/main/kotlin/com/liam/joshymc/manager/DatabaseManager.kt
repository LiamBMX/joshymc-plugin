package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
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
 */
class DatabaseManager(private val plugin: Joshymc) : SqliteDatabase {

    private lateinit var connection: Connection

    private val lock = ReentrantLock()
    /** Transaction nesting depth; only touched while holding [lock]. */
    private var transactionDepth = 0
    /** True while [drainPending] runs, so queued work that touches the DB doesn't re-drain. */
    private var draining = false

    private class PendingWork(val description: String, val block: () -> Unit)
    private val pending = ConcurrentLinkedQueue<PendingWork>()
    private val wakeup = Semaphore(0)
    @Volatile private var accepting = false
    private var worker: Thread? = null
    /** Last time a failure was logged per description, so a broken DB doesn't spam every flush. */
    private val lastFailureLog = ConcurrentHashMap<String, Long>()

    fun start() {
        val dbFile = File(plugin.dataFolder, "data.db")
        plugin.dataFolder.mkdirs()

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

        plugin.logger.info("[Database] Loaded server database: ${dbFile.name}")
    }

    fun shutdown() {
        // Stop taking new queued work (later async() calls run inline), then let the
        // worker finish what's queued before the connection goes away.
        accepting = false
        worker?.let { thread ->
            wakeup.release()
            thread.join(TimeUnit.SECONDS.toMillis(10))
            if (thread.isAlive) plugin.logger.warning("[Database] DB thread still busy after 10s; finishing queued writes on shutdown.")
        }
        worker = null

        lock.withLock {
            drainPending()
            if (::connection.isInitialized && !connection.isClosed) {
                // Force WAL contents into data.db itself before closing. Without this,
                // recently-committed rows only live in data.db-wal — if a restart/redeploy
                // ever loses or skips that sidecar file (e.g. a backup step that only
                // grabs data.db), the last writes before shutdown would appear to vanish.
                try {
                    connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
                } catch (e: Exception) {
                    plugin.logger.warning("[Database] WAL checkpoint on shutdown failed: ${e.message}")
                }
                connection.close()
                plugin.logger.info("[Database] Connection closed.")
            }
        }
    }

    /**
     * Queue database work that doesn't need a result (batch flushes, logging) for the DB
     * thread. Failures are logged with [description] and never reach the caller. Snapshot
     * any mutable game state before calling: [block] runs on another thread.
     * Once shutdown has begun it runs inline, so late writes from onDisable still land.
     */
    fun async(description: String, block: () -> Unit) {
        if (!accepting) {
            lock.withLock { drainPending(); runLogged(PendingWork(description, block)) }
            return
        }
        pending.add(PendingWork(description, block))
        wakeup.release()
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

    private fun runLogged(work: PendingWork) {
        try {
            work.block()
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val last = lastFailureLog[work.description] ?: 0L
            if (now - last >= 60_000L) {
                lastFailureLog[work.description] = now
                plugin.logger.warning("[Database] Queued '${work.description}' failed: ${e.message}")
            }
        }
    }

    /** Every connection use goes through here: take the lock, run anything still queued, then [block]. */
    private inline fun <T> locked(block: () -> T): T = lock.withLock {
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
                    plugin.logger.warning("[Database] Rollback failed: ${rollbackError.message}")
                }
                throw e
            } finally {
                transactionDepth = 0
                try {
                    connection.autoCommit = true
                } catch (e: Exception) {
                    plugin.logger.warning("[Database] Could not restore autoCommit: ${e.message}")
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
}
