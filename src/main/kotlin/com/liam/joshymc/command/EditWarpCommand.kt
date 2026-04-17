package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class EditWarpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.editwarp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /editwarp <name>", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            plugin.commsManager.send(sender, Component.text("Hold the item you want as the warp icon.", NamedTextColor.GRAY), CommunicationsManager.Category.WARP)
            return true
        }

        val name = args[0].lowercase()
        val held = sender.inventory.itemInMainHand

        if (held.type.isAir) {
            plugin.commsManager.send(sender, Component.text("Hold the item you want as the warp icon.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        val materialName = held.type.name

        if (plugin.warpManager.setWarpIcon(name, materialName)) {
            plugin.commsManager.send(sender,
                Component.text("Warp '$name' icon set to ", NamedTextColor.GREEN)
                    .append(Component.text(materialName, NamedTextColor.WHITE)),
                CommunicationsManager.Category.WARP
            )
        } else {
            plugin.commsManager.send(sender, Component.text("Server warp '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return plugin.warpManager.getAllWarps().map { it.name }.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
