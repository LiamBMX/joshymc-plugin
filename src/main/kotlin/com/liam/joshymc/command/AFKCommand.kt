package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.afk.AfkSpawnRules
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class AFKCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("setspawn", ignoreCase = true)) {
            setSpawn(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.afk")) {
            Msg.noPermission(sender, CommunicationsManager.Category.AFK)
            return true
        }

        // Going AFK: combat-tagged and ranked players are refused inside enterAfk (issue #1596).
        // Leaving AFK is always allowed.
        plugin.afkManager.toggleAfk(sender)
        return true
    }

    /** `/afk setspawn` (issue #1596): saves the sender's position for the gamemode they're in. */
    private fun setSpawn(player: Player) {
        if (!player.hasPermission(SETSPAWN_PERMISSION)) {
            Msg.noPermission(player, CommunicationsManager.Category.AFK)
            return
        }
        val spawn = plugin.afkManager.setSpawnHere(player) ?: return
        plugin.commsManager.send(player, Component.text(AfkSpawnRules.formatSaved(spawn), NamedTextColor.GREEN), CommunicationsManager.Category.AFK)
        AdminAudit.log(
            plugin, player, "AFK_SETSPAWN",
            details = "gamemode=${spawn.gamemode.id} world=${spawn.world} x=${spawn.x} y=${spawn.y} z=${spawn.z} yaw=${spawn.yaw} pitch=${spawn.pitch}"
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1 || !sender.hasPermission(SETSPAWN_PERMISSION)) return emptyList()
        return listOf("setspawn").filter { it.startsWith(args[0].lowercase()) }
    }

    companion object {
        const val SETSPAWN_PERMISSION = "joshymc.afk.setspawn"
    }
}
