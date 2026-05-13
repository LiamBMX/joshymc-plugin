package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class RestartCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private var countdownTaskId = -1
    private var secondsRemaining = 0

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.restart")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].equals("cancel", ignoreCase = true)) {
            if (countdownTaskId == -1) {
                sender.sendMessage(Component.text("No restart is scheduled.", NamedTextColor.RED))
            } else {
                cancelCountdown()
                broadcastAll(Component.text("Server restart has been cancelled.", NamedTextColor.GREEN))
            }
            return true
        }

        if (countdownTaskId != -1) {
            sender.sendMessage(Component.text("A restart is already scheduled. Use /restart cancel to cancel it.", NamedTextColor.RED))
            return true
        }

        val delaySecs = args.getOrNull(0)?.toIntOrNull()?.takeIf { it > 0 } ?: 300
        secondsRemaining = delaySecs

        broadcastAll(Component.text("Server restarting in ${formatTime(secondsRemaining)}!", NamedTextColor.RED))

        countdownTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            secondsRemaining--
            when (secondsRemaining) {
                240 -> broadcastAll(Component.text("Server restarting in 4 minutes!", NamedTextColor.YELLOW))
                180 -> broadcastAll(Component.text("Server restarting in 3 minutes!", NamedTextColor.YELLOW))
                120 -> broadcastAll(Component.text("Server restarting in 2 minutes!", NamedTextColor.YELLOW))
                60  -> broadcastAll(Component.text("Server restarting in 1 minute!", NamedTextColor.YELLOW))
                30  -> broadcastAll(Component.text("Server restarting in 30 seconds!", NamedTextColor.GOLD))
                10  -> broadcastAll(Component.text("Server restarting in 10 seconds!", NamedTextColor.GOLD))
                5, 4, 3, 2, 1 -> {
                    broadcastAll(Component.text("Server restarting in $secondsRemaining second${if (secondsRemaining != 1) "s" else ""}!", NamedTextColor.RED))
                    playCountdownSound()
                }
                0 -> {
                    cancelCountdown()
                    broadcastAll(Component.text("Server is restarting now. See you soon!", NamedTextColor.RED))
                    val kickMessage = Component.text("Server is restarting. Back in a moment!", NamedTextColor.RED)
                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        for (player in Bukkit.getOnlinePlayers()) {
                            player.kick(kickMessage)
                        }
                        plugin.server.spigot().restart()
                    }, 5L)
                }
            }
        }, 20L, 20L)

        return true
    }

    private fun cancelCountdown() {
        if (countdownTaskId != -1) {
            Bukkit.getScheduler().cancelTask(countdownTaskId)
            countdownTaskId = -1
        }
        secondsRemaining = 0
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
        val mins = seconds / 60
        return if (mins > 0) "$mins minute${if (mins != 1) "s" else ""}" else "$seconds second${if (seconds != 1) "s" else ""}"
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.restart")) return emptyList()
        if (args.size == 1) {
            val options = buildList {
                if (countdownTaskId != -1) add("cancel")
                if (countdownTaskId == -1) addAll(listOf("30", "60", "120", "300"))
            }
            return options.filter { it.startsWith(args[0]) }
        }
        return emptyList()
    }
}
