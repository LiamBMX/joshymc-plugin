package com.liam.joshymc.manager

import java.sql.ResultSet

/**
 * Common SQLite access surface shared by DatabaseManager (data.db) and
 * PlayerDatabaseManager (playerdata.db), so migration code can read from one
 * and write to the other without caring which concrete manager it's holding.
 */
interface SqliteDatabase {
    fun execute(sql: String, vararg params: Any?)
    fun executeUpdate(sql: String, vararg params: Any?): Int
    fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T>
    fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T?
    fun transaction(block: () -> Unit)
    fun createTable(sql: String)
}
