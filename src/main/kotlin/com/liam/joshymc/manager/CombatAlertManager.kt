package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.EmbedBuilder
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * PvP cheat-detection Discord alerting (issue #852).
 *
 * This does NOT replace or duplicate [AntiCheatManager] / GrimAC — it is a thin alerting
 * layer on top of the violations they already compute. [AntiCheatManager.flag] calls into
 * [record] for the combat-relevant check types (both native checks and anything routed in
 * from GrimAC via the `/jmc-violation` bridge), so this manager never has to know how a
 * violation was detected. Detection only — never bans, kicks, mutes, or freezes anyone.
 */
class CombatAlertManager(private val plugin: Joshymc) {

    enum class Severity(val label: String, val color: Int) {
        LOW("Low", 0xFEE75C),
        MEDIUM("Medium", 0xFFA500),
        HIGH("High", 0xED4245)
    }

    private data class RateBucket(var count: Int, val windowStart: Long, var peakDetail: String)

    private val rateBuckets = ConcurrentHashMap<String, RateBucket>()

    private val enabled: Boolean get() = plugin.config.getBoolean("combat-alerts.enabled", true)
    private val discordEnabled: Boolean get() = plugin.config.getBoolean("combat-alerts.discord.enabled", true)
    private val discordChannelId: String get() = plugin.config.getString("combat-alerts.discord.channel-id") ?: ""
    private val rateLimitSeconds: Long get() = plugin.config.getLong("combat-alerts.rate-limit-seconds", 30L)

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS combat_alert_detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                check_name TEXT NOT NULL,
                severity TEXT NOT NULL,
                detected_value TEXT,
                target TEXT,
                evidence TEXT,
                world TEXT,
                location TEXT,
                ping INTEGER,
                tps REAL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())
    }

    /**
     * Records a PvP cheat-detection signal for staff review. Safe to call from the main
     * thread — the DB write is a single small INSERT and the Discord post is queued
     * (async, non-blocking). Never punishes the player.
     */
    fun record(
        player: Player,
        checkName: String,
        severity: Severity,
        detectedValue: String,
        evidence: String,
        target: Entity? = null
    ) {
        if (!enabled) return

        val now = System.currentTimeMillis()
        val world = player.world.name
        val location = "X: ${player.location.blockX} Y: ${player.location.blockY} Z: ${player.location.blockZ}"
        val ping = player.ping
        val tps = Bukkit.getTPS().getOrElse(0) { 20.0 }.coerceAtMost(20.0)
        val targetName = targetLabel(target)

        plugin.databaseManager.execute(
            """INSERT INTO combat_alert_detections
               (uuid, player_name, check_name, severity, detected_value, target, evidence, world, location, ping, tps, timestamp)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            player.uniqueId.toString(), player.name, checkName, severity.label,
            detectedValue, targetName, evidence, world, location, ping, tps, now
        )

        if (!discordEnabled || discordChannelId.isEmpty()) return
        queueDiscordAlert(player, checkName, severity, detectedValue, evidence, targetName, world, location, ping, tps, now)
    }

    /**
     * Rate-limits repeats of the exact same (player, check) pair. The first occurrence in a
     * window is posted immediately — distinct incidents (different check, different player)
     * are never suppressed. Further repeats inside the same window are grouped into a single
     * follow-up embed instead of spamming the channel.
     */
    private fun queueDiscordAlert(
        player: Player,
        checkName: String,
        severity: Severity,
        detectedValue: String,
        evidence: String,
        targetName: String,
        world: String,
        location: String,
        ping: Int,
        tps: Double,
        timestamp: Long
    ) {
        val key = "${player.uniqueId}|$checkName"
        val bucket = rateBuckets.compute(key) { _, existing ->
            if (existing == null || timestamp - existing.windowStart > rateLimitSeconds * 1000L) {
                RateBucket(1, timestamp, detectedValue)
            } else {
                existing.count++
                if (isHigherValue(detectedValue, existing.peakDetail)) existing.peakDetail = detectedValue
                existing
            }
        }!!

        if (bucket.count > 1) return // within the window and already alerted — grouped follow-up covers the rest

        val embed = EmbedBuilder()
            .setTitle("PvP Cheat Detection Alert")
            .setColor(severity.color)
            .addField("Player", player.name, true)
            .addField("UUID", player.uniqueId.toString(), true)
            .addField("Check", checkName, true)
            .addField("Severity", severity.label, true)
            .addField("Detected Value", detectedValue.ifBlank { "N/A" }, true)
            .addField("Target", targetName, true)
            .addField("Ping", "${ping}ms", true)
            .addField("TPS", "%.1f".format(tps), true)
            .addField("World", world, true)
            .addField("Location", location, true)
            .addField("Evidence", evidence.ifBlank { "Suspicious combat behavior detected." }, false)
            .setTimestamp(Instant.ofEpochMilli(timestamp))
            .build()
        plugin.discordManager.sendEmbedToChannel(discordChannelId, embed)

        if (rateLimitSeconds > 0) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val finalBucket = rateBuckets.remove(key)
                if (finalBucket != null && finalBucket.count > 1) {
                    sendGroupedFollowUp(player.uniqueId, player.name, checkName, finalBucket.count, finalBucket.peakDetail)
                }
            }, rateLimitSeconds * 20L)
        } else {
            rateBuckets.remove(key)
        }
    }

    private fun sendGroupedFollowUp(uuid: UUID, playerName: String, checkName: String, count: Int, peakDetail: String) {
        val embed = EmbedBuilder()
            .setTitle("Repeated PvP Cheat Alerts Suppressed")
            .setColor(0xFFA500)
            .setDescription("**$playerName** triggered **$checkName** **$count** times in the last $rateLimitSeconds seconds.")
            .addField("UUID", uuid.toString(), true)
            .addField("Peak", peakDetail.ifBlank { "N/A" }, true)
            .setTimestamp(Instant.now())
            .build()
        plugin.discordManager.sendEmbedToChannel(discordChannelId, embed)
    }

    private fun isHigherValue(candidate: String, current: String): Boolean {
        val candidateNum = Regex("[0-9]+(\\.[0-9]+)?").find(candidate)?.value?.toDoubleOrNull() ?: return false
        val currentNum = Regex("[0-9]+(\\.[0-9]+)?").find(current)?.value?.toDoubleOrNull() ?: return true
        return candidateNum > currentNum
    }

    private fun targetLabel(target: Entity?): String = when (target) {
        null -> "N/A"
        is Player -> target.name
        else -> target.type.name.lowercase().replace('_', ' ')
    }
}
