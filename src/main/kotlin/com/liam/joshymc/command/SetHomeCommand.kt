package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SetHomeCommand(private val plugin: Joshymc) : CommandExecutor {

    companion object {
        private val NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,20}$")
    }

    // Returns -1 for unlimited, otherwise the max homes allowed for this player's rank.
    private fun homeLimit(player: org.bukkit.entity.Player): Int {
        val rankId = plugin.rankManager.getPlayerRank(player)?.id ?: "default"
        val rankLimit = plugin.config.getInt("homes.limits-by-rank.$rankId", Int.MIN_VALUE)
        return if (rankLimit != Int.MIN_VALUE) rankLimit
               else plugin.config.getInt("homes.max-per-player", 3)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        val name = (args.getOrNull(0) ?: "home").lowercase()
        val uuid = sender.uniqueId.toString()

        if (!NAME_REGEX.matches(name)) {
            plugin.commsManager.send(sender, Component.text("Home names must be alphanumeric/underscores, max 20 characters.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        if (!sender.hasPermission("joshymc.sethome.unlimited")) {
            val max = homeLimit(sender)
            if (max != -1) {
                val current = plugin.warpManager.getHomeCount(uuid)
                val isOverwrite = plugin.warpManager.getHome(uuid, name) != null
                if (current >= max && !isOverwrite) {
                    plugin.commsManager.send(sender, Component.text("You have reached the home limit ($max).", NamedTextColor.RED), CommunicationsManager.Category.HOME)
                    return true
                }
            }
        }

        plugin.warpManager.setHome(uuid, name, sender.location)
        plugin.commsManager.send(sender, Component.text("Home '$name' set.", NamedTextColor.GREEN), CommunicationsManager.Category.HOME)
        return true
    }
}
