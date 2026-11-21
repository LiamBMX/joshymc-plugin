package com.liam.joshymc.lootbox

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Durable Lootbox openings (issue #1602), Bukkit-free so the state machine can be checked against
 * a real SQLite database.
 *
 * Opening: `CONFIRMED` (prizes written, the Lootbox not yet taken and saved) → `REWARDS_COMMITTED`
 * (Lootbox consumed, five prizes owed) → `DELIVERING` (some delivered) → `DELIVERED` (all final).
 * A `CONFIRMED` row left at startup becomes `REVIEW` (staff: grant or void); `VOIDED` = cancelled
 * before anything was consumed. The selection screen before confirming is memory only: nothing is
 * at stake there.
 *
 * Prize: `PENDING` → `DELIVERING` (claimed for one synchronous delivery call) → `DELIVERED`, or back
 * to `PENDING` with fewer `remaining` when the inventory was full. A `DELIVERING` row left at
 * startup becomes `INTERRUPTED` and is never sent again automatically. `FAILED` = can't be built
 * any more. Every move is a conditional UPDATE on the expected state, and triggers keep final rows
 * final, so two callers (or a staff recovery) can never deliver one prize twice.
 */
class LootboxStore(
    private val db: SqliteDatabase,
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class OpeningState { CONFIRMED, REWARDS_COMMITTED, DELIVERING, DELIVERED, REVIEW, VOIDED }

    enum class RewardState { PENDING, DELIVERING, DELIVERED, INTERRUPTED, FAILED, VOIDED }

    /** A prize as committed: everything needed to deliver it later, whatever lootboxes.yml says by then. */
    data class Prize(
        val rewardId: String,
        /** `&` colour codes. */
        val name: String,
        val type: LootboxRewardType,
        /** Base64 item unit (items), plain decimal (coins/credits), tag id (chat-tag), newline-joined commands (command). */
        val payload: String,
        /** Items to hand out; 1 for everything else. */
        val amount: Int,
        /** Base64 display icon for the reveal and summary. */
        val icon: String,
    )

    data class Opening(
        val id: Int,
        val uuid: UUID,
        val name: String,
        val level: Int,
        val gamemode: String,
        val state: OpeningState,
        val slots: String,
        val createdAt: Long,
        val updatedAt: Long,
        val note: String?,
    )

    data class RewardRow(
        val openingId: Int,
        val index: Int,
        val uuid: UUID,
        val rewardId: String,
        val name: String,
        val rawType: String,
        val payload: String,
        val amount: Int,
        val remaining: Int,
        val icon: String,
        val state: RewardState,
        val note: String?,
        val updatedAt: Long,
    ) {
        val type: LootboxRewardType? get() = LootboxRewardType.of(rawType)
    }

    fun createSchema() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lootbox_openings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                level INTEGER NOT NULL CHECK (level BETWEEN 1 AND 10),
                gamemode TEXT NOT NULL,
                state TEXT NOT NULL,
                slots TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                note TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lootbox_openings_uuid ON lootbox_openings (uuid, id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lootbox_openings_state ON lootbox_openings (state)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lootbox_opening_rewards (
                opening_id INTEGER NOT NULL REFERENCES lootbox_openings (id),
                idx INTEGER NOT NULL CHECK (idx BETWEEN 0 AND 4),
                uuid TEXT NOT NULL,
                reward_id TEXT NOT NULL,
                name TEXT NOT NULL,
                type TEXT NOT NULL,
                payload TEXT NOT NULL,
                amount INTEGER NOT NULL CHECK (amount >= 1),
                remaining INTEGER NOT NULL CHECK (remaining >= 0),
                icon TEXT NOT NULL,
                state TEXT NOT NULL,
                note TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (opening_id, idx)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lootbox_rewards_uuid_state ON lootbox_opening_rewards (uuid, state)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lootbox_rewards_state ON lootbox_opening_rewards (state)")
        // Final rows stay final, whatever code (or a hand-written UPDATE) tries.
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lootbox_reward_final
            BEFORE UPDATE OF state ON lootbox_opening_rewards
            WHEN OLD.state IN ('DELIVERED', 'VOIDED') AND NEW.state <> OLD.state
            BEGIN SELECT RAISE(ABORT, 'lootbox reward is final'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lootbox_opening_final
            BEFORE UPDATE OF state ON lootbox_openings
            WHEN OLD.state IN ('DELIVERED', 'VOIDED') AND NEW.state <> OLD.state
            BEGIN SELECT RAISE(ABORT, 'lootbox opening is final'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lootbox_reward_no_delete
            BEFORE DELETE ON lootbox_opening_rewards
            BEGIN SELECT RAISE(ABORT, 'lootbox rewards are never deleted'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lootbox_secret (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                secret TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    /** The server's signing secret: [fresh] is stored the first time, the stored one is returned afterwards. */
    fun secret(fresh: String): String {
        db.execute("INSERT OR IGNORE INTO lootbox_secret (id, secret) VALUES (1, ?)", fresh)
        return db.queryFirst("SELECT secret FROM lootbox_secret WHERE id = 1") { it.getString("secret") }
            ?: error("lootbox_secret is empty")
    }

    // ── Opening ──────────────────────────────────────────────────

    /**
     * Writes a `CONFIRMED` opening with its [prizes] (exactly [LootboxDraw.PRIZES]) in one
     * transaction and returns its id. Nothing is owed yet: [commit] makes it count once the
     * Lootbox has been taken and saved.
     */
    fun reserve(uuid: UUID, name: String, level: Int, gamemode: String, slots: List<Int>, prizes: List<Prize>): Int {
        require(prizes.size == LootboxDraw.PRIZES) { "an opening has exactly ${LootboxDraw.PRIZES} prizes" }
        require(level in 1..LootboxConfig.LEVELS) { "level $level" }
        val now = clock()
        var id: Int? = null
        db.transaction {
            id = insertReturningId(
                "INSERT INTO lootbox_openings (uuid, name, level, gamemode, state, slots, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(uuid.toString(), name, level, gamemode, OpeningState.CONFIRMED.name, slots.joinToString(","), now, now),
            ) ?: error("opening insert returned no id")
            for ((index, prize) in prizes.withIndex()) {
                db.execute(
                    """INSERT INTO lootbox_opening_rewards
                       (opening_id, idx, uuid, reward_id, name, type, payload, amount, remaining, icon, state, updated_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    id, index, uuid.toString(), prize.rewardId, prize.name, prize.type.id, prize.payload,
                    prize.amount, prize.amount, prize.icon, RewardState.PENDING.name, now,
                )
            }
        }
        return checkNotNull(id)
    }

    /** CONFIRMED → REWARDS_COMMITTED. False when it was no longer CONFIRMED. */
    fun commit(id: Int): Boolean = moveOpening(id, OpeningState.CONFIRMED, OpeningState.REWARDS_COMMITTED, null)

    /** CONFIRMED (or REVIEW) → VOIDED and its prizes VOIDED, in one transaction: the Lootbox was not used. */
    fun void(id: Int, from: OpeningState, note: String): Boolean {
        require(from == OpeningState.CONFIRMED || from == OpeningState.REVIEW) { "only unconsumed openings are voided" }
        var moved = false
        db.transaction {
            moved = moveOpening(id, from, OpeningState.VOIDED, note)
            if (moved) {
                db.executeUpdate(
                    "UPDATE lootbox_opening_rewards SET state = ?, note = ?, updated_at = ? WHERE opening_id = ? AND state = ?",
                    RewardState.VOIDED.name, note, clock(), id, RewardState.PENDING.name,
                )
            }
        }
        return moved
    }

    /** REVIEW → REWARDS_COMMITTED: staff confirmed the Lootbox is gone, so the prizes are owed. */
    fun grantReviewed(id: Int, note: String): Boolean = moveOpening(id, OpeningState.REVIEW, OpeningState.REWARDS_COMMITTED, note)

    private fun moveOpening(id: Int, from: OpeningState, to: OpeningState, note: String?): Boolean =
        db.executeUpdate(
            "UPDATE lootbox_openings SET state = ?, updated_at = ?, note = COALESCE(?, note) WHERE id = ? AND state = ?",
            to.name, clock(), note, id, from.name,
        ) == 1

    data class Recovery(val review: List<Opening>, val interrupted: List<RewardRow>)

    /**
     * Startup: `CONFIRMED` openings (the server stopped between writing the prizes and saving the
     * player) become `REVIEW`; `DELIVERING` prizes (stopped mid-delivery) become `INTERRUPTED`.
     * Neither is ever delivered automatically.
     */
    fun recoverAtStartup(): Recovery {
        val review = db.query("SELECT * FROM lootbox_openings WHERE state = ?", OpeningState.CONFIRMED.name) { opening(it) }
        val interrupted = db.query("SELECT * FROM lootbox_opening_rewards WHERE state = ?", RewardState.DELIVERING.name) { reward(it) }
        db.transaction {
            for (o in review) moveOpening(o.id, OpeningState.CONFIRMED, OpeningState.REVIEW, "server stopped before the opening was saved")
            for (r in interrupted) {
                db.executeUpdate(
                    "UPDATE lootbox_opening_rewards SET state = ?, note = ?, updated_at = ? WHERE opening_id = ? AND idx = ? AND state = ?",
                    RewardState.INTERRUPTED.name, "server stopped mid-delivery (${r.remaining} were being handed over)", clock(),
                    r.openingId, r.index, RewardState.DELIVERING.name,
                )
            }
        }
        return Recovery(review, interrupted)
    }

    // ── Prizes ───────────────────────────────────────────────────

    private val activeOpening = "(SELECT state FROM lootbox_openings WHERE id = opening_id) IN ('REWARDS_COMMITTED', 'DELIVERING')"

    /** PENDING → DELIVERING for one synchronous delivery call, only for [uuid]'s committed opening. */
    fun claim(openingId: Int, index: Int, uuid: UUID): RewardRow? {
        val moved = db.executeUpdate(
            "UPDATE lootbox_opening_rewards SET state = ?, updated_at = ? WHERE opening_id = ? AND idx = ? AND uuid = ? AND state = ? AND $activeOpening",
            RewardState.DELIVERING.name, clock(), openingId, index, uuid.toString(), RewardState.PENDING.name,
        )
        if (moved != 1) return null
        return reward(openingId, index)
    }

    /** DELIVERING → [next] after handing over [delivered] items. */
    fun finish(openingId: Int, index: Int, delivered: Int, next: RewardState, note: String?) {
        require(next == RewardState.PENDING || next == RewardState.DELIVERED || next == RewardState.FAILED)
        db.transaction {
            val moved = db.executeUpdate(
                """UPDATE lootbox_opening_rewards SET state = ?, remaining = MAX(remaining - ?, 0), note = COALESCE(?, note), updated_at = ?
                   WHERE opening_id = ? AND idx = ? AND state = ?""",
                next.name, delivered, note, clock(), openingId, index, RewardState.DELIVERING.name,
            )
            check(moved == 1) { "prize $openingId/$index was not DELIVERING" }
            refresh(openingId)
        }
    }

    /**
     * PENDING → DELIVERED with [action] (a deposit or unlock) inside the same transaction, so the
     * prize and its effect commit together or not at all. False when it wasn't PENDING (already
     * handled) or belongs to someone else.
     */
    fun completeWith(openingId: Int, index: Int, uuid: UUID, note: String?, action: () -> Unit): Boolean {
        var done = false
        db.transaction {
            val moved = db.executeUpdate(
                """UPDATE lootbox_opening_rewards SET state = ?, remaining = 0, note = COALESCE(?, note), updated_at = ?
                   WHERE opening_id = ? AND idx = ? AND uuid = ? AND state = ? AND $activeOpening""",
                RewardState.DELIVERED.name, note, clock(), openingId, index, uuid.toString(), RewardState.PENDING.name,
            )
            if (moved == 1) {
                action()
                refresh(openingId)
                done = true
            }
        }
        return done
    }

    /** PENDING → FAILED (the prize can't be built any more); staff resolve it. */
    fun fail(openingId: Int, index: Int, note: String): Boolean {
        var moved = false
        db.transaction {
            moved = db.executeUpdate(
                "UPDATE lootbox_opening_rewards SET state = ?, note = ?, updated_at = ? WHERE opening_id = ? AND idx = ? AND state = ?",
                RewardState.FAILED.name, note, clock(), openingId, index, RewardState.PENDING.name,
            ) == 1
            if (moved) refresh(openingId)
        }
        return moved
    }

    /** Staff: INTERRUPTED/FAILED → PENDING (sent again, [remaining] as recorded). */
    fun resend(openingId: Int, index: Int, note: String): Boolean =
        db.executeUpdate(
            "UPDATE lootbox_opening_rewards SET state = ?, note = ?, updated_at = ? WHERE opening_id = ? AND idx = ? AND state IN (?, ?)",
            RewardState.PENDING.name, note, clock(), openingId, index, RewardState.INTERRUPTED.name, RewardState.FAILED.name,
        ) == 1

    /** Staff: INTERRUPTED/FAILED → DELIVERED (the player already has it, or it was handled by hand). */
    fun markDelivered(openingId: Int, index: Int, note: String): Boolean {
        var moved = false
        db.transaction {
            moved = db.executeUpdate(
                "UPDATE lootbox_opening_rewards SET state = ?, remaining = 0, note = ?, updated_at = ? WHERE opening_id = ? AND idx = ? AND state IN (?, ?)",
                RewardState.DELIVERED.name, note, clock(), openingId, index, RewardState.INTERRUPTED.name, RewardState.FAILED.name,
            ) == 1
            if (moved) refresh(openingId)
        }
        return moved
    }

    /** REWARDS_COMMITTED → DELIVERING once anything is final; → DELIVERED once every prize is. */
    private fun refresh(openingId: Int) {
        val states = db.query("SELECT state FROM lootbox_opening_rewards WHERE opening_id = ?", openingId) { RewardState.valueOf(it.getString("state")) }
        val allFinal = states.isNotEmpty() && states.all { it == RewardState.DELIVERED || it == RewardState.VOIDED }
        val current = get(openingId)?.state ?: return
        if (current != OpeningState.REWARDS_COMMITTED && current != OpeningState.DELIVERING) return
        val target = if (allFinal) OpeningState.DELIVERED else OpeningState.DELIVERING
        if (target != current) moveOpening(openingId, current, target, null)
    }

    // ── Reads ────────────────────────────────────────────────────

    fun get(id: Int): Opening? = db.queryFirst("SELECT * FROM lootbox_openings WHERE id = ?", id) { opening(it) }

    fun rewards(openingId: Int): List<RewardRow> =
        db.query("SELECT * FROM lootbox_opening_rewards WHERE opening_id = ? ORDER BY idx", openingId) { reward(it) }

    fun reward(openingId: Int, index: Int): RewardRow? =
        db.queryFirst("SELECT * FROM lootbox_opening_rewards WHERE opening_id = ? AND idx = ?", openingId, index) { reward(it) }

    /** [uuid]'s prizes still to deliver, oldest first. */
    fun pendingFor(uuid: UUID, limit: Int = 100): List<RewardRow> =
        db.query(
            "SELECT * FROM lootbox_opening_rewards WHERE uuid = ? AND state = ? AND $activeOpening ORDER BY opening_id, idx LIMIT ?",
            uuid.toString(), RewardState.PENDING.name, limit,
        ) { reward(it) }

    /** Players with prizes still to deliver. */
    fun pendingPlayers(limit: Int = 500): List<UUID> =
        db.query(
            "SELECT DISTINCT uuid FROM lootbox_opening_rewards WHERE state = ? AND $activeOpening LIMIT ?",
            RewardState.PENDING.name, limit,
        ) { UUID.fromString(it.getString("uuid")) }

    fun recentFor(uuid: UUID, limit: Int): List<Opening> =
        db.query("SELECT * FROM lootbox_openings WHERE uuid = ? ORDER BY id DESC LIMIT ?", uuid.toString(), limit) { opening(it) }

    /** Openings staff should look at: REVIEW, or with a prize that is INTERRUPTED, FAILED or still PENDING. */
    fun attention(limit: Int): List<Opening> =
        db.query(
            """SELECT * FROM lootbox_openings o WHERE o.state = ?
               OR (o.state IN ('REWARDS_COMMITTED', 'DELIVERING') AND EXISTS (
                   SELECT 1 FROM lootbox_opening_rewards r WHERE r.opening_id = o.id AND r.state IN ('PENDING', 'INTERRUPTED', 'FAILED')))
               ORDER BY o.id DESC LIMIT ?""",
            OpeningState.REVIEW.name, limit,
        ) { opening(it) }

    fun counts(): Map<String, Int> =
        db.query("SELECT state, COUNT(*) AS n FROM lootbox_opening_rewards GROUP BY state") { it.getString("state") to it.getInt("n") }.toMap()

    private fun opening(rs: ResultSet) = Opening(
        id = rs.getInt("id"),
        uuid = UUID.fromString(rs.getString("uuid")),
        name = rs.getString("name"),
        level = rs.getInt("level"),
        gamemode = rs.getString("gamemode"),
        state = OpeningState.valueOf(rs.getString("state")),
        slots = rs.getString("slots"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
        note = rs.getString("note"),
    )

    private fun reward(rs: ResultSet) = RewardRow(
        openingId = rs.getInt("opening_id"),
        index = rs.getInt("idx"),
        uuid = UUID.fromString(rs.getString("uuid")),
        rewardId = rs.getString("reward_id"),
        name = rs.getString("name"),
        rawType = rs.getString("type"),
        payload = rs.getString("payload"),
        amount = rs.getInt("amount"),
        remaining = rs.getInt("remaining"),
        icon = rs.getString("icon"),
        state = RewardState.valueOf(rs.getString("state")),
        note = rs.getString("note"),
        updatedAt = rs.getLong("updated_at"),
    )
}
