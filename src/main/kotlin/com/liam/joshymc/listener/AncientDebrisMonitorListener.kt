package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.discord.SafeEmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Ancient Debris mining monitor (issue #999) — logs every Ancient Debris a player breaks to
 * Discord and raises a one-shot "suspicious" / "high" alert when a player breaks too many
 * inside a rolling window, so staff can review for X-ray. Monitoring only: never cancels
 * mining or kicks/bans/punishes anyone.
 *
 * Routine logs are batched (issue #1159): each player's breaks are collected and posted as
 * one summary per `batch-interval-seconds` (count, worlds, recent locations, time window),
 * so vein mining no longer posts an embed per block. The X-ray alerts are never batched;
 * they go out the moment a threshold is crossed. [stop] posts whatever is still collected.
 *
 * Only [BlockBreakEvent] is used, which Bukkit fires solely for player breaks — explosions,
 * pistons, and world generation never reach it, and no chunks or nearby blocks are scanned.
 * All state is touched on the main thread; Discord sends go through
 * [com.liam.joshymc.discord.DiscordManager.sendEmbedToChannel], which queues onto the
 * existing async-flushed bot connection.
 */
class AncientDebrisMonitorListener(private val plugin: Joshymc) : Listener {

    private data class Mined(val timeMs: Long, val world: String, val x: Int, val y: Int, val z: Int)

    private class PlayerState {
        val recent = ArrayDeque<Mined>()
        var suspiciousSent = false
        var highSent = false
    }

    private val states = mutableMapOf<UUID, PlayerState>()

    /** One player's routine breaks since the last summary. Bounded: counts plus [MAX_LISTED_LOCATIONS] samples. */
    private class Batch(val name: String, val firstMs: Long) {
        var lastMs = firstMs
        var count = 0
        val perWorld = linkedMapOf<String, Int>()
        val recent = ArrayDeque<Mined>()
    }

    /** Insertion-ordered so summaries post in the order players started mining. */
    private val batches = linkedMapOf<UUID, Batch>()
    private var flushTask: BukkitTask? = null

    private val enabled: Boolean get() = plugin.config.getBoolean("ancient-debris-monitor.enabled", true)
    private val channelId: String get() = plugin.config.getString("ancient-debris-monitor.discord-channel-id") ?: ""
    private val windowMs: Long
        get() = plugin.config.getLong("ancient-debris-monitor.window-minutes", 10L).coerceAtLeast(1L) * 60_000L
    private val suspiciousThreshold: Int
        get() = plugin.config.getInt("ancient-debris-monitor.suspicious-threshold", 8)
    private val highThreshold: Int
        get() = plugin.config.getInt("ancient-debris-monitor.high-suspicion-threshold", 15)
    /** 0 posts every block on its own, as before batching. */
    private val batchIntervalSeconds: Long
        get() = plugin.config.getLong("ancient-debris-monitor.batch-interval-seconds", 60L).let { if (it <= 0L) 0L else it.coerceIn(10L, 3600L) }

    fun start() {
        val seconds = batchIntervalSeconds
        if (seconds > 0L) {
            flushTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { flushBatches() }, seconds * 20L, seconds * 20L)
        }
    }

    /** Cancels the summary task and posts what was collected, so a reload or shutdown loses no breaks. */
    fun stop() {
        flushTask?.cancel()
        flushTask = null
        flushBatches()
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.ANCIENT_DEBRIS) return
        // No early return on an empty channelId: the X-ray alert still goes to the staff anti-cheat channel.
        if (!enabled) return

        val player = event.player
        val block = event.block
        val now = System.currentTimeMillis()
        val entry = Mined(now, block.world.name, block.x, block.y, block.z)

        logMined(player, entry)

        val state = states.getOrPut(player.uniqueId) { PlayerState() }
        val cutoff = now - windowMs
        while (state.recent.isNotEmpty() && state.recent.first().timeMs < cutoff) state.recent.removeFirst()
        state.recent.addLast(entry)

        val count = state.recent.size
        val high = highThreshold
        val suspicious = suspiciousThreshold

        // Re-arm once the rolling count falls back under a threshold, so each fires once per burst.
        if (count < suspicious) state.suspiciousSent = false
        if (count < high) state.highSent = false

        if (count >= high && !state.highSent) {
            state.highSent = true
            state.suspiciousSent = true
            sendAlert(player.name, state, count, high = true)
        } else if (count >= suspicious && !state.suspiciousSent && !state.highSent) {
            state.suspiciousSent = true
            sendAlert(player.name, state, count, high = false)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        states.remove(event.player.uniqueId)
    }

    private fun sendAlert(playerName: String, state: PlayerState, count: Int, high: Boolean) {
        val minutes = windowMs / 60_000L
        val worlds = state.recent.map { it.world }.distinct().joinToString(", ")
        val locations = state.recent.takeLast(MAX_LISTED_LOCATIONS).joinToString("\n") { "- ${coords(it)}" }

        val embed = SafeEmbedBuilder()
            .setTitle(if (high) "🚨 HIGH: Suspicious Ancient Debris Mining" else "⚠️ Suspicious Ancient Debris Mining")
            .setColor(if (high) 0xED4245 else 0xFEE75C)
            .addField("Detection", "X-ray (Ancient Debris)", true)
            .addField("Player", playerName, true)
            .addField("World", worlds, true)
            .addField("Count", "$count Ancient Debris in $minutes minutes", false)
            .addField("Recent Locations", locations, false)
            .setFooter("Manual staff review required.")
            .setTimestamp(Instant.now())
            .build()

        plugin.discordManager.sendEmbedToChannel(channelId, embed)
        plugin.discordManager.sendStaffAlert(embed, channelId)
    }

    /** Routine log: posted right away with batching off, otherwise added to the player's batch. */
    private fun logMined(player: Player, entry: Mined) {
        if (channelId.isEmpty()) return
        if (flushTask == null) {
            val time = ZonedDateTime.now(plugin.timezoneManager.zoneFor(player)).format(TIME_FORMAT)
            plugin.discordManager.sendEmbedToChannel(
                channelId,
                SafeEmbedBuilder()
                    .setTitle("⛏️ Ancient Debris Mined")
                    .setColor(0x99572B)
                    .addField("Player", "${player.name} (`${player.uniqueId}`)", false)
                    .addField("World", entry.world, true)
                    .addField("Location", coords(entry), true)
                    .addField("Time", time, true)
                    .setTimestamp(Instant.now())
                    .build()
            )
            return
        }
        val batch = batches.getOrPut(player.uniqueId) { Batch(player.name, entry.timeMs) }
        batch.count++
        batch.lastMs = entry.timeMs
        batch.perWorld.merge(entry.world, 1, Int::plus)
        batch.recent.addLast(entry)
        if (batch.recent.size > MAX_LISTED_LOCATIONS) batch.recent.removeFirst()
    }

    /** One summary embed per player with breaks since the last flush; the batches are cleared first, so nothing posts twice. */
    private fun flushBatches() {
        if (batches.isEmpty()) return
        val pending = batches.toList()
        batches.clear()
        val channel = channelId
        if (channel.isEmpty()) return
        for ((uuid, batch) in pending) {
            try {
                plugin.discordManager.sendEmbedToChannel(channel, summaryEmbed(uuid, batch))
            } catch (e: Exception) {
                plugin.logger.warning("[AncientDebris] Could not post the mining summary for ${batch.name}: ${e.message}")
            }
        }
    }

    private fun summaryEmbed(uuid: UUID, batch: Batch): MessageEmbed {
        val first = batch.firstMs / 1000
        val last = batch.lastMs / 1000
        // Discord timestamps render in each viewer's own timezone.
        val window = if (last <= first) "<t:$first:t>" else "<t:$first:t> - <t:$last:t>"
        val worlds = batch.perWorld.entries.joinToString(", ") { (world, n) -> if (batch.perWorld.size == 1) world else "$world ($n)" }
        val multiWorld = batch.perWorld.size > 1
        val shown = batch.recent.joinToString("\n") { "- ${coords(it)}" + if (multiWorld) " (${it.world})" else "" }
        val hidden = batch.count - batch.recent.size
        val locations = if (hidden > 0) "$shown\n...and $hidden earlier" else shown

        return SafeEmbedBuilder()
            .setTitle("⛏️ Ancient Debris Mined")
            .setColor(0x99572B)
            .addField("Player", "${batch.name} (`$uuid`)", false)
            .addField("Count", batch.count.toString(), true)
            .addField(if (multiWorld) "Worlds" else "World", worlds, true)
            .addField("Time", window, true)
            .addField(if (batch.count == 1) "Location" else "Locations", locations, false)
            .setTimestamp(Instant.ofEpochMilli(batch.lastMs))
            .build()
    }

    private fun coords(m: Mined) = "X: ${m.x}, Y: ${m.y}, Z: ${m.z}"

    companion object {
        private const val MAX_LISTED_LOCATIONS = 10
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
