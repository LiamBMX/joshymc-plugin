package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class DatabaseManager(private val plugin: Joshymc) {

    private lateinit var connection: Connection

    fun start() {
        val dbFile = File(plugin.dataFolder, "data.db")
        plugin.dataFolder.mkdirs()

        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

        // Enable WAL mode for better concurrent read performance
        connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }

        plugin.logger.info("[Database] Connected to SQLite (${dbFile.name})")
    }

    fun shutdown() {
        if (::connection.isInitialized && !connection.isClosed) {
            connection.close()
            plugin.logger.info("[Database] Connection closed.")
        }
    }

    /**
     * Execute a statement that doesn't return results (CREATE, INSERT, UPDATE, DELETE).
     */
    fun execute(sql: String, vararg params: Any?) {
        connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    /**
     * Execute a statement and return the number of affected rows.
     * Used for atomic check-and-delete to prevent race condition dupes.
     */
    fun executeUpdate(sql: String, vararg params: Any?): Int {
        return connection.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { index, param -> stmt.setObject(index + 1, param) }
            stmt.executeUpdate()
        }
    }

    /**
     * Execute a query and map each row to a result using the provided mapper.
     */
    fun <T> query(sql: String, vararg params: Any?, mapper: (java.sql.ResultSet) -> T): List<T> {
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

    /**
     * Execute a query and return the first result, or null.
     */
    fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (java.sql.ResultSet) -> T): T? {
        return query(sql, *params, mapper = mapper).firstOrNull()
    }

    /**
     * Run multiple statements in a single transaction for better performance.
     */
    fun transaction(block: () -> Unit) {
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

    /**
     * Create a table if it doesn't exist. Called by individual managers during init.
     */
    fun createTable(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }
}
