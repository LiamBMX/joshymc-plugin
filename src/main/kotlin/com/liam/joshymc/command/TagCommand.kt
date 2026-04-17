package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TagCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.tag")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "remove", "clear", "off" -> {
                plugin.chatTagManager.setPlayerTag(sender.uniqueId, null)
                plugin.commsManager.send(sender, Component.text("Chat tag removed.", NamedTextColor.GREEN))
            }
            null -> plugin.chatTagManager.openCategoryMenu(sender)
            else -> {
                // Try to equip by tag ID directly
                val tag = plugin.chatTagManager.getTag(args[0].lowercase())
                if (tag != null) {
                    if (!plugin.chatTagManager.canUse(sender, tag)) {
                        plugin.commsManager.send(sender, Component.text("You don't have permission to use this tag.", NamedTextColor.RED))
                        return true
                    }
                    plugin.chatTagManager.setPlayerTag(sender.uniqueId, tag.id)
                    plugin.commsManager.send(sender,
                        Component.text("Tag set to ", NamedTextColor.GREEN)
                            .append(plugin.commsManager.parseLegacy(tag.display.trimEnd()))
                    )
                } else {
                    // Open the GUI
                    plugin.chatTagManager.openCategoryMenu(sender)
                }
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val base = listOf("remove")
            val tagIds = plugin.chatTagManager.getAllTags().map { it.id }
            return (base + tagIds).filter { it.startsWith(args[0].lowercase()) }.take(30)
        }
        return emptyList()
    }
}
