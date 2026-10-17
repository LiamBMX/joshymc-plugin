package com.liam.joshymc.communityevent.result

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/** How an event ended. [WINNER] can mean several winners (Mob Waves survivors). */
enum class EventOutcome(val label: String) {
    WINNER("Winner decided"),
    DRAW("Draw"),
    NO_WINNER("No winner"),
    DEFEAT("Defeat"),
    CANCELLED("Cancelled"),
}

/** Where a recorded event stands. Only AWAITING_CONFIRMATION can still change. */
enum class RecordStatus(val label: String) {
    AWAITING_CONFIRMATION("Awaiting confirmation"),
    CONFIRMED("Confirmed"),
    CANCELLED("Cancelled"),
}

/** One payout's delivery state, read from its `purchase_deliveries` row. */
enum class PayoutState(val label: String) {
    /** Not handed to the delivery queue yet, or queued and waiting (offline / full inventory). */
    PENDING("Pending"),
    PROCESSING("Processing"),
    DELIVERED("Delivered"),
    /** Needs an administrator: `/event rewards retry`. */
    FAILED("Failed"),
    CANCELLED("Cancelled"),
}

data class EventRecord(
    val id: Int,
    val uid: String,
    val type: String,
    val arenaId: String?,
    val arenaName: String?,
    val hostUuid: UUID?,
    val hostName: String,
    val createdAt: Long,
    val openedAt: Long?,
    val startedAt: Long?,
    val endedAt: Long,
    val participantCount: Int,
    val detectedOutcome: EventOutcome,
    val outcome: EventOutcome,
    val status: RecordStatus,
    val summary: String?,
    val confirmedByName: String?,
    val confirmedAt: Long?,
)

data class RecordPlayer(val uuid: UUID, val name: String, val placement: Placement?, val detected: Placement?)

data class Payout(
    val id: Int,
    val eventId: Int,
    val recipient: UUID,
    val recipientName: String,
    val position: Int,
    val entry: RewardEntry?,
    val deliveryId: Int?,
    val state: PayoutState,
    val note: String?,
)

data class RewardSummary(val total: Int, val delivered: Int, val failed: Int, val cancelled: Int) {
    val pending: Int get() = total - delivered - failed - cancelled

    fun label(): String = when {
        total == 0 -> "No rewards"
        failed > 0 -> "$failed failed, $delivered/$total delivered"
        delivered == total -> "All $total delivered"
        else -> "$delivered/$total delivered, $pending pending"
    }
}

data class NewRecord(
    val uid: String,
    val sessionId: String,
    val type: String,
    val arenaId: String?,
    val arenaName: String?,
    val hostUuid: UUID?,
    val hostName: String,
    val createdAt: Long,
    val openedAt: Long?,
    val startedAt: Long?,
    val endedAt: Long,
    val participantCount: Int,
    val outcome: EventOutcome,
    val status: RecordStatus,
    val summary: String?,
)

data class NewPlayer(val uuid: UUID, val name: String, val detected: Placement?)

data class HostStats(
    val total: Int,
    val completed: Int,
    val cancelled: Int,
    val participants: Int,
    val byType: Map<String, Int>,
    val last: EventRecord?,
) {
    val average: Double get() = if (total == 0) 0.0 else participants.toDouble() / total
    val favorite: String? get() = byType.maxByOrNull { it.value }?.key
}

data class PlayerStats(
    val joined: Int,
    val completed: Int,
    val wins: Int,
    val winsByType: Map<String, Int>,
    val rewardsReceived: Int,
    val last: EventRecord?,
)

data class HostEntry(val uuid: UUID, val name: String, val events: Int)

/**
 * Event history, results and payouts (issue #1199) in data.db. No Bukkit, like
 * [com.liam.joshymc.manager.PurchaseDeliveryStore], so the state rules can be checked on their own.
 *
 *  - `event_history`: one row per event that opened registration; `id` is the stable event
 *    completion id, `uid` a random UUID for logs.
 *  - `event_history_players`: who took part (one row per player, so a re-registration counts
 *    once) and their placement.
 *  - `event_history_rewards`: the reward lines snapshotted for that event.
 *  - `event_reward_payouts`: one row per recipient per reward line, UNIQUE, linked to the
 *    `purchase_deliveries` row that delivers it.
 *  - `event_result_audit`: confirmations, manual changes and recovery actions.
 *
 * Results change only while AWAITING_CONFIRMATION, and every change is a conditional UPDATE on
 * that status, so a confirmed result can't be rewritten and two confirms can't both win.
 */
class EventHistoryStore(
    private val db: SqliteDatabase,
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Idempotent: safe on every start. */
    fun createSchema() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                session_id TEXT NOT NULL,
                type TEXT NOT NULL,
                arena_id TEXT,
                arena_name TEXT,
                host_uuid TEXT,
                host_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                opened_at INTEGER,
                started_at INTEGER,
                ended_at INTEGER NOT NULL,
                participant_count INTEGER NOT NULL,
                detected_outcome TEXT NOT NULL,
                outcome TEXT NOT NULL,
                status TEXT NOT NULL,
                summary TEXT,
                confirmed_by_uuid TEXT,
                confirmed_by_name TEXT,
                confirmed_at INTEGER,
                announced INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        // History pages sort by id (the rowid), which every index already ends with.
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_history_host ON event_history (host_uuid)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_history_status ON event_history (status)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_history_players (
                event_id INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                placement TEXT,
                detected_placement TEXT,
                PRIMARY KEY (event_id, uuid)
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_history_players_uuid ON event_history_players (uuid)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_history_rewards (
                event_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                placement TEXT NOT NULL,
                reward_type TEXT NOT NULL,
                reward_param TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL,
                PRIMARY KEY (event_id, position)
            )
        """.trimIndent())
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_reward_payouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id INTEGER NOT NULL,
                recipient_uuid TEXT NOT NULL,
                recipient_name TEXT NOT NULL,
                position INTEGER NOT NULL,
                placement TEXT NOT NULL,
                reward_type TEXT NOT NULL,
                reward_param TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL,
                delivery_id INTEGER,
                created_at INTEGER NOT NULL,
                UNIQUE (event_id, recipient_uuid, position)
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_reward_payouts_event ON event_reward_payouts (event_id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_reward_payouts_recipient ON event_reward_payouts (recipient_uuid)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_result_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id INTEGER,
                actor_uuid TEXT,
                actor_name TEXT NOT NULL,
                action TEXT NOT NULL,
                detail TEXT,
                at INTEGER NOT NULL
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_result_audit_event ON event_result_audit (event_id)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_reward_presets (
                type TEXT NOT NULL,
                position INTEGER NOT NULL,
                placement TEXT NOT NULL,
                reward_type TEXT NOT NULL,
                reward_param TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL,
                PRIMARY KEY (type, position)
            )
        """.trimIndent())
    }

    // ── Recording ─────────────────────────────────────────

    /** Writes a finished event, its players and reward snapshot in one transaction. Returns its id. */
    fun insert(record: NewRecord, players: List<NewPlayer>, rewards: List<RewardEntry>): Int {
        var inserted: Int? = null
        db.transaction {
            val id = insertReturningId(
                """INSERT INTO event_history
                   (uid, session_id, type, arena_id, arena_name, host_uuid, host_name, created_at, opened_at, started_at,
                    ended_at, participant_count, detected_outcome, outcome, status, summary)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                arrayOf<Any?>(
                    record.uid, record.sessionId, record.type, record.arenaId, record.arenaName, record.hostUuid?.toString(),
                    record.hostName, record.createdAt, record.openedAt, record.startedAt, record.endedAt, record.participantCount,
                    record.outcome.name, record.outcome.name, record.status.name, record.summary?.take(256),
                )
            ) ?: error("event history insert returned no id")
            for (p in players.distinctBy { it.uuid }) {
                db.execute(
                    "INSERT OR IGNORE INTO event_history_players (event_id, uuid, name, placement, detected_placement) VALUES (?, ?, ?, ?, ?)",
                    id, p.uuid.toString(), p.name.take(64), p.detected?.name, p.detected?.name
                )
            }
            writeRewards(id, rewards)
            inserted = id
        }
        return inserted ?: error("event history insert returned no id")
    }

    private fun writeRewards(eventId: Int, rewards: List<RewardEntry>) {
        rewards.forEachIndexed { i, r ->
            db.execute(
                "INSERT INTO event_history_rewards (event_id, position, placement, reward_type, reward_param, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                eventId, i, r.placement.name, r.kind.id, r.param, r.quantity
            )
        }
    }

    // ── Reading ───────────────────────────────────────────

    fun get(id: Int): EventRecord? = db.queryFirst("SELECT * FROM event_history WHERE id = ?", id) { map(it) }

    /** Results waiting for a host, newest first. */
    fun awaiting(limit: Int): List<EventRecord> = db.query(
        "SELECT * FROM event_history WHERE status = ? ORDER BY id DESC LIMIT ?",
        RecordStatus.AWAITING_CONFIRMATION.name, limit
    ) { map(it) }

    /** One page of history, newest first, optionally only [host]'s events. */
    fun page(offset: Int, limit: Int, host: UUID? = null): List<EventRecord> =
        if (host == null) db.query("SELECT * FROM event_history ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset) { map(it) }
        else db.query("SELECT * FROM event_history WHERE host_uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?", host.toString(), limit, offset) { map(it) }

    fun count(host: UUID? = null): Int =
        if (host == null) db.queryFirst("SELECT COUNT(*) AS n FROM event_history") { it.getInt("n") } ?: 0
        else db.queryFirst("SELECT COUNT(*) AS n FROM event_history WHERE host_uuid = ?", host.toString()) { it.getInt("n") } ?: 0

    fun players(eventId: Int): List<RecordPlayer> = db.query(
        "SELECT * FROM event_history_players WHERE event_id = ? ORDER BY rowid ASC", eventId
    ) { rs ->
        RecordPlayer(
            UUID.fromString(rs.getString("uuid")), rs.getString("name"),
            Placement.of(rs.getString("placement")), Placement.of(rs.getString("detected_placement")),
        )
    }

    /** Winner names per event, for history lists (one query for the whole page). */
    fun winnerNames(eventIds: Collection<Int>): Map<Int, List<String>> {
        if (eventIds.isEmpty()) return emptyMap()
        val rows = db.query(
            "SELECT event_id, name FROM event_history_players WHERE placement = ? AND event_id IN (${eventIds.joinToString(",") { "?" }}) ORDER BY rowid",
            Placement.FIRST.name, *eventIds.toTypedArray()
        ) { it.getInt("event_id") to it.getString("name") }
        return rows.groupBy({ it.first }, { it.second })
    }

    fun rewards(eventId: Int): List<RewardEntry> = db.query(
        "SELECT * FROM event_history_rewards WHERE event_id = ? ORDER BY position ASC", eventId
    ) { mapReward(it) }.filterNotNull()

    // ── Changing an unconfirmed result ────────────────────

    /**
     * Gives [uuid] [placement] (null clears it) on an AWAITING result. A player holds one
     * placement at a time. The outcome follows: any 1st place makes it WINNER; none falls back
     * to the detected non-winner outcome. False when the result is final or [uuid] didn't take part.
     */
    fun setPlacement(eventId: Int, uuid: UUID, placement: Placement?): Boolean {
        var changed = false
        db.transaction {
            if (!isAwaiting(eventId)) return@transaction
            changed = db.executeUpdate(
                "UPDATE event_history_players SET placement = ? WHERE event_id = ? AND uuid = ?",
                placement?.name, eventId, uuid.toString()
            ) == 1
            if (changed) refreshOutcome(eventId)
        }
        return changed
    }

    /** Makes [uuid] the only winner (any other 1st place is cleared). */
    fun setSoleWinner(eventId: Int, uuid: UUID): Boolean {
        var changed = false
        db.transaction {
            if (!isAwaiting(eventId)) return@transaction
            if (db.queryFirst("SELECT 1 FROM event_history_players WHERE event_id = ? AND uuid = ?", eventId, uuid.toString()) { 1 } == null) return@transaction
            db.execute("UPDATE event_history_players SET placement = NULL WHERE event_id = ? AND placement = ?", eventId, Placement.FIRST.name)
            db.execute("UPDATE event_history_players SET placement = ? WHERE event_id = ? AND uuid = ?", Placement.FIRST.name, eventId, uuid.toString())
            refreshOutcome(eventId)
            changed = true
        }
        return changed
    }

    /** Clears every placement on an AWAITING result. */
    fun clearPlacements(eventId: Int): Boolean {
        var changed = false
        db.transaction {
            if (!isAwaiting(eventId)) return@transaction
            db.execute("UPDATE event_history_players SET placement = NULL WHERE event_id = ?", eventId)
            refreshOutcome(eventId)
            changed = true
        }
        return changed
    }

    /** Replaces an AWAITING result's reward snapshot (e.g. with the current preset). */
    fun replaceRewards(eventId: Int, rewards: List<RewardEntry>): Boolean {
        var changed = false
        db.transaction {
            if (!isAwaiting(eventId)) return@transaction
            db.execute("DELETE FROM event_history_rewards WHERE event_id = ?", eventId)
            writeRewards(eventId, rewards)
            changed = true
        }
        return changed
    }

    private fun isAwaiting(eventId: Int): Boolean = db.queryFirst(
        "SELECT 1 FROM event_history WHERE id = ? AND status = ?", eventId, RecordStatus.AWAITING_CONFIRMATION.name
    ) { 1 } != null

    private fun refreshOutcome(eventId: Int) {
        db.execute(
            """UPDATE event_history SET outcome = CASE
                 WHEN EXISTS (SELECT 1 FROM event_history_players WHERE event_id = ? AND placement = ?) THEN ?
                 WHEN detected_outcome = ? THEN ?
                 ELSE detected_outcome END
               WHERE id = ? AND status = ?""",
            eventId, Placement.FIRST.name, EventOutcome.WINNER.name,
            EventOutcome.WINNER.name, EventOutcome.NO_WINNER.name,
            eventId, RecordStatus.AWAITING_CONFIRMATION.name
        )
    }

    // ── Confirmation and payouts ──────────────────────────

    sealed class Confirm {
        data class Confirmed(val payouts: Int) : Confirm()
        /** Already confirmed or cancelled; nothing changed. */
        data object NotAwaiting : Confirm()
    }

    /**
     * AWAITING_CONFIRMATION -> CONFIRMED and one payout row per recipient per reward line, in
     * one transaction. Only one caller can ever move the status, so repeated clicks or two
     * staff confirming at once create the payouts once. [valid] filters out reward lines that
     * no longer pass validation (they are recorded in the audit log instead).
     */
    fun confirm(eventId: Int, actorUuid: UUID?, actorName: String, valid: (RewardEntry) -> String?): Confirm {
        var result: Confirm = Confirm.NotAwaiting
        db.transaction {
            val now = clock()
            val moved = db.executeUpdate(
                "UPDATE event_history SET status = ?, confirmed_by_uuid = ?, confirmed_by_name = ?, confirmed_at = ? WHERE id = ? AND status = ?",
                RecordStatus.CONFIRMED.name, actorUuid?.toString(), actorName, now, eventId, RecordStatus.AWAITING_CONFIRMATION.name
            ) == 1
            if (!moved) return@transaction
            val players = players(eventId)
            var created = 0
            rewards(eventId).forEachIndexed { position, entry ->
                valid(entry)?.let { problem ->
                    audit(eventId, actorUuid, actorName, "REWARD_SKIPPED", "line ${position + 1} (${entry.describe()}): $problem")
                    return@forEachIndexed
                }
                val recipients = if (entry.placement == Placement.PARTICIPANT) players else players.filter { it.placement == entry.placement }
                for (p in recipients) {
                    created += db.executeUpdate(
                        """INSERT OR IGNORE INTO event_reward_payouts
                           (event_id, recipient_uuid, recipient_name, position, placement, reward_type, reward_param, quantity, created_at)
                           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                        eventId, p.uuid.toString(), p.name, position, entry.placement.name, entry.kind.id, entry.param, entry.quantity, now
                    )
                }
            }
            audit(eventId, actorUuid, actorName, "CONFIRM", "$created payout(s) created")
            result = Confirm.Confirmed(created)
        }
        return result
    }

    /** Payouts with their delivery state. [eventId] null = across every event, newest first. */
    fun payouts(eventId: Int?, onlyStates: Set<PayoutState>? = null, limit: Int = 500): List<Payout> {
        val rows = if (eventId != null) db.query(PAYOUT_SELECT + " WHERE p.event_id = ? ORDER BY p.id ASC LIMIT ?", eventId, limit) { mapPayout(it) }
        else db.query(PAYOUT_SELECT + " ORDER BY p.id DESC LIMIT ?", limit) { mapPayout(it) }
        return if (onlyStates == null) rows else rows.filter { it.state in onlyStates }
    }

    /** Failed payouts across every event, newest first (FAILED/INTERRUPTED deliveries). */
    fun failedPayouts(limit: Int): List<Payout> = db.query(
        PAYOUT_SELECT + " WHERE d.state IN ('FAILED', 'INTERRUPTED') ORDER BY p.id DESC LIMIT ?", limit
    ) { mapPayout(it) }

    fun payout(id: Int): Payout? = db.queryFirst(PAYOUT_SELECT + " WHERE p.id = ?", id) { mapPayout(it) }

    /** Payouts of CONFIRMED events not yet handed to the delivery queue (e.g. a crash right after confirming). */
    fun unlinkedPayouts(eventId: Int?, limit: Int): List<Payout> =
        if (eventId != null) db.query(PAYOUT_SELECT + " WHERE p.event_id = ? AND p.delivery_id IS NULL ORDER BY p.id ASC LIMIT ?", eventId, limit) { mapPayout(it) }
        else db.query(PAYOUT_SELECT + " WHERE p.delivery_id IS NULL ORDER BY p.id ASC LIMIT ?", limit) { mapPayout(it) }

    /** Links a payout to its delivery row, once. */
    fun link(payoutId: Int, deliveryId: Int): Boolean = db.executeUpdate(
        "UPDATE event_reward_payouts SET delivery_id = ? WHERE id = ? AND delivery_id IS NULL", deliveryId, payoutId
    ) == 1

    fun rewardSummaries(eventIds: Collection<Int>): Map<Int, RewardSummary> {
        if (eventIds.isEmpty()) return emptyMap()
        return db.query(
            """SELECT p.event_id AS event_id, COUNT(*) AS total,
                 SUM(CASE WHEN d.state = 'COMPLETED' THEN 1 ELSE 0 END) AS delivered,
                 SUM(CASE WHEN d.state IN ('FAILED', 'INTERRUPTED', 'REJECTED') THEN 1 ELSE 0 END) AS failed,
                 SUM(CASE WHEN d.state = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled
               FROM event_reward_payouts p LEFT JOIN purchase_deliveries d ON d.id = p.delivery_id
               WHERE p.event_id IN (${eventIds.joinToString(",") { "?" }}) GROUP BY p.event_id""",
            *eventIds.toTypedArray()
        ) { rs -> rs.getInt("event_id") to RewardSummary(rs.getInt("total"), rs.getInt("delivered"), rs.getInt("failed"), rs.getInt("cancelled")) }
            .toMap()
    }

    /** True the first time only, so a confirmation is announced once. */
    fun markAnnounced(eventId: Int): Boolean =
        db.executeUpdate("UPDATE event_history SET announced = 1 WHERE id = ? AND announced = 0", eventId) == 1

    fun audit(eventId: Int?, actorUuid: UUID?, actorName: String, action: String, detail: String?) {
        db.execute(
            "INSERT INTO event_result_audit (event_id, actor_uuid, actor_name, action, detail, at) VALUES (?, ?, ?, ?, ?, ?)",
            eventId, actorUuid?.toString(), actorName.take(64), action, detail?.take(512), clock()
        )
    }

    // ── Statistics (always computed from history, never counters) ──

    fun hostStats(host: UUID): HostStats {
        val totals = db.queryFirst(
            """SELECT COUNT(*) AS total,
                 SUM(CASE WHEN status = ? THEN 1 ELSE 0 END) AS cancelled,
                 COALESCE(SUM(participant_count), 0) AS participants
               FROM event_history WHERE host_uuid = ?""",
            RecordStatus.CANCELLED.name, host.toString()
        ) { Triple(it.getInt("total"), it.getInt("cancelled"), it.getInt("participants")) } ?: Triple(0, 0, 0)
        val byType = db.query(
            "SELECT type, COUNT(*) AS n FROM event_history WHERE host_uuid = ? GROUP BY type ORDER BY n DESC", host.toString()
        ) { it.getString("type") to it.getInt("n") }.toMap()
        val last = db.queryFirst("SELECT * FROM event_history WHERE host_uuid = ? ORDER BY id DESC LIMIT 1", host.toString()) { map(it) }
        return HostStats(totals.first, totals.first - totals.second, totals.second, totals.third, byType, last)
    }

    /** Everyone who has hosted a recorded event, most events first. */
    fun hosts(limit: Int): List<HostEntry> = db.query(
        """SELECT host_uuid, MAX(host_name) AS name, COUNT(*) AS n FROM event_history
           WHERE host_uuid IS NOT NULL GROUP BY host_uuid ORDER BY n DESC LIMIT ?""",
        limit
    ) { rs -> runCatching { HostEntry(UUID.fromString(rs.getString("host_uuid")), rs.getString("name"), rs.getInt("n")) }.getOrNull() }
        .filterNotNull()

    /**
     * Joined = events [player] took part in once they started; completed = those that reached a
     * result; wins = 1st place in a CONFIRMED event only, so cancelled or unconfirmed events are
     * never victories.
     */
    fun playerStats(player: UUID): PlayerStats {
        val id = player.toString()
        val counts = db.queryFirst(
            """SELECT COUNT(*) AS joined,
                 SUM(CASE WHEN h.status != ? THEN 1 ELSE 0 END) AS completed,
                 SUM(CASE WHEN h.status = ? AND p.placement = ? THEN 1 ELSE 0 END) AS wins
               FROM event_history_players p JOIN event_history h ON h.id = p.event_id WHERE p.uuid = ?""",
            RecordStatus.CANCELLED.name, RecordStatus.CONFIRMED.name, Placement.FIRST.name, id
        ) { Triple(it.getInt("joined"), it.getInt("completed"), it.getInt("wins")) } ?: Triple(0, 0, 0)
        val winsByType = db.query(
            """SELECT h.type AS type, COUNT(*) AS n FROM event_history_players p JOIN event_history h ON h.id = p.event_id
               WHERE p.uuid = ? AND h.status = ? AND p.placement = ? GROUP BY h.type ORDER BY n DESC""",
            id, RecordStatus.CONFIRMED.name, Placement.FIRST.name
        ) { it.getString("type") to it.getInt("n") }.toMap()
        val rewards = db.queryFirst(
            """SELECT COUNT(*) AS n FROM event_reward_payouts p JOIN purchase_deliveries d ON d.id = p.delivery_id
               WHERE p.recipient_uuid = ? AND d.state = 'COMPLETED'""",
            id
        ) { it.getInt("n") } ?: 0
        val last = db.queryFirst(
            "SELECT h.* FROM event_history_players p JOIN event_history h ON h.id = p.event_id WHERE p.uuid = ? ORDER BY h.id DESC LIMIT 1", id
        ) { map(it) }
        return PlayerStats(counts.first, counts.second, counts.third, winsByType, rewards, last)
    }

    // ── Presets ───────────────────────────────────────────

    fun loadPresets(): Map<String, List<RewardEntry>> = db.query(
        "SELECT * FROM event_reward_presets ORDER BY type, position"
    ) { rs -> rs.getString("type") to mapReward(rs) }
        .filter { it.second != null }
        .groupBy({ it.first }, { it.second!! })

    fun savePreset(type: String, entries: List<RewardEntry>) {
        db.transaction {
            db.execute("DELETE FROM event_reward_presets WHERE type = ?", type)
            entries.forEachIndexed { i, r ->
                db.execute(
                    "INSERT INTO event_reward_presets (type, position, placement, reward_type, reward_param, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                    type, i, r.placement.name, r.kind.id, r.param, r.quantity
                )
            }
        }
    }

    // ── Mapping ───────────────────────────────────────────

    private fun mapReward(rs: ResultSet): RewardEntry? {
        val placement = Placement.of(rs.getString("placement")) ?: return null
        val kind = RewardKind.of(rs.getString("reward_type")) ?: return null
        return RewardEntry(placement, kind, rs.getString("reward_param") ?: "", rs.getInt("quantity"))
    }

    private fun mapPayout(rs: ResultSet): Payout {
        val deliveryId = rs.getInt("delivery_id").takeIf { !rs.wasNull() }
        val state = when (rs.getString("d_state")) {
            null -> PayoutState.PENDING
            "PENDING", "UNRESOLVED" -> PayoutState.PENDING
            "PROCESSING" -> PayoutState.PROCESSING
            "COMPLETED" -> PayoutState.DELIVERED
            "CANCELLED" -> PayoutState.CANCELLED
            else -> PayoutState.FAILED
        }
        val note = rs.getString("d_error")
        return Payout(
            id = rs.getInt("id"),
            eventId = rs.getInt("event_id"),
            recipient = UUID.fromString(rs.getString("recipient_uuid")),
            recipientName = rs.getString("recipient_name"),
            position = rs.getInt("position"),
            entry = mapReward(rs),
            deliveryId = deliveryId,
            state = state,
            note = if (rs.getString("d_state") == "INTERRUPTED") "interrupted mid-delivery: check their inventory before retrying" else note,
        )
    }

    private fun map(rs: ResultSet): EventRecord = EventRecord(
        id = rs.getInt("id"),
        uid = rs.getString("uid"),
        type = rs.getString("type"),
        arenaId = rs.getString("arena_id"),
        arenaName = rs.getString("arena_name"),
        hostUuid = rs.getString("host_uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        hostName = rs.getString("host_name"),
        createdAt = rs.getLong("created_at"),
        openedAt = rs.getLong("opened_at").takeIf { !rs.wasNull() },
        startedAt = rs.getLong("started_at").takeIf { !rs.wasNull() },
        endedAt = rs.getLong("ended_at"),
        participantCount = rs.getInt("participant_count"),
        detectedOutcome = runCatching { EventOutcome.valueOf(rs.getString("detected_outcome")) }.getOrDefault(EventOutcome.NO_WINNER),
        outcome = runCatching { EventOutcome.valueOf(rs.getString("outcome")) }.getOrDefault(EventOutcome.NO_WINNER),
        status = runCatching { RecordStatus.valueOf(rs.getString("status")) }.getOrDefault(RecordStatus.CANCELLED),
        summary = rs.getString("summary"),
        confirmedByName = rs.getString("confirmed_by_name"),
        confirmedAt = rs.getLong("confirmed_at").takeIf { !rs.wasNull() },
    )

    private companion object {
        const val PAYOUT_SELECT =
            "SELECT p.*, d.state AS d_state, d.last_error AS d_error FROM event_reward_payouts p LEFT JOIN purchase_deliveries d ON d.id = p.delivery_id"
    }
}
