package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import java.sql.ResultSet
import java.util.UUID
import kotlin.random.Random

/**
 * Persistence of the Lifesteal Daily & Weekly Quests (issue #1425), Bukkit-free (data.db through
 * [DatabaseManager]; `scripts/LifestealQuestCheck.kt` runs it against a real SQLite file).
 *
 * Tables (all `lifesteal_quest_*`, every row carries `gamemode = 'lifesteal'`; the Survival quests'
 * `quest_cycle_progress` is never read or written):
 *  - `assignments`: one row per quest a player got. The template is **snapshotted** (name, text,
 *    objective, match, team rule, target, rewards), so editing the config mid-cycle never changes a
 *    quest someone already holds. States ACTIVE → COMPLETED → CLAIMED, or ACTIVE → REROLLED; CLAIMED
 *    and REROLLED are final (trigger) and progress never goes down (trigger). A partial UNIQUE index
 *    keeps one live quest per (player, cycle, slot), so a double load can't assign twice.
 *  - `rerolls`: reroll history (UNIQUE old assignment, so one quest is replaced once), the free/paid
 *    counts and the Credits taken.
 *  - `payouts`: one row per reward of a claimed quest, written in the claim's transaction and later
 *    handed to the store-delivery queue (`delivery_id`), so a restart mid-claim never loses or
 *    repeats a reward.
 *  - `unique_victims`, `pvp_kills`: anti-farming state that must survive restarts.
 *  - `audit`.
 */
class LifestealQuestStore(private val db: DatabaseManager, private val wallet: Wallet) {

    /** Credits for paid rerolls; called inside the reroll transaction (a later failure rolls it back). */
    fun interface Wallet {
        fun withdrawCredits(uuid: UUID, amount: Double): Boolean
    }

    enum class State { ACTIVE, COMPLETED, CLAIMED, REROLLED }

    data class Assignment(
        val id: Long,
        val uuid: UUID,
        val period: QuestPeriod,
        val cycleId: String,
        val cycleEnd: Long,
        val slot: Int,
        val questId: String,
        val name: String,
        val description: String,
        val category: QuestCategory,
        val objective: QuestObjective,
        val match: Set<String>,
        val team: TeamRequirement,
        val group: String?,
        /** Stored units. */
        val target: Long,
        val progress: Long,
        val rewards: List<PurchaseRewards.Spec>,
        val state: State,
        val version: Int,
    )

    data class Payout(val id: Long, val assignmentId: Long, val uuid: UUID, val playerName: String, val spec: PurchaseRewards.Spec, val deliveryId: Long?)

    private val gm = QuestSettings.GAMEMODE

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_quest_assignments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                period TEXT NOT NULL,
                cycle_id TEXT NOT NULL,
                cycle_end INTEGER NOT NULL,
                slot INTEGER NOT NULL,
                quest_id TEXT NOT NULL,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                category TEXT NOT NULL,
                objective TEXT NOT NULL,
                match_tokens TEXT NOT NULL,
                team TEXT NOT NULL,
                grp TEXT,
                target INTEGER NOT NULL CHECK (target > 0),
                progress INTEGER NOT NULL DEFAULT 0 CHECK (progress >= 0),
                rewards TEXT NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'COMPLETED', 'CLAIMED', 'REROLLED')),
                assigned_at INTEGER NOT NULL,
                completed_at INTEGER,
                claimed_at INTEGER,
                version INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.createTable(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_lsq_live_slot ON lifesteal_quest_assignments (uuid, gamemode, cycle_id, slot) WHERE state != 'REROLLED'"
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lsq_player_cycle ON lifesteal_quest_assignments (uuid, gamemode, cycle_id)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lsq_final_state BEFORE UPDATE OF state ON lifesteal_quest_assignments
            WHEN OLD.state IN ('CLAIMED', 'REROLLED') AND NEW.state != OLD.state
            BEGIN SELECT RAISE(ABORT, 'lifesteal quest state is final'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lsq_progress BEFORE UPDATE OF progress ON lifesteal_quest_assignments
            WHEN NEW.progress < OLD.progress OR OLD.state IN ('CLAIMED', 'REROLLED')
            BEGIN SELECT RAISE(ABORT, 'lifesteal quest progress can only grow on a live quest'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_quest_rerolls (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                period TEXT NOT NULL,
                cycle_id TEXT NOT NULL,
                slot INTEGER NOT NULL,
                old_assignment INTEGER NOT NULL UNIQUE,
                new_assignment INTEGER NOT NULL,
                old_quest TEXT NOT NULL,
                new_quest TEXT NOT NULL,
                credits REAL NOT NULL DEFAULT 0 CHECK (credits >= 0),
                free INTEGER NOT NULL,
                ineligible INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lsq_rerolls_cycle ON lifesteal_quest_rerolls (uuid, gamemode, cycle_id)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_quest_payouts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                assignment_id INTEGER NOT NULL,
                idx INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                param TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                delivery_id INTEGER,
                created_at INTEGER NOT NULL,
                UNIQUE (assignment_id, idx)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lsq_payouts_unlinked ON lifesteal_quest_payouts (delivery_id, uuid)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_quest_unique_victims (
                assignment_id INTEGER NOT NULL,
                victim TEXT NOT NULL,
                at INTEGER NOT NULL,
                PRIMARY KEY (assignment_id, victim)
            )
            """.trimIndent()
        )
        db.createTable("CREATE TABLE IF NOT EXISTS lifesteal_quest_pvp_kills (killer TEXT NOT NULL, victim TEXT NOT NULL, at INTEGER NOT NULL)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lsq_pvp_kills ON lifesteal_quest_pvp_kills (killer, at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_quest_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                action TEXT NOT NULL,
                detail TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    // ── Reading ─────────────────────────────────────────────

    private val columns = "id, uuid, period, cycle_id, cycle_end, slot, quest_id, name, description, category, objective, " +
        "match_tokens, team, grp, target, progress, rewards, state, version"

    private fun map(rs: ResultSet): Assignment? {
        val period = QuestPeriod.of(rs.getString("period")) ?: return null
        val objective = QuestObjective.of(rs.getString("objective")) ?: return null
        return Assignment(
            id = rs.getLong("id"),
            uuid = UUID.fromString(rs.getString("uuid")),
            period = period,
            cycleId = rs.getString("cycle_id"),
            cycleEnd = rs.getLong("cycle_end"),
            slot = rs.getInt("slot"),
            questId = rs.getString("quest_id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            category = QuestCategory.of(rs.getString("category")) ?: objective.category,
            objective = objective,
            match = rs.getString("match_tokens").split(',').filter { it.isNotEmpty() }.toSet(),
            team = TeamRequirement.of(rs.getString("team")) ?: TeamRequirement.ANY,
            group = rs.getString("grp"),
            target = rs.getLong("target"),
            progress = rs.getLong("progress"),
            rewards = decodeRewards(rs.getString("rewards")),
            state = State.valueOf(rs.getString("state")),
            version = rs.getInt("version"),
        )
    }

    /** The live (not rerolled) quests of [uuid] in [cycleId], by slot. */
    fun board(uuid: UUID, cycleId: String): List<Assignment> =
        db.query(
            "SELECT $columns FROM lifesteal_quest_assignments WHERE uuid = ? AND gamemode = ? AND cycle_id = ? AND state != 'REROLLED' ORDER BY slot",
            uuid.toString(), gm, cycleId,
        ) { map(it) }.filterNotNull()

    fun get(id: Long): Assignment? =
        db.queryFirst("SELECT $columns FROM lifesteal_quest_assignments WHERE id = ? AND gamemode = ?", id, gm) { map(it) }

    /** Every quest id [uuid] held in [cycleId], rerolled ones included (never offered again that cycle). */
    fun heldIds(uuid: UUID, cycleId: String): Set<String> =
        db.query(
            "SELECT quest_id FROM lifesteal_quest_assignments WHERE uuid = ? AND gamemode = ? AND cycle_id = ?",
            uuid.toString(), gm, cycleId,
        ) { it.getString(1) }.toSet()

    data class RerollUsage(val free: Int, val paid: Int)

    /** Rerolls made in [cycleId]. Free rerolls of a quest the player had become ineligible for don't count. */
    fun rerollUsage(uuid: UUID, cycleId: String): RerollUsage =
        db.queryFirst(
            """
            SELECT COALESCE(SUM(CASE WHEN free = 1 AND ineligible = 0 THEN 1 ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN free = 0 THEN 1 ELSE 0 END), 0)
            FROM lifesteal_quest_rerolls WHERE uuid = ? AND gamemode = ? AND cycle_id = ?
            """.trimIndent(),
            uuid.toString(), gm, cycleId,
        ) { RerollUsage(it.getInt(1), it.getInt(2)) } ?: RerollUsage(0, 0)

    // ── Assigning ───────────────────────────────────────────

    /**
     * Returns [uuid]'s board for [cycle], first filling every empty slot (1..[count]) with picks
     * from [pool] in one transaction. Safe to call any number of times and from two threads: the
     * partial UNIQUE index ignores a second fill of the same slot.
     */
    fun ensureBoard(
        uuid: UUID, cycle: QuestCycles.Cycle, count: Int, pool: List<QuestTemplate>, hasTeam: Boolean,
        now: Long, random: Random = Random.Default,
    ): List<Assignment> {
        var result: List<Assignment> = emptyList()
        db.transaction {
            val board = board(uuid, cycle.id)
            val missing = (0 until count).filter { slot -> board.none { it.slot == slot } }
            if (missing.isNotEmpty()) {
                val taken = QuestSelection.Taken(
                    ids = heldIds(uuid, cycle.id),
                    objectives = board.mapTo(HashSet()) { it.objective },
                    groups = board.mapNotNullTo(HashSet()) { it.group },
                )
                val picks = QuestSelection.pick(pool, missing.size, hasTeam, taken, random)
                for ((slot, template) in missing.zip(picks)) insert(uuid, cycle, slot, template, now)
                if (picks.isNotEmpty()) audit(now, uuid, "ASSIGN", "${cycle.id}: ${picks.joinToString(",") { it.id }}")
            }
            result = board(uuid, cycle.id)
        }
        return result
    }

    private fun insert(uuid: UUID, cycle: QuestCycles.Cycle, slot: Int, t: QuestTemplate, now: Long): Long? =
        db.insertReturningId(
            """
            INSERT OR IGNORE INTO lifesteal_quest_assignments
                (uuid, gamemode, period, cycle_id, cycle_end, slot, quest_id, name, description, category, objective,
                 match_tokens, team, grp, target, progress, rewards, state, assigned_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 'ACTIVE', ?)
            """.trimIndent(),
            uuid.toString(), gm, cycle.period.id, cycle.id, cycle.endMillis, slot, t.id, t.name, t.describe(),
            t.category.id, t.objective.id, t.match.sorted().joinToString(","), t.team.id, t.group, t.storedTarget,
            encodeRewards(t.rewards), now,
        )?.toLong()

    // ── Progress ────────────────────────────────────────────

    /**
     * Saves progress (stored units) of live quests. Never lowers progress, never touches a quest
     * that was claimed or rerolled meanwhile, caps at the target and marks reached targets
     * COMPLETED. Returns the ids that became COMPLETED.
     */
    fun saveProgress(progress: Map<Long, Long>, now: Long): List<Long> {
        val completed = ArrayList<Long>()
        if (progress.isEmpty()) return completed
        db.transaction {
            for ((id, value) in progress) {
                db.executeUpdate(
                    """
                    UPDATE lifesteal_quest_assignments SET progress = MIN(target, ?), version = version + 1
                    WHERE id = ? AND gamemode = ? AND state = 'ACTIVE' AND progress < MIN(target, ?)
                    """.trimIndent(),
                    value, id, gm, value,
                )
                val done = db.executeUpdate(
                    """
                    UPDATE lifesteal_quest_assignments SET state = 'COMPLETED', completed_at = ?, version = version + 1
                    WHERE id = ? AND gamemode = ? AND state = 'ACTIVE' AND progress >= target
                    """.trimIndent(),
                    now, id, gm,
                )
                if (done == 1) completed += id
            }
        }
        return completed
    }

    /** UNIQUE_PLAYER_KILL: true the first time [victim] counts for this quest. */
    fun addUniqueVictim(assignmentId: Long, victim: UUID, now: Long): Boolean =
        db.executeUpdate(
            "INSERT OR IGNORE INTO lifesteal_quest_unique_victims (assignment_id, victim, at) VALUES (?, ?, ?)",
            assignmentId, victim.toString(), now,
        ) == 1

    // ── Claiming ────────────────────────────────────────────

    sealed interface ClaimResult {
        data class Claimed(val assignment: Assignment, val payouts: List<Payout>) : ClaimResult
        data object AlreadyClaimed : ClaimResult
        data object Rerolled : ClaimResult
        /** The quest's cycle is over: it can never be claimed. */
        data object Expired : ClaimResult
        data object NotComplete : ClaimResult
        data object NotFound : ClaimResult
    }

    /**
     * Claims quest [id] for [uuid], once. [currentCycleId] is the cycle running *now* for the
     * quest's period (an earlier cycle's quest is refused, completed or not). [knownProgress] is the
     * in-memory progress not saved yet. The state change and the payout rows commit together; the
     * conditional UPDATE makes a second (or concurrent) claim find nothing to claim.
     */
    fun claim(uuid: UUID, id: Long, currentCycleId: String, knownProgress: Long, playerName: String, now: Long): ClaimResult {
        var result: ClaimResult = ClaimResult.NotFound
        db.transaction {
            val a = get(id)
            result = when {
                a == null || a.uuid != uuid -> ClaimResult.NotFound
                a.state == State.CLAIMED -> ClaimResult.AlreadyClaimed
                a.state == State.REROLLED -> ClaimResult.Rerolled
                a.cycleId != currentCycleId || now >= a.cycleEnd -> ClaimResult.Expired
                maxOf(a.progress, knownProgress) < a.target -> ClaimResult.NotComplete
                else -> {
                    val changed = db.executeUpdate(
                        """
                        UPDATE lifesteal_quest_assignments
                        SET state = 'CLAIMED', progress = target, claimed_at = ?, completed_at = COALESCE(completed_at, ?), version = version + 1
                        WHERE id = ? AND gamemode = ? AND state IN ('ACTIVE', 'COMPLETED') AND version = ?
                        """.trimIndent(),
                        now, now, id, gm, a.version,
                    )
                    if (changed != 1) ClaimResult.AlreadyClaimed
                    else {
                        val payouts = a.rewards.mapIndexed { index, spec ->
                            val payoutId = db.insertReturningId(
                                """
                                INSERT INTO lifesteal_quest_payouts (assignment_id, idx, uuid, player_name, kind, param, quantity, created_at)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """.trimIndent(),
                                id, index, uuid.toString(), playerName, spec.kind.id, spec.param, spec.quantity, now,
                            ) ?: error("payout row not inserted")
                            Payout(payoutId.toLong(), id, uuid, playerName, spec, null)
                        }
                        audit(now, uuid, "CLAIM", "#$id ${a.questId} (${a.cycleId}): ${a.rewards.joinToString("; ") { it.describe() }}")
                        ClaimResult.Claimed(a.copy(state = State.CLAIMED, progress = a.target), payouts)
                    }
                }
            }
        }
        return result
    }

    /** Payout rows not yet handed to the delivery queue ([uuid] = only theirs). */
    fun unlinkedPayouts(uuid: UUID?, limit: Int = 200): List<Payout> {
        val sql = "SELECT id, assignment_id, uuid, player_name, kind, param, quantity, delivery_id FROM lifesteal_quest_payouts " +
            "WHERE delivery_id IS NULL" + (if (uuid != null) " AND uuid = ?" else "") + " ORDER BY id LIMIT ?"
        val params: Array<Any?> = if (uuid != null) arrayOf(uuid.toString(), limit) else arrayOf(limit)
        return db.query(sql, *params) { rs ->
            val kind = PurchaseRewards.Kind.of(rs.getString("kind")) ?: return@query null
            Payout(
                rs.getLong("id"), rs.getLong("assignment_id"), UUID.fromString(rs.getString("uuid")), rs.getString("player_name"),
                PurchaseRewards.Spec(kind, rs.getString("param"), rs.getInt("quantity")), null,
            )
        }.filterNotNull()
    }

    fun link(payoutId: Long, deliveryId: Long): Boolean =
        db.executeUpdate("UPDATE lifesteal_quest_payouts SET delivery_id = ? WHERE id = ? AND delivery_id IS NULL", deliveryId, payoutId) == 1

    // ── Rerolling ───────────────────────────────────────────

    data class RerollRequest(
        val uuid: UUID,
        val assignmentId: Long,
        /** The quest the menu showed; anything else is a stale click. */
        val expectedQuestId: String,
        val cycle: QuestCycles.Cycle,
        val settings: RerollSettings,
        val freeWhenIneligible: Boolean,
        val hasTeam: Boolean,
        /** The price the menu showed; a different price now is refused, never charged. */
        val expectedPrice: RerollRules.Price,
        val pool: List<QuestTemplate>,
        val now: Long,
        val random: Random = Random.Default,
    )

    sealed interface RerollResult {
        data class Rerolled(val old: Assignment, val replacement: Assignment, val price: RerollRules.Price) : RerollResult
        data class Refused(val reason: RerollRules.Refusal) : RerollResult
    }

    private class Abort(val reason: RerollRules.Refusal) : RuntimeException(reason.name)

    /**
     * Replaces one ACTIVE quest. Re-checks everything inside one transaction (owner, the quest the
     * menu showed, cycle, state, reroll counts, price) and picks a replacement that is not on the
     * board, was never held this cycle, and shares no objective or group with the other quests.
     * Credits are withdrawn inside the transaction, so a failure after them rolls them back.
     */
    fun reroll(r: RerollRequest): RerollResult {
        var result: RerollResult = RerollResult.Refused(RerollRules.Refusal.NOT_FOUND)
        try {
            db.transaction {
                val a = get(r.assignmentId)
                fun refuse(reason: RerollRules.Refusal): Nothing = throw Abort(reason)
                if (a == null || a.uuid != r.uuid) refuse(RerollRules.Refusal.NOT_FOUND)
                if (a.questId != r.expectedQuestId) refuse(RerollRules.Refusal.STALE)
                when (a.state) {
                    State.CLAIMED -> refuse(RerollRules.Refusal.CLAIMED)
                    State.REROLLED -> refuse(RerollRules.Refusal.STALE)
                    State.COMPLETED -> refuse(RerollRules.Refusal.COMPLETED)
                    State.ACTIVE -> Unit
                }
                if (a.period != r.cycle.period || a.cycleId != r.cycle.id || r.now >= a.cycleEnd) refuse(RerollRules.Refusal.EXPIRED)
                if (a.progress >= a.target) refuse(RerollRules.Refusal.COMPLETED)
                val usage = rerollUsage(r.uuid, a.cycleId)
                val ineligible = !a.team.allows(r.hasTeam)
                val price = RerollRules.price(r.settings, usage.free, usage.paid, ineligible, r.freeWhenIneligible)
                if (price == RerollRules.Price.LimitReached) refuse(RerollRules.Refusal.LIMIT)
                if (price != r.expectedPrice) refuse(RerollRules.Refusal.PRICE_CHANGED)

                val others = board(r.uuid, a.cycleId).filter { it.id != a.id }
                val taken = QuestSelection.Taken(
                    ids = heldIds(r.uuid, a.cycleId),
                    objectives = others.mapTo(HashSet()) { it.objective } + a.objective,
                    groups = others.mapNotNullTo(HashSet()) { it.group } + listOfNotNull(a.group),
                )
                val replacement = QuestSelection.pick(r.pool, 1, r.hasTeam, taken, r.random).firstOrNull()
                    ?: refuse(RerollRules.Refusal.NO_REPLACEMENT)

                val credits = (price as? RerollRules.Price.Credits)?.amount ?: 0.0
                if (credits > 0.0 && !wallet.withdrawCredits(r.uuid, credits)) refuse(RerollRules.Refusal.INSUFFICIENT_CREDITS)

                val changed = db.executeUpdate(
                    "UPDATE lifesteal_quest_assignments SET state = 'REROLLED', version = version + 1 WHERE id = ? AND gamemode = ? AND state = 'ACTIVE' AND version = ?",
                    a.id, gm, a.version,
                )
                if (changed != 1) refuse(RerollRules.Refusal.STALE)
                val newId = insert(r.uuid, r.cycle, a.slot, replacement, r.now) ?: refuse(RerollRules.Refusal.STALE)
                db.execute(
                    """
                    INSERT INTO lifesteal_quest_rerolls
                        (uuid, gamemode, period, cycle_id, slot, old_assignment, new_assignment, old_quest, new_quest, credits, free, ineligible, at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    r.uuid.toString(), gm, a.period.id, a.cycleId, a.slot, a.id, newId, a.questId, replacement.id, credits,
                    if (price == RerollRules.Price.Free) 1 else 0, if (ineligible && r.freeWhenIneligible) 1 else 0, r.now,
                )
                audit(r.now, r.uuid, "REROLL", "#${a.id} ${a.questId} -> #$newId ${replacement.id} (${a.cycleId}, credits $credits)")
                result = RerollResult.Rerolled(a.copy(state = State.REROLLED), get(newId) ?: error("new quest row missing"), price)
            }
        } catch (e: Abort) {
            result = RerollResult.Refused(e.reason)
        }
        return result
    }

    // ── Anti-farming state ──────────────────────────────────

    fun recordPvpKill(killer: UUID, victim: UUID, at: Long) {
        db.execute("INSERT INTO lifesteal_quest_pvp_kills (killer, victim, at) VALUES (?, ?, ?)", killer.toString(), victim.toString(), at)
    }

    data class PvpKill(val killer: UUID, val victim: UUID, val at: Long)

    fun pvpKillsSince(since: Long): List<PvpKill> =
        db.query("SELECT killer, victim, at FROM lifesteal_quest_pvp_kills WHERE at >= ?", since) {
            PvpKill(UUID.fromString(it.getString(1)), UUID.fromString(it.getString(2)), it.getLong(3))
        }

    fun prunePvpKills(before: Long) {
        db.execute("DELETE FROM lifesteal_quest_pvp_kills WHERE at < ?", before)
    }

    /** AltManager's hashed connections; null when the lookup failed (callers then refuse). */
    fun sharesConnection(a: UUID, b: UUID): Boolean? = try {
        db.queryFirst(
            "SELECT 1 FROM player_connections x JOIN player_connections y ON x.connection_hash = y.connection_hash WHERE x.uuid = ? AND y.uuid = ? LIMIT 1",
            a.toString(), b.toString(),
        ) { true } ?: false
    } catch (e: Exception) {
        null
    }

    fun audit(now: Long, uuid: UUID, action: String, detail: String) {
        db.execute("INSERT INTO lifesteal_quest_audit (at, uuid, action, detail) VALUES (?, ?, ?, ?)", now, uuid.toString(), action, detail.take(1000))
    }

    companion object {
        fun encodeRewards(rewards: List<PurchaseRewards.Spec>): String =
            rewards.joinToString("\n") { "${it.kind.id}|${it.param}|${it.quantity}" }

        fun decodeRewards(text: String): List<PurchaseRewards.Spec> = text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 3) return@mapNotNull null
            val kind = PurchaseRewards.Kind.of(parts[0]) ?: return@mapNotNull null
            val quantity = parts[2].toIntOrNull() ?: return@mapNotNull null
            PurchaseRewards.Spec(kind, parts[1], quantity)
        }
    }
}
