package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PvP Kill Streak + dynamic bounty system.
 *
 * Reuses [EconomyManager] for all money movement and the existing kill/death
 * stats already tracked by [ScoreboardManager] — this only adds the new
 * "consecutive kills without dying" streak concept plus milestone rewards
 * and the streak-scaled bounty (separate from the player-placed bounties in
 * [TeamManager]/`/bounty`).
 */
class KillStreakManager(private val plugin: Joshymc) : Listener {

    private val currentStreak = ConcurrentHashMap<UUID, Int>()
    private val highestStreak = ConcurrentHashMap<UUID, Int>()

    var enabled = false
        private set
    private var resetOnAnyDeath = true
    private var milestones: List<Int> = DEFAULT_MILESTONES
    private var milestoneBroadcastMin = 5
    private var milestoneRewardsEnabled = true
    private var rewardPerKill = 10000.0
    private var bountyEnabled = true
    private var bountyMinStreak = 5
    private var bountyPercentage = 10.0
    private var bountyBroadcast = true
    private var antiFarmCooldownMs = 30 * 60_000L
    private var envLossBroadcast = true
    private var envLossMinStreak = 10
    private var killMessageEnabled = true

    private var cleanupTask: BukkitTask? = null

    fun start() {
        enabled = true
        resetOnAnyDeath = plugin.config.getBoolean("kill-streaks.reset-on-any-death", true)
        milestones = plugin.config.getIntegerList("kill-streaks.milestones")
            .takeIf { it.isNotEmpty() }
            ?.sorted()
            ?: DEFAULT_MILESTONES
        milestoneBroadcastMin = plugin.config.getInt("kill-streaks.milestone-broadcast-min-streak", 5)
        milestoneRewardsEnabled = plugin.config.getBoolean("kill-streaks.milestone-rewards.enabled", true)
        rewardPerKill = plugin.config.getDouble("kill-streaks.milestone-rewards.reward-per-kill", 10000.0)
        bountyEnabled = plugin.config.getBoolean("kill-streaks.bounty.enabled", true)
        bountyMinStreak = plugin.config.getInt("kill-streaks.bounty.minimum-streak", 5)
        bountyPercentage = plugin.config.getDouble("kill-streaks.bounty.percentage", 10.0)
        bountyBroadcast = plugin.config.getBoolean("kill-streaks.bounty.broadcast", true)
        antiFarmCooldownMs = plugin.config.getLong("kill-streaks.anti-farm.same-victim-cooldown-minutes", 30) * 60_000L
        envLossBroadcast = plugin.config.getBoolean("kill-streaks.environmental-streak-loss.broadcast", true)
        envLossMinStreak = plugin.config.getInt("kill-streaks.environmental-streak-loss.minimum-streak", 10)
        killMessageEnabled = plugin.config.getBoolean("kill-streaks.kill-message", true)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS kill_streaks (
                uuid TEXT PRIMARY KEY,
                current_streak INTEGER NOT NULL DEFAULT 0,
                highest_streak INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS kill_streak_cooldowns (
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                last_kill_ms INTEGER NOT NULL,
                PRIMARY KEY (killer, victim)
            )
        """.trimIndent())

        currentStreak.clear()
        highestStreak.clear()
        plugin.databaseManager.query("SELECT uuid, current_streak, highest_streak FROM kill_streaks") { rs ->
            Triple(rs.getString("uuid"), rs.getInt("current_streak"), rs.getInt("highest_streak"))
        }.forEach { (uuidStr, current, highest) ->
            val uuid = UUID.fromString(uuidStr)
            if (current > 0) currentStreak[uuid] = current
            if (highest > 0) highestStreak[uuid] = highest
        }

        cleanupExpiredCooldowns()
        cleanupTask?.cancel()
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { cleanupExpiredCooldowns() }, 72000L, 72000L)

        plugin.logger.info("[KillStreak] KillStreakManager started.")
    }

    fun stop() {
        enabled = false
        cleanupTask?.cancel()
        cleanupTask = null
    }

    // ── Public accessors ──────────────────────────────────

    fun getCurrentStreak(uuid: UUID): Int = currentStreak.getOrDefault(uuid, 0)

    fun getHighestStreak(uuid: UUID): Int = highestStreak.getOrDefault(uuid, 0)

    fun isBountyActive(uuid: UUID): Boolean = bountyEnabled && getCurrentStreak(uuid) >= bountyMinStreak

    fun currentBountyAmount(uuid: UUID): Double {
        if (!isBountyActive(uuid)) return 0.0
        return plugin.economyManager.getBalance(uuid) * (bountyPercentage / 100.0)
    }

    fun nextMilestone(current: Int): Int? = milestones.firstOrNull { it > current }

    // ── Admin mutation ────────────────────────────────────

    fun setCurrentStreak(uuid: UUID, amount: Int) {
        val clamped = amount.coerceAtLeast(0)
        currentStreak[uuid] = clamped
        if (clamped > getHighestStreak(uuid)) highestStreak[uuid] = clamped
        persist(uuid)
    }

    fun setHighestStreak(uuid: UUID, amount: Int) {
        highestStreak[uuid] = amount.coerceAtLeast(0)
        persist(uuid)
    }

    fun resetStreak(uuid: UUID) {
        if (getCurrentStreak(uuid) == 0) return
        currentStreak[uuid] = 0
        persist(uuid)
    }

    private fun persist(uuid: UUID) {
        plugin.databaseManager.execute(
            "INSERT INTO kill_streaks (uuid, current_streak, highest_streak) VALUES (?, ?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET current_streak = excluded.current_streak, highest_streak = excluded.highest_streak",
            uuid.toString(), getCurrentStreak(uuid), getHighestStreak(uuid)
        )
    }

    // ── Anti-farm (killer → victim cooldown) ──────────────

    private fun isOnCooldown(killerUuid: UUID, victimUuid: UUID): Boolean {
        if (antiFarmCooldownMs <= 0) return false
        val last = plugin.databaseManager.queryFirst(
            "SELECT last_kill_ms FROM kill_streak_cooldowns WHERE killer = ? AND victim = ?",
            killerUuid.toString(), victimUuid.toString()
        ) { rs -> rs.getLong("last_kill_ms") }
        return last != null && (System.currentTimeMillis() - last) < antiFarmCooldownMs
    }

    private fun markCooldown(killerUuid: UUID, victimUuid: UUID) {
        plugin.databaseManager.execute(
            "INSERT INTO kill_streak_cooldowns (killer, victim, last_kill_ms) VALUES (?, ?, ?) " +
                "ON CONFLICT(killer, victim) DO UPDATE SET last_kill_ms = excluded.last_kill_ms",
            killerUuid.toString(), victimUuid.toString(), System.currentTimeMillis()
        )
    }

    private fun cleanupExpiredCooldowns() {
        if (antiFarmCooldownMs <= 0) return
        plugin.databaseManager.execute(
            "DELETE FROM kill_streak_cooldowns WHERE last_kill_ms < ?",
            System.currentTimeMillis() - antiFarmCooldownMs
        )
    }

    // ── Death handling ─────────────────────────────────────

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!enabled) return

        val victim = event.entity
        val victimUuid = victim.uniqueId
        val preDeathStreak = getCurrentStreak(victimUuid)
        val killer = victim.killer

        var validKill = false
        if (killer != null && killer.uniqueId != victimUuid) {
            validKill = !isOnCooldown(killer.uniqueId, victimUuid)
            if (validKill) {
                markCooldown(killer.uniqueId, victimUuid)
                handleValidKill(killer, victim, preDeathStreak)
            }
        }

        val shouldReset = resetOnAnyDeath || killer != null
        if (shouldReset && preDeathStreak > 0) {
            resetStreak(victimUuid)
            if (killer == null && envLossBroadcast && preDeathStreak >= envLossMinStreak) {
                plugin.commsManager.broadcast(
                    Component.text(victim.name, NamedTextColor.WHITE)
                        .append(Component.text("'s $preDeathStreak Kill Streak has ended!", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.COMBAT
                )
            }
        }
    }

    private fun handleValidKill(killer: Player, victim: Player, victimPreDeathStreak: Int) {
        val newStreak = currentStreak.merge(killer.uniqueId, 1, Int::plus) ?: 1
        if (newStreak > getHighestStreak(killer.uniqueId)) highestStreak[killer.uniqueId] = newStreak
        persist(killer.uniqueId)

        if (killMessageEnabled) {
            plugin.commsManager.send(
                killer,
                Component.text("You killed ", NamedTextColor.GRAY)
                    .append(Component.text(victim.name, NamedTextColor.WHITE))
                    .append(Component.text("! Kill Streak: ", NamedTextColor.GRAY))
                    .append(Component.text(newStreak, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.COMBAT
            )
        }

        if (bountyEnabled && newStreak == bountyMinStreak) {
            plugin.commsManager.send(
                killer,
                Component.text("BOUNTY ACTIVE — ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
                    .append(Component.text("dying to another player now costs you ${formatPercent(bountyPercentage)}% of your money.", NamedTextColor.GRAY)),
                CommunicationsManager.Category.COMBAT
            )
        }

        checkMilestone(killer, newStreak)

        if (bountyEnabled && victimPreDeathStreak >= bountyMinStreak) {
            payBounty(killer, victim, victimPreDeathStreak)
        }
    }

    private fun checkMilestone(killer: Player, streak: Int) {
        if (!milestoneRewardsEnabled || streak !in milestones) return

        val reward = streak * rewardPerKill
        plugin.economyManager.deposit(killer.uniqueId, reward)

        plugin.commsManager.send(
            killer,
            Component.text("Milestone reached! ", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)
                .append(Component.text("+$${plugin.economyManager.formatShort(reward)} for reaching a $streak Kill Streak!", NamedTextColor.GREEN)),
            CommunicationsManager.Category.COMBAT
        )

        if (streak >= milestoneBroadcastMin) {
            plugin.commsManager.broadcast(
                Component.text(killer.name, NamedTextColor.WHITE)
                    .append(Component.text(" is on a ", NamedTextColor.GRAY))
                    .append(Component.text("$streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" Kill Streak!", NamedTextColor.GRAY)),
                CommunicationsManager.Category.COMBAT
            )
        }
    }

    private fun payBounty(killer: Player, victim: Player, victimStreak: Int) {
        val balance = plugin.economyManager.getBalance(victim.uniqueId)
        if (balance <= 0.0) return

        val bountyAmount = balance * (bountyPercentage / 100.0)
        if (bountyAmount <= 0.0) return

        var transferred = false
        plugin.databaseManager.transaction {
            if (plugin.economyManager.withdraw(victim.uniqueId, bountyAmount)) {
                plugin.economyManager.deposit(killer.uniqueId, bountyAmount)
                transferred = true
            }
        }
        if (!transferred) return

        plugin.commsManager.send(
            killer,
            Component.text("You claimed a ", NamedTextColor.GRAY)
                .append(Component.text("$${plugin.economyManager.formatShort(bountyAmount)}", NamedTextColor.GREEN))
                .append(Component.text(" bounty from ", NamedTextColor.GRAY))
                .append(Component.text(victim.name, NamedTextColor.WHITE))
                .append(Component.text("'s $victimStreak Kill Streak!", NamedTextColor.GRAY)),
            CommunicationsManager.Category.COMBAT
        )

        if (bountyBroadcast) {
            plugin.commsManager.broadcast(
                Component.text(killer.name, NamedTextColor.WHITE)
                    .append(Component.text(" ended ", NamedTextColor.GRAY))
                    .append(Component.text(victim.name, NamedTextColor.WHITE))
                    .append(Component.text("'s $victimStreak Kill Streak and claimed a ", NamedTextColor.GRAY))
                    .append(Component.text("$${plugin.economyManager.formatShort(bountyAmount)}", NamedTextColor.GREEN))
                    .append(Component.text(" bounty!", NamedTextColor.GRAY)),
                CommunicationsManager.Category.COMBAT
            )
        }
    }

    private fun formatPercent(value: Double): String {
        return if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    }

    companion object {
        private val DEFAULT_MILESTONES = listOf(3, 5, 10, 15, 25, 50, 100, 200, 500)
    }
}
