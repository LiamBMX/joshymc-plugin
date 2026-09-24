package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PvpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()

        // /pvp elytra <on|off> and /pvp enderpearl <on|off> — ops/console only
        if (sub == "elytra" || sub == "enderpearl") {
            if (sender is Player && !sender.isOp) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
                return true
            }
            val onOff = args.getOrNull(1)?.lowercase()
            val enable = when (onOff) {
                "on" -> true
                "off" -> false
                else -> {
                    val usage = Component.text("Usage: /pvp $sub <on|off>", NamedTextColor.RED)
                    if (sender is Player) plugin.commsManager.send(sender, usage, CommunicationsManager.Category.COMBAT)
                    else sender.sendMessage(usage)
                    return true
                }
            }
            if (sub == "elytra") {
                plugin.combatManager.allowElytraInCombat = enable
                plugin.config.set("combat.allow-elytra", enable)
            } else {
                plugin.combatManager.allowEnderpearlInCombat = enable
                plugin.config.set("combat.allow-enderpearl", enable)
            }
            plugin.saveConfig()
            val itemName = if (sub == "elytra") "Elytra" else "Ender pearl"
            val status = if (enable) Component.text("enabled", NamedTextColor.GREEN) else Component.text("disabled", NamedTextColor.RED)
            val msg = Component.text("$itemName in combat: ", NamedTextColor.GRAY).append(status)
            if (sender is Player) plugin.commsManager.send(sender, msg, CommunicationsManager.Category.COMBAT)
            else sender.sendMessage(msg)
            return true
        }

        // /pvp [on|off] — player-only PvP toggle
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.pvp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
            return true
        }

        // Can't toggle while in combat
        if (plugin.combatManager.isTagged(sender)) {
            plugin.commsManager.send(sender, Component.text("You cannot toggle PvP while in combat!", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
            return true
        }

        val newValue = when (sub) {
            "on" -> true
            "off" -> false
            else -> !plugin.settingsManager.getSetting(sender, CombatManager.PVP_SETTING_KEY)
        }

        plugin.settingsManager.setSetting(sender, CombatManager.PVP_SETTING_KEY, newValue)

        val status = if (newValue) Component.text("enabled", NamedTextColor.GREEN)
        else Component.text("disabled", NamedTextColor.RED)
        plugin.commsManager.send(sender, Component.text("PvP ", NamedTextColor.GRAY).append(status), CommunicationsManager.Category.COMBAT)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val options = mutableListOf("on", "off")
            if (sender is Player && sender.isOp || sender !is Player) {
                options.addAll(listOf("elytra", "enderpearl"))
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && (args[0].equals("elytra", ignoreCase = true) || args[0].equals("enderpearl", ignoreCase = true))) {
            return listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
