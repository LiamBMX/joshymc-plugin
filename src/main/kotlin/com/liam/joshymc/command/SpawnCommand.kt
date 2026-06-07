package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.WorldFlagManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SpawnCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private fun isAdmin(sender: CommandSender): Boolean {
        return sender.hasPermission("joshymc.spawn.admin") || (sender is Player && sender.isOp)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty()) {
            if (!isAdmin(sender)) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                return true
            }

            val sub = args[0].lowercase()
            val flag = when (sub) {
                "drops" -> WorldFlagManager.WorldFlag.ITEM_DROP
                "containers" -> WorldFlagManager.WorldFlag.CONTAINER
                else -> {
                    sender.sendMessage(Component.text("Usage: /spawn [drops|containers] [on|off]", NamedTextColor.RED))
                    return true
                }
            }

            if (args.size < 2) {
                val current = plugin.worldFlagManager.getFlag("spawn", flag)
                sender.sendMessage(Component.text("Spawn ${flag.displayName} is currently ${if (current) "on" else "off"}.", NamedTextColor.YELLOW))
                return true
            }

            val value = when (args[1].lowercase()) {
                "on", "true" -> true
                "off", "false" -> false
                else -> {
                    sender.sendMessage(Component.text("Usage: /spawn $sub <on|off>", NamedTextColor.RED))
                    return true
                }
            }

            plugin.worldFlagManager.setFlag("spawn", flag, value)
            sender.sendMessage(Component.text("Spawn ${flag.displayName} is now ${if (value) "on" else "off"}.", NamedTextColor.GREEN))
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (TeleportChecks.checkAndApply(sender, plugin)) return true

        val spawn = plugin.warpManager.getSpawn()
            ?: org.bukkit.Bukkit.getWorld("spawn")?.spawnLocation
            ?: org.bukkit.Bukkit.getWorlds()[0].spawnLocation

        TeleportChecks.teleportWithWarmup(sender, spawn, plugin)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (!isAdmin(sender)) return emptyList()
        return when (args.size) {
            1 -> listOf("drops", "containers").filter { it.startsWith(args[0].lowercase()) }
            2 -> listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
            else -> emptyList()
        }
    }
}
