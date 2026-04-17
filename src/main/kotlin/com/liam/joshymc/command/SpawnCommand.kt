package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SpawnCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
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
}
