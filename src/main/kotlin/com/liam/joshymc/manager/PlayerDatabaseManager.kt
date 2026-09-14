package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * Separate SQLite database (playerdata.db) for clearly player-owned data,
 * kept apart from the server-wide data.db (DatabaseManager). Systems migrate
 * over one at a time; each migration is recorded in playerdata_migrations so
 * it only runs once.
 */
class PlayerDatabaseManager(private val plugin: Joshymc) : SqliteDatabase {

    private lateinit var connection: Connection

    fun start() {
        val dbFile = File(plugin.dataFolder, "playerdata.db")
        plugin.dataFolder.mkdirs()

        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // Enable WAL mode for better concurrent read performance
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }

        createTable("""
            CREATE TABLE IF NOT EXISTS playerdata_migrations (
                name TEXT PRIMARY KEY,
                migrated_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.logger.info("[Database] Loaded player database: ${dbFile.name}")
    }

    fun shutdown() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
        }
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
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    override fun executeUpdate(sql: String, vararg params: Any?): Int {
        return connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    override fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> {
        val results = mutableListOf<T>()
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    results.add(mapper(rs))
                }
            }
        }
        return results
    }

    override fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? {
        return query(sql, *params, mapper = mapper).firstOrNull()
    }

    override fun transaction(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun createTable(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }
}
