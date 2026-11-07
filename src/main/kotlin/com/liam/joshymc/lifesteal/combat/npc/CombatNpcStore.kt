package com.liam.joshymc.lifesteal.combat.npc

import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.Outcome
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules.State
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Persistent Combat NPC sessions (issue #1402), Bukkit-free, in the Lifesteal server's own
 * `data.db` (created only where Lifesteal is hosted).
 *
 * The row is the only place a combat logger's items live while they are offline: the player's own
 * data file is emptied in the same quit handler that inserts the row. A partial UNIQUE index allows
 * one open (not COMPLETE) session per player, so repeated quit handlers, reloads or reconnect races
 * can never create a second NPC. Every state change is a conditional UPDATE on the expected state
 * ([CombatNpcRules]); the methods return whether this call won.
 */
class CombatNpcStore(private val db: SqliteDatabase) {

    data class Point(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float, val pitch: Float)

    /** The owner's state as the NPC holds it (at capture, at the last save, or at the end). */
    data class Snapshot(
        val location: Point,
        val health: Double,
        /** The owner's MAX_HEALTH base value (hearts x 2); item and effect modifiers come on top again. */
        val maxHealthBase: Double,
        val absorption: Double,
        val fireTicks: Int,
        val air: Int,
        /** [CombatNpcRules.INVENTORY_SIZE] Base64 blobs (null = empty slot). */
        val inventory: List<String?>,
        val heldSlot: Int,
        val xpLevel: Int,
        val xpProgress: Float,
        val xpTotal: Int,
        val effects: List<CombatNpcRules.Effect>,
        /** When [effects]' durations were measured. */
        val effectsAt: Long,
    )

    data class Session(
        val id: String,
        val owner: UUID,
        val ownerName: String,
        val state: State,
        val outcome: Outcome?,
        val createdAt: Long,
        val expiresAt: Long,
        val endedAt: Long?,
        val tagExpiresAt: Long?,
        val textures: String?,
        val texturesSignature: String?,
        val snapshot: Snapshot,
        val killer: UUID?,
        val killerName: String?,
        val deathCause: String?,
        val deathMessage: String?,
        val dropsDone: Boolean,
        val updatedAt: Long,
    )

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_combat_npcs (
                id TEXT PRIMARY KEY,
                owner TEXT NOT NULL,
                owner_name TEXT NOT NULL,
                state TEXT NOT NULL,
                outcome TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                ended_at INTEGER,
                tag_expires_at INTEGER,
                textures TEXT,
                textures_signature TEXT,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL,
                health REAL NOT NULL,
                max_health_base REAL NOT NULL,
                absorption REAL NOT NULL,
                fire_ticks INTEGER NOT NULL,
                air INTEGER NOT NULL,
                inventory TEXT NOT NULL,
                held_slot INTEGER NOT NULL,
                xp_level INTEGER NOT NULL,
                xp_progress REAL NOT NULL,
                xp_total INTEGER NOT NULL,
                effects TEXT NOT NULL,
                effects_at INTEGER NOT NULL,
                killer TEXT,
                killer_name TEXT,
                death_cause TEXT,
                death_message TEXT,
                drops_done INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_combat_npcs_open ON lifesteal_combat_npcs(owner) WHERE state <> 'COMPLETE'")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_combat_npcs_state ON lifesteal_combat_npcs(state)")
    }

    /**
     * Inserts a new ACTIVE session; false when [owner] already has an open one (the partial UNIQUE
     * index refuses it, so nothing changes and no second NPC can exist).
     */
    fun insertActive(
        id: String,
        owner: UUID,
        ownerName: String,
        createdAt: Long,
        expiresAt: Long,
        tagExpiresAt: Long?,
        textures: String?,
        texturesSignature: String?,
        snapshot: Snapshot,
    ): Boolean {
        val s = snapshot
        return db.executeUpdate(
            """
            INSERT OR IGNORE INTO lifesteal_combat_npcs (id, owner, owner_name, state, outcome, created_at, expires_at, ended_at,
                tag_expires_at, textures, textures_signature, world, x, y, z, yaw, pitch, health, max_health_base, absorption,
                fire_ticks, air, inventory, held_slot, xp_level, xp_progress, xp_total, effects, effects_at, drops_done, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', NULL, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)
            """.trimIndent(),
            id, owner.toString(), ownerName, createdAt, expiresAt, tagExpiresAt, textures, texturesSignature,
            s.location.world, s.location.x, s.location.y, s.location.z, s.location.yaw.toDouble(), s.location.pitch.toDouble(),
            s.health, s.maxHealthBase, s.absorption, s.fireTicks, s.air, CombatNpcRules.encodeInventory(s.inventory), s.heldSlot,
            s.xpLevel, s.xpProgress.toDouble(), s.xpTotal, CombatNpcRules.encodeEffects(s.effects), s.effectsAt, createdAt,
        ) > 0
    }

    /**
     * [owner]'s open (not COMPLETE) session, if any. Throws when one exists but can't be read, so the
     * caller never mistakes it for "no session" (the owner would get an empty inventory).
     */
    fun open(owner: UUID): Session? {
        val rows = db.query("SELECT * FROM lifesteal_combat_npcs WHERE owner = ? AND state <> 'COMPLETE'", owner.toString()) { rs ->
            read(rs) ?: throw IllegalStateException("combat NPC session ${rs.getString("id")} of $owner is unreadable")
        }
        return rows.firstOrNull()
    }

    /** Every open session (startup recovery). Unreadable rows are skipped and reported by [unreadableOpen]. */
    fun allOpen(): List<Session> =
        db.query("SELECT * FROM lifesteal_combat_npcs WHERE state <> 'COMPLETE' ORDER BY created_at") { read(it) }.filterNotNull()

    /** Ids of open rows that can't be read (never touched automatically, so nothing is lost). */
    fun unreadableOpen(): List<String> =
        db.query("SELECT * FROM lifesteal_combat_npcs WHERE state <> 'COMPLETE'") { rs -> if (read(rs) == null) rs.getString("id") else null }.filterNotNull()

    fun byId(id: String): Session? = db.queryFirst("SELECT * FROM lifesteal_combat_npcs WHERE id = ?", id) { read(it) }

    fun recent(limit: Int, owner: UUID? = null): List<Session> =
        if (owner == null) db.query("SELECT * FROM lifesteal_combat_npcs ORDER BY created_at DESC LIMIT ?", limit.coerceIn(1, 100)) { read(it) }.filterNotNull()
        else db.query("SELECT * FROM lifesteal_combat_npcs WHERE owner = ? ORDER BY created_at DESC LIMIT ?", owner.toString(), limit.coerceIn(1, 100)) { read(it) }.filterNotNull()

    /** The live position/health of a standing NPC (periodic and at shutdown). Only while ACTIVE. */
    fun saveLive(id: String, snapshot: Snapshot, now: Long): Boolean = writeSnapshot(id, snapshot, now, "ACTIVE", "ACTIVE", null)

    /**
     * ACTIVE -> [to] (SURVIVED or RECOVERING) with the NPC's final state: its deadline passed, it was
     * removed without dying, or its owner reconnected. False when the session isn't ACTIVE anymore.
     */
    fun endAlive(id: String, snapshot: Snapshot, outcome: Outcome, to: State, now: Long): Boolean {
        require(to == State.SURVIVED || to == State.RECOVERING) { "an NPC that stayed alive ends SURVIVED or RECOVERING" }
        require(outcome != Outcome.DEAD) { "use die() for a death" }
        return writeSnapshot(id, snapshot, now, "ACTIVE", to.name, outcome)
    }

    /**
     * ACTIVE -> DEAD: the one committed death of this session (a second death event, a reconnect in
     * the same tick or a restart finds it no longer ACTIVE). [snapshot] is what drops.
     */
    fun die(id: String, snapshot: Snapshot, killer: UUID?, killerName: String?, cause: String?, message: String?, now: Long): Boolean {
        var won = false
        db.transaction {
            won = writeSnapshot(id, snapshot, now, "ACTIVE", State.DEAD.name, Outcome.DEAD)
            if (won) {
                db.executeUpdate(
                    "UPDATE lifesteal_combat_npcs SET killer = ?, killer_name = ?, death_cause = ?, death_message = ? WHERE id = ?",
                    killer?.toString(), killerName, cause, message, id,
                )
            }
        }
        return won
    }

    /** DEAD with drops pending -> drops done (once). */
    fun markDropsDone(id: String, now: Long): Boolean = db.executeUpdate(
        "UPDATE lifesteal_combat_npcs SET drops_done = 1, updated_at = ? WHERE id = ? AND state = 'DEAD' AND drops_done = 0", now, id,
    ) > 0

    /** SURVIVED -> RECOVERING (the owner joined). */
    fun beginRestore(id: String, now: Long): Boolean = db.executeUpdate(
        "UPDATE lifesteal_combat_npcs SET state = 'RECOVERING', updated_at = ? WHERE id = ? AND state = 'SURVIVED'", now, id,
    ) > 0

    /** RECOVERING -> COMPLETE, or DEAD (drops done) -> COMPLETE, after the owner got their state. */
    fun complete(id: String, now: Long): Boolean = db.executeUpdate(
        """
        UPDATE lifesteal_combat_npcs SET state = 'COMPLETE', updated_at = ?
        WHERE id = ? AND (state = 'RECOVERING' OR (state = 'DEAD' AND drops_done = 1))
        """.trimIndent(), now, id,
    ) > 0

    /** Deletes settled sessions older than [cutoff] (retention). Open sessions are never deleted. */
    fun purgeCompletedBefore(cutoff: Long): Int =
        db.executeUpdate("DELETE FROM lifesteal_combat_npcs WHERE state = 'COMPLETE' AND updated_at < ?", cutoff)

    private fun writeSnapshot(id: String, s: Snapshot, now: Long, from: String, to: String, outcome: Outcome?): Boolean = db.executeUpdate(
        """
        UPDATE lifesteal_combat_npcs SET state = ?, outcome = COALESCE(?, outcome), ended_at = CASE WHEN ? = 'ACTIVE' THEN ended_at ELSE ? END,
            world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ?, health = ?, absorption = ?, fire_ticks = ?, air = ?,
            inventory = ?, effects = ?, effects_at = ?, updated_at = ?
        WHERE id = ? AND state = ?
        """.trimIndent(),
        to, outcome?.name, to, now,
        s.location.world, s.location.x, s.location.y, s.location.z, s.location.yaw.toDouble(), s.location.pitch.toDouble(),
        s.health, s.absorption, s.fireTicks, s.air, CombatNpcRules.encodeInventory(s.inventory), CombatNpcRules.encodeEffects(s.effects),
        s.effectsAt, now, id, from,
    ) > 0

    private fun read(rs: java.sql.ResultSet): Session? = try {
        val inventory = CombatNpcRules.decodeInventory(rs.getString("inventory")) ?: throw IllegalStateException("bad inventory")
        Session(
            id = rs.getString("id"),
            owner = UUID.fromString(rs.getString("owner")),
            ownerName = rs.getString("owner_name"),
            state = State.valueOf(rs.getString("state")),
            outcome = rs.getString("outcome")?.let { Outcome.valueOf(it) },
            createdAt = rs.getLong("created_at"),
            expiresAt = rs.getLong("expires_at"),
            endedAt = rs.getLong("ended_at").takeUnless { rs.wasNull() },
            tagExpiresAt = rs.getLong("tag_expires_at").takeUnless { rs.wasNull() },
            textures = rs.getString("textures"),
            texturesSignature = rs.getString("textures_signature"),
            snapshot = Snapshot(
                location = Point(rs.getString("world"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"), rs.getFloat("yaw"), rs.getFloat("pitch")),
                health = rs.getDouble("health"),
                maxHealthBase = rs.getDouble("max_health_base"),
                absorption = rs.getDouble("absorption"),
                fireTicks = rs.getInt("fire_ticks"),
                air = rs.getInt("air"),
                inventory = inventory,
                heldSlot = rs.getInt("held_slot"),
                xpLevel = rs.getInt("xp_level"),
                xpProgress = rs.getFloat("xp_progress"),
                xpTotal = rs.getInt("xp_total"),
                effects = CombatNpcRules.decodeEffects(rs.getString("effects")),
                effectsAt = rs.getLong("effects_at"),
            ),
            killer = rs.getString("killer")?.let { UUID.fromString(it) },
            killerName = rs.getString("killer_name"),
            deathCause = rs.getString("death_cause"),
            deathMessage = rs.getString("death_message"),
            dropsDone = rs.getInt("drops_done") == 1,
            updatedAt = rs.getLong("updated_at"),
        )
    } catch (_: Exception) {
        null // A malformed row is reported, never rewritten.
    }
}
