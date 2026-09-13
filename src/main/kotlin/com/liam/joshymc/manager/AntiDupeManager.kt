package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.EmbedBuilder
import org.bukkit.entity.Player
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-dupe audit log + Discord alerting (issue #748).
 *
 * This does NOT replace the atomic claim-first guards individual managers already
 * have (AuctionManager's delete-before-pay, the voucher managers' reserveVoucherId,
 * OrderManager's escrow row claim, etc. — see CLAUDE.md "Recurring gotchas"). Those
 * already stop the dupe. What was missing was staff visibility when one of those
 * guards actually catches an attempt — this manager persists the evidence and posts
 * a Discord embed so staff can investigate, without ever auto-punishing anyone.
 */
class AntiDupeManager(private val plugin: Joshymc) {

    enum class RiskLevel(val label: String, val color: Int, val configKey: String) {
        LOW("Low", 0xFEE75C, "low"),
        MEDIUM("Medium", 0xFFA500, "medium"),
        HIGH("High", 0xED4245, "high"),
        CRITICAL("Critical", 0x8B0000, "critical")
    }

    private data class RateBucket(var count: Int, val windowStart: Long)

    private val rateBuckets = ConcurrentHashMap<String, RateBucket>()

    private val enabled: Boolean get() = plugin.config.getBoolean("anti-dupe.enabled", true)
    private val discordEnabled: Boolean get() = plugin.config.getBoolean("anti-dupe.discord.enabled", true)
    private val discordChannelId: String get() = plugin.config.getString("anti-dupe.discord.channel-id") ?: ""
    private val rateLimitSeconds: Long get() = plugin.config.getLong("anti-dupe.rate-limit-seconds", 10L)

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS anti_dupe_detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                detection_type TEXT NOT NULL,
                risk_level TEXT NOT NULL,
                item TEXT,
                amount TEXT,
                source TEXT,
                destination TEXT,
                transaction_id TEXT,
                world TEXT,
                location TEXT,
                timestamp INTEGER NOT NULL,
                resolved INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    private fun alertAllowed(risk: RiskLevel): Boolean =
        plugin.config.getBoolean("anti-dupe.alerts.${risk.configKey}", risk != RiskLevel.LOW)

    /**
     * Records a suspected duplication/replay incident for staff review. Detection only —
     * never bans, mutes, or reverses anything. Safe to call from the main thread; the DB
     * write is a single small INSERT and the Discord post is queued (async, non-blocking).
     */
    fun record(
        player: Player,
        detectionType: String,
        risk: RiskLevel,
        item: String? = null,
        amount: String? = null,
        source: String? = null,
        destination: String? = null,
        transactionId: String? = null
    ) {
        if (!enabled) return

        val now = System.currentTimeMillis()
        val world = player.world.name
        val location = "X: ${player.location.blockX} Y: ${player.location.blockY} Z: ${player.location.blockZ}"

        plugin.databaseManager.execute(
            """INSERT INTO anti_dupe_detections
               (uuid, player_name, detection_type, risk_level, item, amount, source, destination, transaction_id, world, location, timestamp, resolved)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
            player.uniqueId.toString(), player.name, detectionType, risk.name,
            item, amount, source, destination, transactionId, world, location, now
        )

        if (risk == RiskLevel.HIGH || risk == RiskLevel.CRITICAL) {
            plugin.logger.warning(
                "[AntiDupe] [${risk.label.uppercase()}] $detectionType — ${player.name} (${player.uniqueId})" +
                    (item?.let { " — $it" } ?: "") + (transactionId?.let { " — tx=$it" } ?: "")
            )
        }

        if (!discordEnabled || discordChannelId.isEmpty() || !alertAllowed(risk)) return
        queueDiscordAlert(player, detectionType, risk, item, amount, source, destination, transactionId, world, location, now)
    }

    /**
     * Rate-limits repeats of the exact same (player, detection type) pair. The first
     * occurrence in a window is always posted immediately — distinct incidents (different
     * type, different player) are never suppressed. If more repeats land inside the same
     * window, a single grouped follow-up embed reports the total instead of spamming.
     */
    private fun queueDiscordAlert(
        player: Player,
        detectionType: String,
        risk: RiskLevel,
        item: String?,
        amount: String?,
        source: String?,
        destination: String?,
        transactionId: String?,
        world: String,
        location: String,
        timestamp: Long
    ) {
        val key = "${player.uniqueId}|$detectionType"
        val bucket = rateBuckets.compute(key) { _, existing ->
            if (existing == null || timestamp - existing.windowStart > rateLimitSeconds * 1000L) {
                RateBucket(1, timestamp)
            } else {
                existing.count++
                existing
            }
        }!!

        if (bucket.count > 1) return // within the window and already alerted — grouped follow-up covers the rest

        val embed = EmbedBuilder()
            .setTitle("Possible Duplication Detected")
            .setColor(risk.color)
            .addField("Player", player.name, true)
            .addField("UUID", player.uniqueId.toString(), true)
            .addField("Risk Level", risk.label, true)
            .addField("Detection Type", detectionType, false)
            .apply {
                if (!item.isNullOrBlank()) addField("Item", item, true)
                if (!amount.isNullOrBlank()) addField("Amount", amount, true)
                if (!source.isNullOrBlank()) addField("Source", source, true)
                if (!destination.isNullOrBlank()) addField("Destination", destination, true)
                if (!transactionId.isNullOrBlank()) addField("Transaction ID", transactionId, true)
                addField("World", world, true)
                addField("Location", location, true)
            }
            .setTimestamp(Instant.ofEpochMilli(timestamp))
            .build()
        plugin.discordManager.sendEmbedToChannel(discordChannelId, embed)

        if (rateLimitSeconds > 0) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                val finalBucket = rateBuckets.remove(key)
                if (finalBucket != null && finalBucket.count > 1) {
                    sendGroupedFollowUp(player.uniqueId, player.name, detectionType, finalBucket.count)
                }
            }, rateLimitSeconds * 20L)
        }
    }

    private fun sendGroupedFollowUp(uuid: UUID, playerName: String, detectionType: String, count: Int) {
        val embed = EmbedBuilder()
            .setTitle("Repeated Anti-Dupe Alerts Suppressed")
            .setColor(0xFFA500)
            .setDescription("**$playerName** triggered **$detectionType** **$count** times in the last $rateLimitSeconds seconds.")
            .addField("UUID", uuid.toString(), true)
            .setTimestamp(Instant.now())
            .build()
        plugin.discordManager.sendEmbedToChannel(discordChannelId, embed)
    }
}
