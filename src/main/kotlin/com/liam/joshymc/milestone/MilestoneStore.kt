package com.liam.joshymc.milestone

import com.liam.joshymc.manager.DatabaseManager
import java.util.UUID

/**
 * Milestone persistence in data.db (issue #1420), Bukkit-free.
 *
 * - `milestone_progress`: one row per player + track (tier, progress in [MilestoneMath.SCALE] units).
 *   Saves are batched by the manager and guarded by `WHERE tier = ?`, so a late save can never undo a claim.
 * - `milestone_claims`: one row per claimed tier (PK uuid + track + tier). A tier can be claimed once, ever.
 * - `milestone_pending_rewards`: physical rewards that haven't reached an inventory yet (survive restarts).
 * - `milestone_pvp_kills`: last counted kill per killer + victim pair (PvP cooldown across restarts).
 *
 * A claim is one transaction: claim row, next tier, currency deposits and pending reward rows. Any
 * failure rolls all of it back.
 */
class MilestoneStore(private val db: DatabaseManager) {

    data class Row(val key: String, val tier: Int, val progress: Long)

    data class PendingItem(val label: String, val data: String, val amount: Int)

    data class PendingRow(val id: Long, val source: String, val tier: Int, val label: String, val data: String, val amount: Int, val createdAt: Long)

    sealed class ClaimOutcome {
        data class Claimed(val pendingIds: List<Long>) : ClaimOutcome()
        /** That tier was claimed before: nothing was paid. */
        object AlreadyClaimed : ClaimOutcome()
        /** The stored tier isn't the one being claimed (another claim got there first): nothing was paid. */
        object Stale : ClaimOutcome()
    }

    private class Abort(val outcome: ClaimOutcome) : RuntimeException(null, null, false, false)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS milestone_progress (
                uuid TEXT NOT NULL,
                track_key TEXT NOT NULL,
                tier INTEGER NOT NULL DEFAULT 1,
                progress INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, track_key)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS milestone_claims (
                uuid TEXT NOT NULL,
                track_key TEXT NOT NULL,
                tier INTEGER NOT NULL,
                coins REAL NOT NULL DEFAULT 0,
                credits REAL NOT NULL DEFAULT 0,
                rewards TEXT NOT NULL DEFAULT '',
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, track_key, tier)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS milestone_pending_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                source TEXT NOT NULL,
                tier INTEGER NOT NULL,
                label TEXT NOT NULL,
                item_data TEXT NOT NULL,
                amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_milestone_pending_uuid ON milestone_pending_rewards (uuid, id)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS milestone_pvp_kills (
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                killed_at INTEGER NOT NULL,
                PRIMARY KEY (killer, victim)
            )
            """.trimIndent()
        )
    }

    // ── Progress ────────────────────────────────────────────

    /** Stored tracks of [uuid]: key → (tier, progress). Corrupted values are clamped, never thrown. */
    fun load(uuid: UUID): Map<String, Pair<Int, Long>> =
        db.query("SELECT track_key, tier, progress FROM milestone_progress WHERE uuid = ?", uuid.toString()) { rs ->
            val tier = rs.getLong("tier").coerceIn(1L, MilestoneMath.MAX_TIER.toLong()).toInt()
            val progress = rs.getLong("progress").coerceIn(0L, MilestoneMath.MAX_REQUIREMENT * MilestoneMath.SCALE)
            rs.getString("track_key") to (tier to progress)
        }.toMap()

    /**
     * Saves progress for rows whose stored tier still matches (or that don't exist yet). A row whose
     * tier moved on (claimed since the snapshot) is left alone. Returns how many rows were written.
     */
    fun saveProgress(uuid: UUID, rows: List<Row>, now: Long): Int {
        if (rows.isEmpty()) return 0
        var written = 0
        db.transaction {
            for (row in rows) {
                written += db.executeUpdate(
                    """
                    INSERT INTO milestone_progress (uuid, track_key, tier, progress, updated_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(uuid, track_key) DO UPDATE SET progress = excluded.progress, updated_at = excluded.updated_at
                    WHERE milestone_progress.tier = excluded.tier
                    """.trimIndent(),
                    uuid.toString(), row.key, row.tier, row.progress, now
                )
            }
        }
        return written
    }

    // ── Claims ──────────────────────────────────────────────

    /**
     * Claims tier [tier] of [key] for [uuid] in one transaction: records the claim (once ever), moves the
     * stored track to tier + 1 with progress 0 (only if it is still at [tier]), runs [pay] (currency
     * deposits; throwing rolls everything back) and queues [items] as pending rewards.
     */
    fun claim(
        uuid: UUID,
        key: String,
        tier: Int,
        coins: Double,
        credits: Double,
        rewardsText: String,
        items: List<PendingItem>,
        now: Long,
        pay: () -> Unit,
    ): ClaimOutcome {
        val ids = ArrayList<Long>(items.size)
        return try {
            db.transaction {
                val inserted = db.executeUpdate(
                    "INSERT OR IGNORE INTO milestone_claims (uuid, track_key, tier, coins, credits, rewards, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    uuid.toString(), key, tier, coins, credits, rewardsText, now
                )
                if (inserted != 1) throw Abort(ClaimOutcome.AlreadyClaimed)
                val moved = db.executeUpdate(
                    """
                    INSERT INTO milestone_progress (uuid, track_key, tier, progress, updated_at) VALUES (?, ?, ?, 0, ?)
                    ON CONFLICT(uuid, track_key) DO UPDATE SET tier = excluded.tier, progress = 0, updated_at = excluded.updated_at
                    WHERE milestone_progress.tier = ?
                    """.trimIndent(),
                    uuid.toString(), key, MilestoneMath.clampTier(tier + 1), now, tier
                )
                if (moved != 1) throw Abort(ClaimOutcome.Stale)
                pay()
                for (item in items) {
                    val id = db.insertReturningId(
                        "INSERT INTO milestone_pending_rewards (uuid, source, tier, label, item_data, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        uuid.toString(), key, tier, item.label, item.data, item.amount, now
                    ) ?: throw IllegalStateException("pending reward insert returned no id")
                    ids += id.toLong()
                }
            }
            ClaimOutcome.Claimed(ids)
        } catch (abort: Abort) {
            abort.outcome
        }
    }

    fun hasClaimed(uuid: UUID, key: String, tier: Int): Boolean =
        db.queryFirst("SELECT 1 FROM milestone_claims WHERE uuid = ? AND track_key = ? AND tier = ?", uuid.toString(), key, tier) { true } ?: false

    fun claimCount(uuid: UUID): Int =
        db.queryFirst("SELECT COUNT(*) AS n FROM milestone_claims WHERE uuid = ?", uuid.toString()) { it.getInt("n") } ?: 0

    // ── Pending rewards ─────────────────────────────────────

    fun pendingCount(uuid: UUID): Int =
        db.queryFirst("SELECT COUNT(*) AS n FROM milestone_pending_rewards WHERE uuid = ?", uuid.toString()) { it.getInt("n") } ?: 0

    fun listPending(uuid: UUID, offset: Int, limit: Int): List<PendingRow> =
        db.query(
            "SELECT id, source, tier, label, item_data, amount, created_at FROM milestone_pending_rewards WHERE uuid = ? ORDER BY id LIMIT ? OFFSET ?",
            uuid.toString(), limit, offset
        ) { rs -> pendingRow(rs) }

    fun pending(uuid: UUID, id: Long): PendingRow? =
        db.queryFirst(
            "SELECT id, source, tier, label, item_data, amount, created_at FROM milestone_pending_rewards WHERE uuid = ? AND id = ?",
            uuid.toString(), id
        ) { rs -> pendingRow(rs) }

    /** Removes the row (conditional, so a second take of the same row gets null). Call before giving the item. */
    fun takePending(uuid: UUID, id: Long): PendingRow? {
        var taken: PendingRow? = null
        db.transaction {
            val row = pending(uuid, id) ?: return@transaction
            if (db.executeUpdate("DELETE FROM milestone_pending_rewards WHERE uuid = ? AND id = ?", uuid.toString(), id) == 1) taken = row
        }
        return taken
    }

    /** Stores an item (leftover of a delivery, or a new reward) as pending. */
    fun addPending(uuid: UUID, source: String, tier: Int, item: PendingItem, now: Long): Long? =
        db.insertReturningId(
            "INSERT INTO milestone_pending_rewards (uuid, source, tier, label, item_data, amount, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            uuid.toString(), source, tier, item.label, item.data, item.amount, now
        )?.toLong()

    private fun pendingRow(rs: java.sql.ResultSet) = PendingRow(
        rs.getLong("id"), rs.getString("source"), rs.getInt("tier"), rs.getString("label"),
        rs.getString("item_data"), rs.getInt("amount"), rs.getLong("created_at")
    )

    // ── PvP ─────────────────────────────────────────────────

    fun pvpKillsSince(since: Long): List<Triple<UUID, UUID, Long>> =
        db.query("SELECT killer, victim, killed_at FROM milestone_pvp_kills WHERE killed_at >= ?", since) { rs ->
            val killer = runCatching { UUID.fromString(rs.getString("killer")) }.getOrNull()
            val victim = runCatching { UUID.fromString(rs.getString("victim")) }.getOrNull()
            if (killer == null || victim == null) null else Triple(killer, victim, rs.getLong("killed_at"))
        }.filterNotNull()

    fun recordPvpKill(killer: UUID, victim: UUID, at: Long) {
        db.execute(
            "INSERT INTO milestone_pvp_kills (killer, victim, killed_at) VALUES (?, ?, ?) ON CONFLICT(killer, victim) DO UPDATE SET killed_at = excluded.killed_at",
            killer.toString(), victim.toString(), at
        )
    }

    fun prunePvpKills(before: Long): Int = db.executeUpdate("DELETE FROM milestone_pvp_kills WHERE killed_at < ?", before)

    /**
     * Whether two accounts ever shared a connection, from AltManager's `player_connections` (salted
     * hashes only). Null when that table can't be read: the caller doesn't count the kill then.
     */
    fun sharesConnection(a: UUID, b: UUID): Boolean? = try {
        db.queryFirst(
            """
            SELECT 1 FROM player_connections x JOIN player_connections y ON x.connection_hash = y.connection_hash
            WHERE x.uuid = ? AND y.uuid = ? LIMIT 1
            """.trimIndent(),
            a.toString(), b.toString()
        ) { true } ?: false
    } catch (e: Exception) {
        null
    }
}
