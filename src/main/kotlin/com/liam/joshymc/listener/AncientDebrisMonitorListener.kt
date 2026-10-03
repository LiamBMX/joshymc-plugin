package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.EmbedBuilder
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerQuitEvent
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

    private val enabled: Boolean get() = plugin.config.getBoolean("ancient-debris-monitor.enabled", true)
    private val channelId: String get() = plugin.config.getString("ancient-debris-monitor.discord-channel-id") ?: ""
    private val windowMs: Long
        get() = plugin.config.getLong("ancient-debris-monitor.window-minutes", 10L).coerceAtLeast(1L) * 60_000L
    private val suspiciousThreshold: Int
        get() = plugin.config.getInt("ancient-debris-monitor.suspicious-threshold", 8)
    private val highThreshold: Int
        get() = plugin.config.getInt("ancient-debris-monitor.high-suspicion-threshold", 15)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.ANCIENT_DEBRIS) return
        if (!enabled || channelId.isEmpty()) return

        val player = event.player
        val block = event.block
        val now = System.currentTimeMillis()
        val entry = Mined(now, block.world.name, block.x, block.y, block.z)

        val time = ZonedDateTime.now(plugin.timezoneManager.zoneFor(player)).format(TIME_FORMAT)
        plugin.discordManager.sendEmbedToChannel(
            channelId,
            EmbedBuilder()
                .setTitle("⛏️ Ancient Debris Mined")
                .setColor(0x99572B)
                .addField("Player", "${player.name} (`${player.uniqueId}`)", false)
                .addField("World", entry.world, true)
                .addField("Location", coords(entry), true)
                .addField("Time", time, true)
                .setTimestamp(Instant.now())
                .build()
        )

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

        val embed = EmbedBuilder()
            .setTitle(if (high) "🚨 HIGH: Suspicious Ancient Debris Mining" else "⚠️ Suspicious Ancient Debris Mining")
            .setColor(if (high) 0xED4245 else 0xFEE75C)
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

    private fun coords(m: Mined) = "X: ${m.x}, Y: ${m.y}, Z: ${m.z}"

    companion object {
        private const val MAX_LISTED_LOCATIONS = 10
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
