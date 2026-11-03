package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistent Ranked Team Wars data (issue #1361), Bukkit-free, in the Lifesteal server's own
 * `data.db` (tables created only where Lifesteal is hosted).
 *
 * - `lifesteal_ranked_arenas`: arena definitions. Deleting one is a soft delete (the row stays for
 *   match history) and is refused while a match that isn't over uses it. Edits are conditional on
 *   `version`.
 * - `lifesteal_ranked_matches`: one row per match (the match record), PK = stable match id. A
 *   partial UNIQUE index allows one match that isn't over per arena. Every state change is a
 *   conditional UPDATE on the current state, so a transition happens once.
 * - `lifesteal_ranked_participants`: one row per player per match. `open = 1` while the match isn't
 *   over; a partial UNIQUE index on `uuid WHERE open = 1` keeps a player in one match at a time.
 * - `lifesteal_ranked_snapshots`: the pre-match inventory, equipment and return location of each
 *   participant, written in the same transaction as the match. UNIQUE (match, player) means a
 *   snapshot is never overwritten, a partial UNIQUE index allows one PENDING snapshot per player,
 *   and PENDING → RESTORED is a conditional UPDATE, so it is restored once.
 * - `lifesteal_ranked_events`: what happened (state changes, deaths, disconnects, restorations).
 *
 * Only UUIDs, team ids/names and coordinates are stored: no player names, IPs or chat.
 */
class RankedStore(private val db: SqliteDatabase) {

    class StaleWriteException(message: String) : RuntimeException(message)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_arenas (
                arena_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                world TEXT NOT NULL,
                min_x INTEGER, min_y INTEGER, min_z INTEGER,
                max_x INTEGER, max_y INTEGER, max_z INTEGER,
                team_a TEXT NOT NULL DEFAULT '',
                team_b TEXT NOT NULL DEFAULT '',
                spectators TEXT NOT NULL DEFAULT '',
                sizes TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 0,
                deleted INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_matches (
                match_id TEXT PRIMARY KEY,
                season_id TEXT NOT NULL,
                arena_id TEXT NOT NULL,
                team_a_id TEXT NOT NULL,
                team_a_name TEXT NOT NULL,
                team_b_id TEXT NOT NULL,
                team_b_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                test INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                started_at INTEGER,
                ended_at INTEGER,
                winner_side TEXT,
                winner_team_id TEXT,
                outcome TEXT,
                cancel_reason TEXT,
                restoration TEXT NOT NULL DEFAULT 'PENDING',
                version INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_ranked_matches_open_arena ON lifesteal_ranked_matches(arena_id) WHERE state NOT IN ('COMPLETED', 'CANCELLED')")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_matches_created ON lifesteal_ranked_matches(created_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_participants (
                match_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                side TEXT NOT NULL,
                team_id TEXT NOT NULL,
                status TEXT NOT NULL,
                defeat_reason TEXT,
                defeated_at INTEGER,
                open INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (match_id, uuid)
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_ranked_participants_open ON lifesteal_ranked_participants(uuid) WHERE open = 1")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_snapshots (
                snapshot_id TEXT PRIMARY KEY,
                match_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                contents TEXT NOT NULL,
                slot_count INTEGER NOT NULL,
                held_slot INTEGER NOT NULL,
                return_world TEXT,
                return_x REAL, return_y REAL, return_z REAL, return_yaw REAL, return_pitch REAL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                restored_at INTEGER,
                restore_reason TEXT,
                UNIQUE (match_id, uuid)
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_ranked_snapshots_pending ON lifesteal_ranked_snapshots(uuid) WHERE status = 'PENDING'")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                match_id TEXT NOT NULL,
                at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                uuid TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_events_match ON lifesteal_ranked_events(match_id, id)")
    }

    // ── Arenas ──────────────────────────────────────────────

    /** A stored arena plus why it couldn't be read completely (a malformed point list). */
    data class LoadedArena(val arena: RankedArena, val problem: String?)

    fun arenas(): List<LoadedArena> = db.query("SELECT * FROM lifesteal_ranked_arenas WHERE deleted = 0 ORDER BY arena_id") { rs -> readArena(rs) }

    fun arena(id: String): LoadedArena? = db.queryFirst("SELECT * FROM lifesteal_ranked_arenas WHERE arena_id = ? AND deleted = 0", id) { rs -> readArena(rs) }

    private fun readArena(rs: ResultSet): LoadedArena {
        val problems = mutableListOf<String>()
        fun points(column: String): List<Point> = Point.decodeList(rs.getString(column)) ?: run {
            problems += "the stored $column points are malformed"
            emptyList()
        }
        val minX = rs.getObject("min_x")
        val bounds = if (minX == null) null else Box(
            rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"), rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
        )
        val sizesRaw = rs.getString("sizes") ?: ""
        val sizes = RankedArenaRules.parseSizes(sizesRaw) ?: run {
            problems += "the stored sizes '$sizesRaw' are malformed"
            emptySet()
        }
        val arena = RankedArena(
            id = rs.getString("arena_id"),
            displayName = rs.getString("display_name"),
            world = rs.getString("world"),
            bounds = bounds,
            teamA = points("team_a"),
            teamB = points("team_b"),
            spectators = points("spectators"),
            sizes = sizes,
            enabled = rs.getInt("enabled") == 1,
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            version = rs.getLong("version"),
        )
        return LoadedArena(arena, problems.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /** Creates [arena] (reusing a soft-deleted id). False when an arena with that id exists. */
    fun createArena(arena: RankedArena, now: Long): Boolean {
        var created = false
        db.transaction {
            val existing = db.queryFirst("SELECT deleted FROM lifesteal_ranked_arenas WHERE arena_id = ?", arena.id) { it.getInt("deleted") }
            if (existing == 0) return@transaction
            if (existing == null) {
                db.execute(
                    "INSERT INTO lifesteal_ranked_arenas (arena_id, display_name, world, sizes, enabled, deleted, created_at, updated_at, version) VALUES (?, ?, ?, ?, 0, 0, ?, ?, 1)",
                    arena.id, arena.displayName, arena.world, encodeSizes(arena.sizes), now, now,
                )
            } else {
                db.executeUpdate(
                    """UPDATE lifesteal_ranked_arenas SET display_name = ?, world = ?, min_x = NULL, min_y = NULL, min_z = NULL, max_x = NULL, max_y = NULL, max_z = NULL,
                       team_a = '', team_b = '', spectators = '', sizes = ?, enabled = 0, deleted = 0, created_at = ?, updated_at = ?, version = version + 1
                       WHERE arena_id = ? AND deleted = 1""",
                    arena.displayName, arena.world, encodeSizes(arena.sizes), now, now, arena.id,
                )
            }
            created = true
        }
        return created
    }

    /** Saves every editable field of [arena] if it is still at [RankedArena.version]; returns the new version. */
    fun updateArena(arena: RankedArena, now: Long): Long {
        val b = arena.bounds
        val changed = db.executeUpdate(
            """UPDATE lifesteal_ranked_arenas SET display_name = ?, world = ?, min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ?,
               team_a = ?, team_b = ?, spectators = ?, sizes = ?, enabled = ?, updated_at = ?, version = version + 1
               WHERE arena_id = ? AND deleted = 0 AND version = ?""",
            arena.displayName, arena.world, b?.minX, b?.minY, b?.minZ, b?.maxX, b?.maxY, b?.maxZ,
            Point.encodeList(arena.teamA), Point.encodeList(arena.teamB), Point.encodeList(arena.spectators),
            encodeSizes(arena.sizes), if (arena.enabled) 1 else 0, now, arena.id, arena.version,
        )
        if (changed != 1) throw StaleWriteException("arena ${arena.id} changed or was deleted meanwhile")
        return arena.version + 1
    }

    enum class DeleteResult { DELETED, NOT_FOUND, IN_USE }

    /** Soft-deletes [id], refused (inside the same statement) while a match that isn't over uses it. */
    fun deleteArena(id: String, now: Long): DeleteResult {
        var result = DeleteResult.NOT_FOUND
        db.transaction {
            val exists = db.queryFirst("SELECT 1 FROM lifesteal_ranked_arenas WHERE arena_id = ? AND deleted = 0", id) { true } == true
            if (!exists) return@transaction
            val changed = db.executeUpdate(
                """UPDATE lifesteal_ranked_arenas SET deleted = 1, enabled = 0, updated_at = ?, version = version + 1
                   WHERE arena_id = ? AND deleted = 0
                   AND NOT EXISTS (SELECT 1 FROM lifesteal_ranked_matches WHERE arena_id = ? AND state NOT IN ('COMPLETED', 'CANCELLED'))""",
                now, id, id,
            )
            result = if (changed == 1) DeleteResult.DELETED else DeleteResult.IN_USE
        }
        return result
    }

    private fun encodeSizes(sizes: Set<Int>): String = sizes.sorted().joinToString(",")

    // ── Matches ─────────────────────────────────────────────

    data class NewMatch(
        val id: String,
        val seasonId: String,
        val arenaId: String,
        val teamAId: String,
        val teamAName: String,
        val teamBId: String,
        val teamBName: String,
        val size: Int,
        val test: Boolean,
        val createdBy: String,
        val createdAt: Long,
    )

    /** One participant's pre-match state. [slots] are opaque serialized item stacks (null = empty slot). */
    data class SnapshotData(
        val uuid: UUID,
        val slots: List<String?>,
        val heldSlot: Int,
        val returnWorld: String?,
        val returnPoint: Point?,
    )

    data class Snapshot(
        val id: String,
        val matchId: String,
        val uuid: UUID,
        val slots: List<String?>,
        val heldSlot: Int,
        val returnWorld: String?,
        val returnPoint: Point?,
        val status: String,
        val createdAt: Long,
    )

    /**
     * Records a validated match as PREPARING, its participants and every snapshot in one
     * transaction, before anything is done to a player. Throws (and writes nothing) when the arena
     * or a player is already held by another match, or a player still has a PENDING snapshot.
     */
    fun insertMatch(match: NewMatch, participants: List<MatchParticipant>, snapshots: List<SnapshotData>) {
        require(participants.map { it.uuid }.toSet() == snapshots.map { it.uuid }.toSet()) { "every participant needs exactly one snapshot" }
        db.transaction {
            db.execute(
                """INSERT INTO lifesteal_ranked_matches (match_id, season_id, arena_id, team_a_id, team_a_name, team_b_id, team_b_name, size, test, state, created_by, created_at, restoration, version)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, 'PENDING', 1)""",
                match.id, match.seasonId, match.arenaId, match.teamAId, match.teamAName, match.teamBId, match.teamBName,
                match.size, if (match.test) 1 else 0, match.createdBy, match.createdAt,
            )
            for (p in participants) {
                db.execute(
                    "INSERT INTO lifesteal_ranked_participants (match_id, uuid, side, team_id, status, open) VALUES (?, ?, ?, ?, 'ACTIVE', 1)",
                    match.id, p.uuid.toString(), p.side.name, p.teamId,
                )
            }
            for (s in snapshots) {
                db.execute(
                    """INSERT INTO lifesteal_ranked_snapshots (snapshot_id, match_id, uuid, contents, slot_count, held_slot, return_world, return_x, return_y, return_z, return_yaw, return_pitch, status, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)""",
                    snapshotId(match.id, s.uuid), match.id, s.uuid.toString(), encodeSlots(s.slots), s.slots.size, s.heldSlot,
                    s.returnWorld, s.returnPoint?.x, s.returnPoint?.y, s.returnPoint?.z, s.returnPoint?.yaw?.toDouble(), s.returnPoint?.pitch?.toDouble(),
                    match.createdAt,
                )
            }
            insertEvent(match.id, match.createdAt, "CREATED", null, "${match.teamAName} vs ${match.teamBName}, ${match.size}v${match.size}, arena ${match.arenaId}${if (match.test) ", test" else ""}")
            insertEvent(match.id, match.createdAt, "STATE", null, "CREATED -> VALIDATING -> PREPARING")
        }
    }

    /** Conditional transition [from] → [to] (checked against [MatchState.canMoveTo] too). */
    fun moveState(matchId: String, from: MatchState, to: MatchState, now: Long): Boolean {
        require(from.canMoveTo(to)) { "$from -> $to is not a valid transition" }
        require(to != MatchState.COMPLETED && to != MatchState.CANCELLED && to != MatchState.FINISHING) { "use complete/cancel/finish" }
        var moved = false
        db.transaction {
            val started = if (to == MatchState.ACTIVE) now else null
            moved = db.executeUpdate(
                "UPDATE lifesteal_ranked_matches SET state = ?, started_at = COALESCE(?, started_at), version = version + 1 WHERE match_id = ? AND state = ?",
                to.name, started, matchId, from.name,
            ) == 1
            if (moved) insertEvent(matchId, now, "STATE", null, "$from -> $to")
        }
        return moved
    }

    /** ACTIVE → FINISHING with the winner recorded, once. */
    fun finish(matchId: String, winner: Side, winnerTeamId: String, now: Long): Boolean {
        var moved = false
        db.transaction {
            moved = db.executeUpdate(
                "UPDATE lifesteal_ranked_matches SET state = 'FINISHING', winner_side = ?, winner_team_id = ?, version = version + 1 WHERE match_id = ? AND state = 'ACTIVE'",
                winner.name, winnerTeamId, matchId,
            ) == 1
            if (moved) insertEvent(matchId, now, "STATE", null, "ACTIVE -> FINISHING, winner ${winner.name} ($winnerTeamId)")
        }
        return moved
    }

    /** FINISHING → COMPLETED (outcome WIN), releasing the players and the arena, once. */
    fun complete(matchId: String, now: Long): Boolean {
        var moved = false
        db.transaction {
            moved = db.executeUpdate(
                "UPDATE lifesteal_ranked_matches SET state = 'COMPLETED', outcome = 'WIN', ended_at = ?, version = version + 1 WHERE match_id = ? AND state = 'FINISHING'",
                now, matchId,
            ) == 1
            if (moved) {
                db.execute("UPDATE lifesteal_ranked_participants SET open = 0 WHERE match_id = ?", matchId)
                insertEvent(matchId, now, "STATE", null, "FINISHING -> COMPLETED")
            }
        }
        return moved
    }

    /**
     * Any state before FINISHING → CANCELLED with [reason] (no winner), releasing the players and
     * the arena, once. A FINISHING match already has its winner and is completed instead.
     */
    fun cancel(matchId: String, reason: String, now: Long): Boolean {
        var moved = false
        db.transaction {
            val from = db.queryFirst("SELECT state FROM lifesteal_ranked_matches WHERE match_id = ?", matchId) { MatchState.parse(it.getString("state")) }
            if (from == null || !from.canMoveTo(MatchState.CANCELLED)) return@transaction
            moved = db.executeUpdate(
                "UPDATE lifesteal_ranked_matches SET state = 'CANCELLED', outcome = 'CANCELLED', cancel_reason = ?, ended_at = ?, version = version + 1 WHERE match_id = ? AND state = ?",
                reason.take(200), now, matchId, from.name,
            ) == 1
            if (moved) {
                db.execute("UPDATE lifesteal_ranked_participants SET open = 0 WHERE match_id = ?", matchId)
                insertEvent(matchId, now, "STATE", null, "$from -> CANCELLED: ${reason.take(200)}")
            }
        }
        return moved
    }

    /** Marks a still-fighting participant defeated, once. */
    fun defeat(matchId: String, uuid: UUID, reason: DefeatReason, now: Long): Boolean {
        var changed = false
        db.transaction {
            changed = db.executeUpdate(
                "UPDATE lifesteal_ranked_participants SET status = 'DEFEATED', defeat_reason = ?, defeated_at = ? WHERE match_id = ? AND uuid = ? AND status = 'ACTIVE'",
                reason.name, now, matchId, uuid.toString(),
            ) == 1
            if (changed) insertEvent(matchId, now, "DEFEAT", uuid, reason.name)
        }
        return changed
    }

    data class Recovered(val matchId: String, val from: MatchState, val to: MatchState)

    /**
     * Startup: no match survives a restart. A FINISHING match already has its winner and is
     * completed; every other open match is cancelled. Snapshots stay PENDING and are restored when
     * each player is next online, so inventory integrity wins over resuming a fight.
     */
    fun recoverInterrupted(now: Long): List<Recovered> {
        val recovered = mutableListOf<Recovered>()
        db.transaction {
            val open = db.query("SELECT match_id, state FROM lifesteal_ranked_matches WHERE state NOT IN ('COMPLETED', 'CANCELLED')") {
                it.getString("match_id") to MatchState.parse(it.getString("state"))
            }
            for ((id, state) in open) {
                if (state == MatchState.FINISHING) {
                    db.executeUpdate("UPDATE lifesteal_ranked_matches SET state = 'COMPLETED', outcome = 'WIN', ended_at = ?, version = version + 1 WHERE match_id = ? AND state = 'FINISHING'", now, id)
                    insertEvent(id, now, "RECOVERED", null, "FINISHING -> COMPLETED after a restart (winner kept)")
                    recovered += Recovered(id, MatchState.FINISHING, MatchState.COMPLETED)
                } else {
                    val reason = "server restarted while ${state?.name ?: "unknown"}"
                    db.executeUpdate(
                        "UPDATE lifesteal_ranked_matches SET state = 'CANCELLED', outcome = 'CANCELLED', cancel_reason = ?, ended_at = ?, version = version + 1 WHERE match_id = ? AND state NOT IN ('COMPLETED', 'CANCELLED')",
                        reason, now, id,
                    )
                    insertEvent(id, now, "RECOVERED", null, "${state?.name ?: "unknown"} -> CANCELLED after a restart")
                    recovered += Recovered(id, state ?: MatchState.CREATED, MatchState.CANCELLED)
                }
                db.execute("UPDATE lifesteal_ranked_participants SET open = 0 WHERE match_id = ?", id)
            }
        }
        return recovered
    }

    // ── Snapshots ───────────────────────────────────────────

    fun snapshotId(matchId: String, uuid: UUID): String = "$matchId:$uuid"

    fun pendingSnapshot(uuid: UUID): Snapshot? =
        db.queryFirst("SELECT * FROM lifesteal_ranked_snapshots WHERE uuid = ? AND status = 'PENDING'", uuid.toString()) { readSnapshot(it) }

    fun snapshot(id: String): Snapshot? = db.queryFirst("SELECT * FROM lifesteal_ranked_snapshots WHERE snapshot_id = ?", id) { readSnapshot(it) }

    fun pendingSnapshotOwners(): Set<UUID> =
        db.query("SELECT uuid FROM lifesteal_ranked_snapshots WHERE status = 'PENDING'") { UUID.fromString(it.getString("uuid")) }.toSet()

    fun snapshotsOf(matchId: String): List<Snapshot> =
        db.query("SELECT * FROM lifesteal_ranked_snapshots WHERE match_id = ? ORDER BY uuid", matchId) { readSnapshot(it) }

    /**
     * PENDING → RESTORED once (false if it was already restored). Marks the match's restoration
     * COMPLETE when it was the last pending snapshot.
     */
    fun markRestored(snapshotId: String, reason: String, now: Long): Boolean {
        var changed = false
        db.transaction {
            val matchId = db.queryFirst("SELECT match_id FROM lifesteal_ranked_snapshots WHERE snapshot_id = ?", snapshotId) { it.getString("match_id") } ?: return@transaction
            val uuid = db.queryFirst("SELECT uuid FROM lifesteal_ranked_snapshots WHERE snapshot_id = ?", snapshotId) { it.getString("uuid") }
            changed = db.executeUpdate(
                "UPDATE lifesteal_ranked_snapshots SET status = 'RESTORED', restored_at = ?, restore_reason = ? WHERE snapshot_id = ? AND status = 'PENDING'",
                now, reason.take(100), snapshotId,
            ) == 1
            if (!changed) return@transaction
            insertEvent(matchId, now, "RESTORED", uuid?.let(UUID::fromString), reason.take(100))
            db.executeUpdate(
                "UPDATE lifesteal_ranked_matches SET restoration = 'COMPLETE' WHERE match_id = ? AND NOT EXISTS (SELECT 1 FROM lifesteal_ranked_snapshots WHERE match_id = ? AND status = 'PENDING')",
                matchId, matchId,
            )
        }
        return changed
    }

    private fun readSnapshot(rs: ResultSet): Snapshot {
        val world = rs.getString("return_world")
        val x = rs.getObject("return_x")
        val point = if (world == null || x == null) null else Point(
            rs.getDouble("return_x"), rs.getDouble("return_y"), rs.getDouble("return_z"),
            rs.getDouble("return_yaw").toFloat(), rs.getDouble("return_pitch").toFloat(),
        )
        val slots = decodeSlots(rs.getString("contents"), rs.getInt("slot_count"))
        return Snapshot(
            id = rs.getString("snapshot_id"),
            matchId = rs.getString("match_id"),
            uuid = UUID.fromString(rs.getString("uuid")),
            slots = slots,
            heldSlot = rs.getInt("held_slot"),
            returnWorld = world,
            returnPoint = point,
            status = rs.getString("status"),
            createdAt = rs.getLong("created_at"),
        )
    }

    // ── Records ─────────────────────────────────────────────

    data class MatchRecord(
        val id: String,
        val seasonId: String,
        val arenaId: String,
        val teamAId: String,
        val teamAName: String,
        val teamBId: String,
        val teamBName: String,
        val size: Int,
        val test: Boolean,
        val state: MatchState?,
        val createdBy: String,
        val createdAt: Long,
        val startedAt: Long?,
        val endedAt: Long?,
        val winner: Side?,
        val winnerTeamId: String?,
        val outcome: String?,
        val cancelReason: String?,
        val restoration: String,
    )

    data class ParticipantRecord(val uuid: UUID, val side: Side, val teamId: String, val status: ParticipantStatus, val defeatReason: DefeatReason?, val open: Boolean)

    data class EventRow(val at: Long, val kind: String, val uuid: UUID?, val detail: String?)

    fun match(id: String): MatchRecord? = db.queryFirst("SELECT * FROM lifesteal_ranked_matches WHERE match_id = ?", id) { readMatch(it) }

    fun recentMatches(limit: Int): List<MatchRecord> =
        db.query("SELECT * FROM lifesteal_ranked_matches ORDER BY created_at DESC, match_id DESC LIMIT ?", limit) { readMatch(it) }

    fun participants(matchId: String): List<ParticipantRecord> =
        db.query("SELECT * FROM lifesteal_ranked_participants WHERE match_id = ? ORDER BY side, uuid", matchId) { rs ->
            ParticipantRecord(
                UUID.fromString(rs.getString("uuid")),
                Side.valueOf(rs.getString("side")),
                rs.getString("team_id"),
                ParticipantStatus.valueOf(rs.getString("status")),
                rs.getString("defeat_reason")?.let { r -> DefeatReason.entries.firstOrNull { it.name == r } },
                rs.getInt("open") == 1,
            )
        }

    fun events(matchId: String, limit: Int): List<EventRow> =
        db.query("SELECT * FROM lifesteal_ranked_events WHERE match_id = ? ORDER BY id DESC LIMIT ?", matchId, limit) { rs ->
            EventRow(rs.getLong("at"), rs.getString("kind"), rs.getString("uuid")?.let(UUID::fromString), rs.getString("detail"))
        }.reversed()

    fun openMatchIds(): List<String> = db.query("SELECT match_id FROM lifesteal_ranked_matches WHERE state NOT IN ('COMPLETED', 'CANCELLED')") { it.getString("match_id") }

    private fun readMatch(rs: ResultSet) = MatchRecord(
        id = rs.getString("match_id"),
        seasonId = rs.getString("season_id"),
        arenaId = rs.getString("arena_id"),
        teamAId = rs.getString("team_a_id"),
        teamAName = rs.getString("team_a_name"),
        teamBId = rs.getString("team_b_id"),
        teamBName = rs.getString("team_b_name"),
        size = rs.getInt("size"),
        test = rs.getInt("test") == 1,
        state = MatchState.parse(rs.getString("state")),
        createdBy = rs.getString("created_by"),
        createdAt = rs.getLong("created_at"),
        startedAt = (rs.getObject("started_at") as? Number)?.toLong(),
        endedAt = (rs.getObject("ended_at") as? Number)?.toLong(),
        winner = rs.getString("winner_side")?.let { Side.valueOf(it) },
        winnerTeamId = rs.getString("winner_team_id"),
        outcome = rs.getString("outcome"),
        cancelReason = rs.getString("cancel_reason"),
        restoration = rs.getString("restoration"),
    )

    /** Appends one event row (callers queue this through `databaseManager.async` where it isn't part of a transition). */
    fun logEvent(matchId: String, kind: String, uuid: UUID?, detail: String?, now: Long) = insertEvent(matchId, now, kind, uuid, detail)

    private fun insertEvent(matchId: String, now: Long, kind: String, uuid: UUID?, detail: String?) {
        db.execute("INSERT INTO lifesteal_ranked_events (match_id, at, kind, uuid, detail) VALUES (?, ?, ?, ?, ?)", matchId, now, kind, uuid?.toString(), detail?.take(300))
    }

    companion object {
        /** Slots joined by ',' (serialized stacks are Base64, so they never contain one); an empty entry is an empty slot. */
        fun encodeSlots(slots: List<String?>): String = slots.joinToString(",") { it ?: "" }

        fun decodeSlots(raw: String?, count: Int): List<String?> {
            val parts = if (raw.isNullOrEmpty()) List(count) { "" } else raw.split(',')
            require(parts.size == count) { "snapshot has ${parts.size} slots, expected $count" }
            return parts.map { it.ifEmpty { null } }
        }
    }
}
