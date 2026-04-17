package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class NPCCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9_]{1,30}$")
        private val SUBCOMMANDS = listOf("create", "delete", "setcommand", "setname", "setskin", "move", "list")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.npc")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> handleCreate(sender, args)
            "delete" -> handleDelete(sender, args)
            "setcommand" -> handleSetCommand(sender, args)
            "setname" -> handleSetName(sender, args)
            "setskin" -> handleSetSkin(sender, args)
            "move" -> handleMove(sender, args)
            "list" -> handleList(sender)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun handleCreate(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /npc create <id> <name...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        if (!ID_REGEX.matches(id)) {
            plugin.commsManager.send(player, Component.text("ID must be alphanumeric/underscores, max 30 characters.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        if (id in plugin.npcManager.getNPCIds()) {
            plugin.commsManager.send(player, Component.text("NPC '$id' already exists. Delete it first.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val name = args.drop(2).joinToString(" ")
        plugin.npcManager.createNPC(id, name, player.location)
        plugin.commsManager.send(player, Component.text("NPC '$id' created at your location.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleDelete(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /npc delete <id>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        if (plugin.npcManager.deleteNPC(id)) {
            plugin.commsManager.send(player, Component.text("NPC '$id' deleted.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("NPC '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleSetCommand(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /npc setcommand <id> <command...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val cmd = args.drop(2).joinToString(" ")

        if (plugin.npcManager.setCommand(id, cmd)) {
            plugin.commsManager.send(player, Component.text("Command for NPC '$id' set to: $cmd", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("NPC '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleSetName(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /npc setname <id> <name...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val name = args.drop(2).joinToString(" ")

        if (plugin.npcManager.setName(id, name)) {
            plugin.commsManager.send(player, Component.text("Name for NPC '$id' updated.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("NPC '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleSetSkin(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /npc setskin <id> <playerName>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val playerName = args[2]

        if (plugin.npcManager.setSkin(id, playerName)) {
            plugin.commsManager.send(player, Component.text("NPC '$id' now uses ${playerName}'s skin.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("NPC '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleMove(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /npc move <id>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        if (plugin.npcManager.moveNPC(id, player.location)) {
            plugin.commsManager.send(player, Component.text("NPC '$id' moved to your location.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("NPC '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleList(player: Player) {
        val ids = plugin.npcManager.getNPCIds()
        if (ids.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No NPCs exist.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("NPCs (${ids.size}):", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        for (id in ids) {
            plugin.commsManager.send(player, Component.text(" - $id", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("NPC Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        val usages = listOf(
            "/npc create <id> <name...>",
            "/npc delete <id>",
            "/npc setcommand <id> <command...>",
            "/npc setname <id> <name...>",
            "/npc setskin <id> <playerName>",
            "/npc move <id>",
            "/npc list"
        )
        for (usage in usages) {
            player.sendMessage(
                Component.text("  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(usage, NamedTextColor.GRAY))
            )
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.npc")) return emptyList()

        return when (args.size) {
            1 -> SUBCOMMANDS.filter { it.startsWith(args[0].lowercase()) }
            2 -> {
                val sub = args[0].lowercase()
                if (sub in listOf("delete", "setcommand", "setname", "setskin", "move")) {
                    plugin.npcManager.getNPCIds().filter { it.startsWith(args[1].lowercase()) }
                } else emptyList()
            }
            3 -> {
                if (args[0].equals("setskin", ignoreCase = true)) {
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
