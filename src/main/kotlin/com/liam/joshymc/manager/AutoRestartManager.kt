package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Sound

class AutoRestartManager(private val plugin: Joshymc) {

    private var scheduleTaskId = -1
    private var countdownTaskId = -1
    private var secondsRemaining = 0
    // Set once the countdown hits zero, covering the few ticks before the server goes down
    private var restarting = false

    // Warning broadcasts at these times before restart (seconds)
    private val warningPoints = setOf(3600, 1800, 900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1)

    fun start() {
        val enabled = plugin.config.getBoolean("auto-restart.enabled", true)
        if (!enabled) return

        val intervalHours = plugin.config.getDouble("auto-restart.interval-hours", 12.0)
        val intervalTicks = (intervalHours * 3600 * 20).toLong()

        scheduleTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            if (!isRestartInProgress) {
                initiateCountdown()
            }
        }, intervalTicks, intervalTicks)

        plugin.logger.info("[AutoRestart] Scheduled restart every ${intervalHours}h.")
    }

    fun stop() {
        if (scheduleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(scheduleTaskId)
            scheduleTaskId = -1
        }
        cancelCountdown()
    }

    /** True from the first countdown broadcast until the server goes down. */
    val isRestartInProgress: Boolean
        get() = countdownTaskId != -1 || restarting

    /**
     * Starts the restart countdown now (`/jrestart`, issue #1102), running the same
     * sequence as the scheduled restart. Returns false if a restart is already in progress.
     */
    fun triggerRestart(): Boolean {
        if (isRestartInProgress) return false
        initiateCountdown()
        return true
    }

    private fun initiateCountdown() {
        val warningSeconds = plugin.config.getInt("auto-restart.warning-seconds", 300)
        secondsRemaining = warningSeconds

        broadcastAll(restartMessage("Server restarting in ${formatTime(secondsRemaining)}!"))

        countdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            secondsRemaining--
            when {
                secondsRemaining in warningPoints -> {
                    broadcastAll(restartMessage("Server restarting in ${formatTime(secondsRemaining)}!"))
                    if (secondsRemaining <= 10) playCountdownSound()
                }
                secondsRemaining <= 0 -> {
                    cancelCountdown()
                    restarting = true
                    broadcastAll(restartMessage("Server is restarting now. See you soon!"))
                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        val kickMessage = restartMessage("Server is restarting. Back in a moment!")
                        for (player in Bukkit.getOnlinePlayers()) {
                            player.kick(kickMessage)
                        }
                        plugin.server.spigot().restart()
                    }, 5L)
                }
            }
        }, 20L, 20L)
    }

    private fun cancelCountdown() {
        if (countdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(countdownTaskId)
            countdownTaskId = -1
        }
        secondsRemaining = 0
    }

    private fun restartMessage(text: String): Component {
        return Component.text(text, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD)
    }

    private fun broadcastAll(message: Component) {
        Bukkit.broadcast(message)
    }

    private fun playCountdownSound() {
        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
        }
    }

    private fun formatTime(seconds: Int): String {
        return when {
            seconds >= 3600 -> {
                val h = seconds / 3600
                "$h hour${if (h != 1) "s" else ""}"
            }
            seconds >= 60 -> {
                val m = seconds / 60
                "$m minute${if (m != 1) "s" else ""}"
            }
            else -> "$seconds second${if (seconds != 1) "s" else ""}"
        }
    }
}
