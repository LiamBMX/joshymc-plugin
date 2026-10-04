package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scheduler.BukkitTask

/**
 * One shared BossBar that rotates through the `rotating-bossbar.messages` list and,
 * optionally, drains from full to empty over each message's display interval.
 * A single repeating task drives it; every online player is a viewer of the same bar,
 * so joiners see the current message at its current progress.
 */
class RotatingBossBarManager(private val plugin: Joshymc) : Listener {

    private var task: BukkitTask? = null
    private var bar: BossBar? = null
    private var messages: List<String> = emptyList()
    private var index = 0
    private var cycleStartMs = 0L
    private var intervalMs = 10_000L
    private var countdown = true

    fun start() {
        stop()
        if (!plugin.config.getBoolean("rotating-bossbar.enabled", true)) return

        messages = plugin.config.getStringList("rotating-bossbar.messages").filter { it.isNotBlank() }
        if (messages.isEmpty()) {
            plugin.logger.info("[RotatingBossBar] No messages configured, skipping.")
            return
        }

        intervalMs = plugin.config.getInt("rotating-bossbar.interval-seconds", 10).coerceAtLeast(1) * 1000L
        countdown = plugin.config.getBoolean("rotating-bossbar.countdown-enabled", true)
        val color = parseColor(plugin.config.getString("rotating-bossbar.color"))
        val overlay = parseOverlay(plugin.config.getString("rotating-bossbar.style"))

        index = 0
        cycleStartMs = System.currentTimeMillis()
        val created = BossBar.bossBar(render(0), 1.0f, color, overlay)
        bar = created
        for (player in Bukkit.getOnlinePlayers()) player.showBossBar(created)

        plugin.server.pluginManager.registerEvents(this, plugin)
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 2L, 2L)
        plugin.logger.info("[RotatingBossBar] Rotating ${messages.size} messages every ${intervalMs / 1000}s.")
    }

    fun stop() {
        task?.cancel()
        task = null
        HandlerList.unregisterAll(this)
        bar?.let { b -> for (player in Bukkit.getOnlinePlayers()) player.hideBossBar(b) }
        bar = null
    }

    private fun tick() {
        val b = bar ?: return
        val now = System.currentTimeMillis()
        var elapsed = now - cycleStartMs
        if (elapsed >= intervalMs) {
            val steps = (elapsed / intervalMs).toInt()
            index = (index + steps) % messages.size
            cycleStartMs += steps * intervalMs
            elapsed = now - cycleStartMs
            b.name(render(index))
        }
        val progress = if (countdown) 1.0f - elapsed.toFloat() / intervalMs else 1.0f
        b.progress(progress.coerceIn(0.0f, 1.0f))
    }

    private fun render(i: Int) = plugin.commsManager.parseLegacy(messages[i])

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        bar?.let { event.player.showBossBar(it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        bar?.let { event.player.hideBossBar(it) }
    }

    private fun parseColor(name: String?): BossBar.Color =
        try {
            BossBar.Color.valueOf(name?.trim()?.uppercase() ?: "YELLOW")
        } catch (_: Exception) {
            plugin.logger.warning("[RotatingBossBar] Unknown color: $name")
            BossBar.Color.YELLOW
        }

    /** Maps Bukkit-style BarStyle names (SOLID, SEGMENTED_6, ...) to Adventure overlays. */
    private fun parseOverlay(name: String?): BossBar.Overlay = when (name?.trim()?.uppercase()) {
        null, "", "SOLID", "PROGRESS" -> BossBar.Overlay.PROGRESS
        "SEGMENTED_6", "NOTCHED_6" -> BossBar.Overlay.NOTCHED_6
        "SEGMENTED_10", "NOTCHED_10" -> BossBar.Overlay.NOTCHED_10
        "SEGMENTED_12", "NOTCHED_12" -> BossBar.Overlay.NOTCHED_12
        "SEGMENTED_20", "NOTCHED_20" -> BossBar.Overlay.NOTCHED_20
        else -> {
            plugin.logger.warning("[RotatingBossBar] Unknown style: $name")
            BossBar.Overlay.PROGRESS
        }
    }
}
