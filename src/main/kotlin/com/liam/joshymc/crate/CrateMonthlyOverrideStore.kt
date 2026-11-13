package com.liam.joshymc.crate

import com.liam.joshymc.manager.SqliteDatabase

/**
 * The admin's monthly crate override (issue #1520), one row (`id = 1`) in `crate_monthly_override`.
 * The month is stored by name and the times as epoch milliseconds (UTC), so the row reads the
 * same after a restart, a DST change or a timezone change. Bukkit-free: talks only to [SqliteDatabase].
 */
class CrateMonthlyOverrideStore(private val db: SqliteDatabase) {

    sealed interface Loaded {
        data object None : Loaded
        data class Found(val override: CrateMonthlyRotation.Override) : Loaded
        /** A row whose month can't be read: kept (never silently deleted), not applied. */
        data class Unreadable(val raw: String) : Loaded
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS crate_monthly_override (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                month TEXT NOT NULL,
                set_at INTEGER NOT NULL,
                expires_at INTEGER,
                set_by TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    fun read(): Loaded {
        val row = db.queryFirst("SELECT month, set_at, expires_at, set_by FROM crate_monthly_override WHERE id = 1") { rs ->
            val expires = rs.getLong("expires_at").takeUnless { rs.wasNull() }
            listOf(rs.getString("month"), rs.getLong("set_at"), expires, rs.getString("set_by"))
        } ?: return Loaded.None
        val raw = row[0] as String
        val month = CrateModels.monthOf(raw) ?: return Loaded.Unreadable(raw)
        return Loaded.Found(CrateMonthlyRotation.Override(month, row[1] as Long, row[2] as Long?, row[3] as String))
    }

    /** Writes [override] as the one override (replacing any earlier one). */
    fun save(override: CrateMonthlyRotation.Override) {
        db.execute(
            "INSERT OR REPLACE INTO crate_monthly_override (id, month, set_at, expires_at, set_by) VALUES (1, ?, ?, ?, ?)",
            CrateModels.monthId(override.month), override.setAt, override.expiresAt, override.setBy
        )
    }

    /** Removes the override, whatever it is. True when there was one. */
    fun clear(): Boolean = db.executeUpdate("DELETE FROM crate_monthly_override WHERE id = 1") == 1

    /**
     * Removes [override] only if it is still the stored one, so an expiry check never deletes an
     * override an admin set in the meantime.
     */
    fun clearIf(override: CrateMonthlyRotation.Override): Boolean =
        db.executeUpdate(
            "DELETE FROM crate_monthly_override WHERE id = 1 AND month = ? AND set_at = ?",
            CrateModels.monthId(override.month), override.setAt
        ) == 1
}
