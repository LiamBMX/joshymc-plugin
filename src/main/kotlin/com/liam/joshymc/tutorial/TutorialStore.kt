package com.liam.joshymc.tutorial

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Tutorial persistence (issue #1387). Bukkit-free: talks only to [SqliteDatabase].
 *
 * - `tutorial_progress`: one row per (player UUID, gamemode). State, 0-based step index, step
 *   progress (earnings so far, quest-menu bits) and timestamps. Every change is a conditional
 *   UPDATE on the state/step it expects, so a second handler for the same action (rapid events,
 *   a reconnect, a double click) changes nothing.
 * - `tutorial_rewards`: the one-time completion reward per (player UUID, gamemode). Created as
 *   PENDING in the same transaction that marks the tutorial COMPLETED, and moved to PAID in the
 *   same transaction as the deposit. Restarting or skipping never touches this table, so a reward
 *   can be earned once and never twice.
 * - `tutorial_meta`: `installed_at`, when the walkthrough first ran on this server (players who
 *   first played before it never get it started automatically).
 */
class TutorialStore(private val db: SqliteDatabase) {

    data class Progress(
        val uuid: UUID,
        val gamemode: ServerGamemode,
        val state: TutorialState,
        val step: Int,
        val progress: Double,
        val startedAt: Long,
        val updatedAt: Long,
        val completedAt: Long?,
    )

    enum class RewardStatus { PENDING, PAID }

    data class Reward(val status: RewardStatus, val amount: Double, val createdAt: Long, val paidAt: Long?, val attempts: Int)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS tutorial_progress (
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                state TEXT NOT NULL,
                step INTEGER NOT NULL DEFAULT 0,
                step_progress REAL NOT NULL DEFAULT 0,
                started_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                skipped_at INTEGER,
                completions INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, gamemode)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS tutorial_rewards (
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                status TEXT NOT NULL,
                amount REAL NOT NULL,
                created_at INTEGER NOT NULL,
                paid_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                PRIMARY KEY (uuid, gamemode)
            )
            """.trimIndent()
        )
        db.createTable("CREATE TABLE IF NOT EXISTS tutorial_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    /** When the walkthrough first ran here; recorded on the first call and never moved. */
    fun installedAt(now: Long): Long {
        db.execute("INSERT OR IGNORE INTO tutorial_meta (key, value) VALUES ('installed_at', ?)", now.toString())
        return db.queryFirst("SELECT value FROM tutorial_meta WHERE key = 'installed_at'") { it.getString("value") }
            ?.toLongOrNull() ?: now
    }

    fun load(uuid: UUID, gamemode: ServerGamemode): Progress? =
        db.queryFirst(
            "SELECT * FROM tutorial_progress WHERE uuid = ? AND gamemode = ?",
            uuid.toString(), gamemode.id
        ) { rs ->
            val state = TutorialState.fromName(rs.getString("state"))
            if (state == null) null else Progress(
                uuid, gamemode, state,
                rs.getInt("step"),
                rs.getDouble("step_progress"),
                rs.getLong("started_at"),
                rs.getLong("updated_at"),
                rs.getLong("completed_at").takeIf { !rs.wasNull() },
            )
        }

    /** Starts the tutorial for a player with no row. False when a row already exists. */
    fun start(uuid: UUID, gamemode: ServerGamemode, now: Long): Boolean =
        db.executeUpdate(
            "INSERT OR IGNORE INTO tutorial_progress (uuid, gamemode, state, step, step_progress, started_at, updated_at) " +
                "VALUES (?, ?, ?, 0, 0, ?, ?)",
            uuid.toString(), gamemode.id, TutorialState.ACTIVE.name, now, now
        ) == 1

    /**
     * Back to step 1 from any state (/tutorial restart, or /tutorial start on a skipped/never
     * started one). Only step progress resets; the reward row is never touched.
     */
    fun restart(uuid: UUID, gamemode: ServerGamemode, now: Long) {
        db.transaction {
            if (!start(uuid, gamemode, now)) {
                db.execute(
                    "UPDATE tutorial_progress SET state = ?, step = 0, step_progress = 0, started_at = ?, updated_at = ?, " +
                        "skipped_at = NULL WHERE uuid = ? AND gamemode = ?",
                    TutorialState.ACTIVE.name, now, now, uuid.toString(), gamemode.id
                )
            }
        }
    }

    /** ACTIVE at [step] → next step, exactly once. */
    fun advance(uuid: UUID, gamemode: ServerGamemode, step: Int, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE tutorial_progress SET step = step + 1, step_progress = 0, updated_at = ? " +
                "WHERE uuid = ? AND gamemode = ? AND state = ? AND step = ?",
            now, uuid.toString(), gamemode.id, TutorialState.ACTIVE.name, step
        ) == 1

    /** Saves step progress (earnings, quest bits) while still ACTIVE at [step]. */
    fun saveProgress(uuid: UUID, gamemode: ServerGamemode, step: Int, progress: Double, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE tutorial_progress SET step_progress = ?, updated_at = ? WHERE uuid = ? AND gamemode = ? AND state = ? AND step = ?",
            progress, now, uuid.toString(), gamemode.id, TutorialState.ACTIVE.name, step
        ) == 1

    /** Any state but COMPLETED/SKIPPED → SKIPPED (also creates the row). Never touches the reward. */
    fun skip(uuid: UUID, gamemode: ServerGamemode, now: Long): Boolean {
        var changed = false
        db.transaction {
            db.execute(
                "INSERT OR IGNORE INTO tutorial_progress (uuid, gamemode, state, step, step_progress, started_at, updated_at) " +
                    "VALUES (?, ?, ?, 0, 0, ?, ?)",
                uuid.toString(), gamemode.id, TutorialState.NOT_STARTED.name, now, now
            )
            changed = db.executeUpdate(
                "UPDATE tutorial_progress SET state = ?, skipped_at = ?, updated_at = ? WHERE uuid = ? AND gamemode = ? AND state IN (?, ?)",
                TutorialState.SKIPPED.name, now, now, uuid.toString(), gamemode.id,
                TutorialState.ACTIVE.name, TutorialState.NOT_STARTED.name
            ) == 1
        }
        return changed
    }

    enum class Completion {
        /** Completed now and a new PENDING reward was created. */
        NEW_REWARD,
        /** Completed now; the reward row already existed (paid earlier, or still pending). */
        REWARD_EXISTS,
        /** Not ACTIVE at the last step any more: another handler already finished it. */
        NOT_COMPLETED,
    }

    /**
     * ACTIVE at [lastStep] → COMPLETED and the reward entitlement, in one transaction. Only the
     * first caller wins; the reward row is INSERT OR IGNORE, so a replay never creates a second.
     */
    fun complete(uuid: UUID, gamemode: ServerGamemode, lastStep: Int, rewardAmount: Double, now: Long): Completion {
        var result = Completion.NOT_COMPLETED
        db.transaction {
            val done = db.executeUpdate(
                "UPDATE tutorial_progress SET state = ?, step = ?, step_progress = 0, completed_at = ?, updated_at = ?, " +
                    "completions = completions + 1 WHERE uuid = ? AND gamemode = ? AND state = ? AND step = ?",
                TutorialState.COMPLETED.name, lastStep + 1, now, now, uuid.toString(), gamemode.id,
                TutorialState.ACTIVE.name, lastStep
            ) == 1
            if (!done) return@transaction
            val created = db.executeUpdate(
                "INSERT OR IGNORE INTO tutorial_rewards (uuid, gamemode, status, amount, created_at) VALUES (?, ?, ?, ?, ?)",
                uuid.toString(), gamemode.id, RewardStatus.PENDING.name, rewardAmount, now
            ) == 1
            result = if (created) Completion.NEW_REWARD else Completion.REWARD_EXISTS
        }
        return result
    }

    fun reward(uuid: UUID, gamemode: ServerGamemode): Reward? =
        db.queryFirst(
            "SELECT status, amount, created_at, paid_at, attempts FROM tutorial_rewards WHERE uuid = ? AND gamemode = ?",
            uuid.toString(), gamemode.id
        ) { rs ->
            val status = RewardStatus.entries.firstOrNull { it.name == rs.getString("status") } ?: RewardStatus.PAID
            Reward(status, rs.getDouble("amount"), rs.getLong("created_at"), rs.getLong("paid_at").takeIf { !rs.wasNull() }, rs.getInt("attempts"))
        }

    enum class Payout { PAID, ALREADY_PAID, NO_ENTITLEMENT, FAILED }

    /**
     * Pays a PENDING reward: PENDING → PAID and [deposit] in one transaction. [deposit] gets the
     * stored amount and must return true only when the money is in the balance (false or a throw
     * rolls back, so the reward stays PENDING for a retry and is never paid twice).
     */
    fun pay(uuid: UUID, gamemode: ServerGamemode, now: Long, deposit: (Double) -> Boolean): Payout {
        val reward = reward(uuid, gamemode) ?: return Payout.NO_ENTITLEMENT
        if (reward.status == RewardStatus.PAID) return Payout.ALREADY_PAID
        var outcome = Payout.ALREADY_PAID
        try {
            db.transaction {
                val claimed = db.executeUpdate(
                    "UPDATE tutorial_rewards SET status = ?, paid_at = ?, attempts = attempts + 1, last_error = NULL " +
                        "WHERE uuid = ? AND gamemode = ? AND status = ?",
                    RewardStatus.PAID.name, now, uuid.toString(), gamemode.id, RewardStatus.PENDING.name
                ) == 1
                if (!claimed) return@transaction
                if (!deposit(reward.amount)) throw IllegalStateException("deposit refused")
                outcome = Payout.PAID
            }
        } catch (e: Exception) {
            recordFailure(uuid, gamemode, e.message ?: e.javaClass.simpleName)
            return Payout.FAILED
        }
        return outcome
    }

    /** Notes a failed payout attempt on a still-PENDING reward (best effort). */
    fun recordFailure(uuid: UUID, gamemode: ServerGamemode, error: String) {
        try {
            db.execute(
                "UPDATE tutorial_rewards SET attempts = attempts + 1, last_error = ? WHERE uuid = ? AND gamemode = ? AND status = ?",
                error.take(200), uuid.toString(), gamemode.id, RewardStatus.PENDING.name
            )
        } catch (_: Exception) {
            // The database itself is failing; the reward simply stays PENDING.
        }
    }
}
