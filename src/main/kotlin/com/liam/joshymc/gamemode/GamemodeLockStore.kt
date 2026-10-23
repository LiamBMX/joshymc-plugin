package com.liam.joshymc.gamemode

import com.liam.joshymc.manager.SqliteDatabase

/**
 * Persistent gamemode lock states plus an audit trail of every lock/unlock (issue #1289).
 * Bukkit-free: talks only to [SqliteDatabase].
 */
class GamemodeLockStore(private val db: SqliteDatabase) {

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS gamemode_locks (
                gamemode TEXT PRIMARY KEY,
                locked INTEGER NOT NULL DEFAULT 0,
                changed_by TEXT,
                changed_at INTEGER
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS gamemode_lock_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                gamemode TEXT NOT NULL,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                actor_uuid TEXT,
                at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun lockedGamemodes(): Set<ServerGamemode> =
        db.query("SELECT gamemode FROM gamemode_locks WHERE locked = 1") { it.getString("gamemode") }
            .mapNotNull { ServerGamemode.fromId(it) }
            .toSet()

    /**
     * Sets [gamemode]'s lock to [locked]. Returns false when it already was (nothing written).
     * The state change and its audit row are one transaction.
     */
    fun setLocked(gamemode: ServerGamemode, locked: Boolean, actor: String, actorUuid: String?, now: Long): Boolean {
        var changed = false
        db.transaction {
            db.execute("INSERT OR IGNORE INTO gamemode_locks (gamemode, locked) VALUES (?, 0)", gamemode.id)
            val flag = if (locked) 1 else 0
            changed = db.executeUpdate(
                "UPDATE gamemode_locks SET locked = ?, changed_by = ?, changed_at = ? WHERE gamemode = ? AND locked <> ?",
                flag, actor, now, gamemode.id, flag
            ) == 1
            if (changed) {
                db.execute(
                    "INSERT INTO gamemode_lock_audit (gamemode, action, actor, actor_uuid, at) VALUES (?, ?, ?, ?, ?)",
                    gamemode.id, if (locked) "LOCK" else "UNLOCK", actor, actorUuid, now
                )
            }
        }
        return changed
    }
}
