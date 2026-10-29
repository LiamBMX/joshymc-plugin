package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Logger
import kotlin.concurrent.withLock

/**
 * Separate SQLite database (playerdata.db) for clearly player-owned data,
 * kept apart from the server-wide data.db (DatabaseManager). Systems migrate
 * over one at a time; each migration is recorded in playerdata_migrations so
 * it only runs once.
 *
 * [shutdown] runs once (issue #1325); after it every call fails with an [SQLException].
 */
class PlayerDatabaseManager(private val dataFolder: File, private val logger: Logger) : SqliteDatabase {

    constructor(plugin: Joshymc) : this(plugin.dataFolder, plugin.logger)

    private lateinit var connection: Connection

    /** Owns [connection]: every use holds it, so threads never share it mid-statement or mid-transaction. */
    private val lock = ReentrantLock()
    /** Transaction nesting depth; only touched while holding [lock]. */
    private var transactionDepth = 0
    /** Set by [shutdown] while holding [lock]; no statement runs once it's true. */
    @Volatile private var closed = false

    fun start() {
        val dbFile = File(dataFolder, "playerdata.db")
        dataFolder.mkdirs()

        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // Enable WAL mode for better concurrent read performance
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }

        createTable("""
            CREATE TABLE IF NOT EXISTS playerdata_migrations (
                name TEXT PRIMARY KEY,
                migrated_at INTEGER NOT NULL
            )
        """.trimIndent())

        logger.info("[Database] Loaded player database: ${dbFile.name}")
    }

    /**
     * Checkpoints the WAL and closes the connection. Safe to call again and when [start]
     * never ran or failed. Never waits forever for another thread holding the connection:
     * after [LOCK_WAIT_SECONDS] it leaves it open and throws. Also throws when the close
     * itself failed, so the caller reports a failed step.
     */
    fun shutdown() {
        val acquired = try {
            lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) throw IllegalStateException("playerdata.db still in use after ${LOCK_WAIT_SECONDS}s; left it open")
        try {
            if (closed) return
            closed = true
            if (!::connection.isInitialized || connection.isClosed) return
            try {
                connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
            } catch (e: Exception) {
                logger.warning("[Database] WAL checkpoint on shutdown failed (committed rows stay in playerdata.db-wal): ${e.message}")
            }
            try {
                connection.close()
            } catch (e: Exception) {
                throw IllegalStateException("closing playerdata.db failed", e)
            }
            logger.info("[Database] Player database closed.")
        } finally {
            lock.unlock()
        }
    }

    /** Every connection use goes through here, and refuses once [shutdown] closed it. */
    private inline fun <T> locked(block: () -> T): T = lock.withLock {
        if (closed) throw SQLException("playerdata.db is closed (the plugin has shut down)")
        block()
    }

    /**
     * Whether a one-time migration (identified by name, e.g. "player_settings_v1")
     * has already completed. Callers should skip re-migrating if this is true.
     */
    fun hasMigrated(name: String): Boolean {
        return queryFirst(
            "SELECT 1 FROM playerdata_migrations WHERE name = ?", name
        ) { it.getInt(1) } != null
    }

    fun markMigrated(name: String) {
        execute(
            "INSERT OR REPLACE INTO playerdata_migrations (name, migrated_at) VALUES (?, ?)",
            name, System.currentTimeMillis()
        )
    }

    override fun execute(sql: String, vararg params: Any?) {
        locked {
            connection.prepareStatement(sql).use { stmt ->
                params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
                stmt.executeUpdate()
            }
        }
    }

    override fun executeUpdate(sql: String, vararg params: Any?): Int = locked {
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    override fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> = locked {
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

    override fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? {
        return query(sql, *params, mapper = mapper).firstOrNull()
    }

    /** Holds [lock] for the whole block; a nested call joins the outer transaction (see DatabaseManager). */
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

    override fun createTable(sql: String) {
        locked {
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private companion object {
        /** How long shutdown waits for whoever still holds the connection. */
        const val LOCK_WAIT_SECONDS = 20L
    }
}
