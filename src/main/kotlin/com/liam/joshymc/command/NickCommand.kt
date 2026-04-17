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

class NickCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        when (args.getOrNull(0)?.lowercase()) {
            "set" -> {
                if (!sender.hasPermission("joshymc.nick")) {
                    plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                val nick = args.drop(1).joinToString(" ")
                if (nick.isBlank()) {
                    plugin.commsManager.send(sender, Component.text("Usage: /nick set <nickname>", NamedTextColor.RED))
                    return true
                }
                if (nick.replace("&[0-9a-fk-or]".toRegex(), "").length > 24) {
                    plugin.commsManager.send(sender, Component.text("Nickname too long (max 24 characters).", NamedTextColor.RED))
                    return true
                }
                plugin.databaseManager.execute(
                    "INSERT OR REPLACE INTO nicknames (uuid, nickname) VALUES (?, ?)",
                    sender.uniqueId.toString(), nick
                )
                val display = plugin.commsManager.parseLegacy(nick)
                sender.displayName(display)
                sender.playerListName(display)
                plugin.commsManager.send(sender, Component.text("Nickname set to ", NamedTextColor.GREEN).append(display))
            }
            "reset", "off", "clear" -> {
                plugin.databaseManager.execute("DELETE FROM nicknames WHERE uuid = ?", sender.uniqueId.toString())
                sender.displayName(Component.text(sender.name))
                sender.playerListName(Component.text(sender.name))
                plugin.commsManager.send(sender, Component.text("Nickname cleared.", NamedTextColor.GREEN))
            }
            "other" -> {
                // /nick other <player> <nick> — admin set someone else's nick
                if (!sender.hasPermission("joshymc.nick.others")) {
                    plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                val targetName = args.getOrNull(1)
                val nick = args.drop(2).joinToString(" ")
                if (targetName == null || nick.isBlank()) {
                    plugin.commsManager.send(sender, Component.text("Usage: /nick other <player> <nickname>", NamedTextColor.RED))
                    return true
                }
                val target = Bukkit.getPlayer(targetName)
                if (target == null) {
                    plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                    return true
                }
                plugin.databaseManager.execute(
                    "INSERT OR REPLACE INTO nicknames (uuid, nickname) VALUES (?, ?)",
                    target.uniqueId.toString(), nick
                )
                val display = plugin.commsManager.parseLegacy(nick)
                target.displayName(display)
                target.playerListName(display)
                plugin.commsManager.send(sender, Component.text("Set ${target.name}'s nickname to ", NamedTextColor.GREEN).append(display))
            }
            else -> {
                plugin.commsManager.send(sender,
                    Component.text("/nick set <name>", NamedTextColor.YELLOW)
                        .append(Component.text(" — set your nickname\n", NamedTextColor.GRAY))
                        .append(Component.text("/nick reset", NamedTextColor.YELLOW))
                        .append(Component.text(" — clear your nickname\n", NamedTextColor.GRAY))
                        .append(Component.text("/nick other <player> <name>", NamedTextColor.YELLOW))
                        .append(Component.text(" — set someone's nickname (admin)", NamedTextColor.GRAY))
                )
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("set", "reset", "other").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("other", true)) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) } else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        /** Create the nicknames table — called from plugin startup */
        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable("""
                CREATE TABLE IF NOT EXISTS nicknames (
                    uuid TEXT PRIMARY KEY,
                    nickname TEXT NOT NULL
                )
            """.trimIndent())
        }

        /** Load a player's nickname on join */
        fun loadNickname(plugin: Joshymc, player: Player) {
            val nick = plugin.databaseManager.queryFirst(
                "SELECT nickname FROM nicknames WHERE uuid = ?",
                player.uniqueId.toString()
            ) { it.getString("nickname") } ?: return

            val display = plugin.commsManager.parseLegacy(nick)
            player.displayName(display)
            player.playerListName(display)
        }
    }
}
