package com.liam.joshymc.lifesteal.ranked.monitor

import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Issue #1474: the ranked anti-farming review log, Bukkit-free. Counts come from history that
 * already exists (`lifesteal_ranked_matches`, `lifesteal_ranked_participants`,
 * `lifesteal_heart_transactions`, AltManager's `player_connections` through
 * [HeartStore.sharedConnection]); the only new table is the review log itself:
 *
 * - `lifesteal_ranked_reviews`: one row per finding. `dedupe_key` is UNIQUE (kind + subject +
 *   match), so processing the same match again never adds a row, and [record] also skips a
 *   finding whose kind and subject were already logged inside the suppression window, so a
 *   pair that keeps meeting is one entry per window, not one per match.
 *
 * Only UUIDs, team ids/names, match ids and counts are stored: never an address or a connection hash.
 * Nothing here writes hearts, cooldowns, ratings, matches or punishments.
 */
class RankedMonitorStore(private val db: SqliteDatabase) {

    private val hearts = HeartStore(db)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_reviews (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                kind TEXT NOT NULL,
                subject TEXT NOT NULL,
                match_id TEXT NOT NULL,
                players TEXT NOT NULL,
                teams TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_reviews_subject ON lifesteal_ranked_reviews (kind, subject, created_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_reviews_created ON lifesteal_ranked_reviews (created_at)")
        // Lookups by player over the existing history (indexes only; no row is changed).
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_participants_uuid ON lifesteal_ranked_participants (uuid, match_id)")
    }

    private fun tableExists(name: String): Boolean =
        db.queryFirst("SELECT 1 AS hit FROM sqlite_master WHERE type = 'table' AND name = ?", name) { true } == true

    // ── Counts over existing history ────────────────────────

    /** Non-test matches between [teamA] and [teamB] (either order) that reached the fight, created at or after [since]. */
    fun teamMatchCount(teamA: String, teamB: String, since: Long): Int =
        db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_ranked_matches WHERE test = 0 AND started_at IS NOT NULL AND created_at >= ? AND " +
                "((team_a_id = ? AND team_b_id = ?) OR (team_a_id = ? AND team_b_id = ?))",
            since, teamA, teamB, teamB, teamA
        ) { it.getInt("n") } ?: 0

    /**
     * For every pair of [sideA] × [sideB], the non-test matches that reached the fight since
     * [since] in which they stood on opposite sides (whatever team they played for), by
     * [RankedMonitorRules.pairKey]. One query.
     */
    fun playerPairMatches(sideA: List<UUID>, sideB: List<UUID>, since: Long): Map<String, Int> {
        if (sideA.isEmpty() || sideB.isEmpty()) return emptyMap()
        val a = sideA.joinToString(",") { "?" }
        val b = sideB.joinToString(",") { "?" }
        val params = ArrayList<Any?>()
        params += since
        sideA.mapTo(params) { it.toString() }
        sideB.mapTo(params) { it.toString() }
        val rows = db.query(
            "SELECT x.uuid AS a, y.uuid AS b, COUNT(DISTINCT x.match_id) AS n FROM lifesteal_ranked_participants x " +
                "JOIN lifesteal_ranked_participants y ON y.match_id = x.match_id AND y.side <> x.side " +
                "JOIN lifesteal_ranked_matches m ON m.match_id = x.match_id " +
                "WHERE m.test = 0 AND m.started_at IS NOT NULL AND m.created_at >= ? AND x.uuid IN ($a) AND y.uuid IN ($b) " +
                "GROUP BY x.uuid, y.uuid",
            *params.toTypedArray()
        ) { rs -> RankedMonitorRules.pairKey(rs.getString("a"), rs.getString("b")) to rs.getInt("n") }
        return rows.toMap()
    }

    /** Hearts [killer] took from [victim] since [since], every PvP path together (open world, Combat NPC, ranked kill, forfeit). */
    fun transferCount(killer: UUID, victim: UUID, since: Long): Int {
        if (!tableExists("lifesteal_heart_transactions")) return 0
        return db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE type = 'PVP' AND outcome IN ('TRANSFERRED', 'VICTIM_LOST_ONLY') " +
                "AND killer = ? AND victim = ? AND created_at >= ?",
            killer.toString(), victim.toString(), since
        ) { it.getInt("n") } ?: 0
    }

    /** Ranked matches [uuid] forfeited by a counted disconnect since [since]. */
    fun forfeitCount(uuid: UUID, since: Long): Int =
        db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_ranked_participants p JOIN lifesteal_ranked_matches m ON m.match_id = p.match_id " +
                "WHERE p.uuid = ? AND p.defeat_reason = 'DISCONNECT' AND m.created_at >= ?",
            uuid.toString(), since
        ) { it.getInt("n") } ?: 0

    /** AltManager's shared-connection evidence (salted hashes compared in SQL; nothing about the connection is read out). */
    fun sharedConnection(a: UUID, b: UUID): Boolean = hearts.sharedConnection(a, b)

    /** Everything [RankedMonitorRules.evaluate] needs for [facts], read inside the window ending at [now]. */
    fun counts(facts: RankedMonitorRules.MatchFacts, settings: RankedMonitorSettings, now: Long): RankedMonitorRules.Counts {
        val since = now - settings.windowMillis
        val pairs = RankedMonitorRules.opponentPairs(facts)
        return RankedMonitorRules.Counts(
            teamMatches = if (settings.repeatedTeamMatches > 0) teamMatchCount(facts.teamAId, facts.teamBId, since) else 0,
            playerPairMatches = if (settings.repeatedPlayerMatches > 0) playerPairMatches(facts.sideA, facts.sideB, since) else emptyMap(),
            pairTransfers = if (settings.repeatedHeartTransfers > 0) facts.transfers.distinct().associate { (k, v) -> RankedMonitorRules.directedKey(k, v) to transferCount(k, v, since) } else emptyMap(),
            forfeits = if (settings.repeatedForfeits > 0) facts.forfeited.distinct().associateWith { forfeitCount(it, since) } else emptyMap(),
            sharedPairs = if (settings.sharedConnections) pairs.filter { (a, b) -> sharedConnection(a, b) }.map { (a, b) -> RankedMonitorRules.pairKey(a, b) }.toSet() else emptySet(),
        )
    }

    // ── Review log ──────────────────────────────────────────

    data class Review(
        val id: Long,
        val kind: String,
        val subject: String,
        val matchId: String,
        val players: List<UUID>,
        val teams: List<String>,
        val reason: String,
        val at: Long,
    )

    /**
     * Stores [finding] for [matchId] once. False (nothing written) when this match already logged
     * it, or the same kind + subject was logged within [suppressMillis] before [now].
     */
    fun record(finding: RankedMonitorRules.Finding, matchId: String, now: Long, suppressMillis: Long): Boolean {
        var inserted = false
        db.transaction {
            val recent = db.queryFirst(
                "SELECT 1 AS hit FROM lifesteal_ranked_reviews WHERE kind = ? AND subject = ? AND created_at > ? LIMIT 1",
                finding.kind.name, finding.subject, now - suppressMillis
            ) { true } == true
            if (recent) return@transaction
            inserted = db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_ranked_reviews (dedupe_key, kind, subject, match_id, players, teams, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                finding.dedupeKey(matchId), finding.kind.name, finding.subject, matchId,
                finding.players.joinToString(","), finding.teams.joinToString(","), finding.reason.take(300), now
            ) == 1
        }
        return inserted
    }

    /** Newest first; with [player], only reviews that involve them. */
    fun reviews(player: UUID? = null, limit: Int = 15): List<Review> =
        if (player == null) db.query("SELECT * FROM lifesteal_ranked_reviews ORDER BY created_at DESC, id DESC LIMIT ?", limit) { review(it) }
        else db.query(
            "SELECT * FROM lifesteal_ranked_reviews WHERE players LIKE ? ORDER BY created_at DESC, id DESC LIMIT ?",
            "%$player%", limit
        ) { review(it) }

    fun reviewCount(): Int = db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_ranked_reviews") { it.getInt("n") } ?: 0

    private fun review(rs: ResultSet) = Review(
        id = rs.getLong("id"),
        kind = rs.getString("kind"),
        subject = rs.getString("subject"),
        matchId = rs.getString("match_id"),
        players = rs.getString("players").split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() },
        teams = rs.getString("teams").split(',').filter { it.isNotBlank() },
        reason = rs.getString("reason"),
        at = rs.getLong("created_at"),
    )
}
