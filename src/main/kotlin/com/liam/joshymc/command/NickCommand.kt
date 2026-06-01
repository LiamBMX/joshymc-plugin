package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class NickCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    /**
     * Build the actual displayed nickname Component. Non-op players get a
     * `~` prefix in front of their nick to make it obvious it's a fake name;
     * ops are exempt so admin-set nicknames look clean.
     */
    private fun renderNick(target: Player, raw: String): Component {
        val core = plugin.commsManager.parseLegacy(raw)
        return if (target.isOp || target.hasPermission("joshymc.nick.noprefix")) {
            core
        } else {
            Component.text("~", NamedTextColor.GRAY).append(core)
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "set" -> {
                if (sender !is Player) { sender.sendMessage("Players only."); return true }
                if (!sender.hasPermission("joshymc.nick")) {
                    plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                val nick = args.drop(1).joinToString(" ")
                if (nick.isBlank()) {
                    plugin.commsManager.send(sender, Component.text("Usage: /nick set <nickname>", NamedTextColor.RED))
                    return true
                }
                val plain = nick.replace("&[0-9a-fk-or]".toRegex(), "")
                if (plain.length > 24) {
                    plugin.commsManager.send(sender, Component.text("Nickname too long (max 24 characters).", NamedTextColor.RED))
                    return true
                }
                if (ProfanityFilter.contains(plain) && !sender.hasPermission("joshymc.nick.bypassfilter")) {
                    val hit = ProfanityFilter.firstHit(plain) ?: "banned word"
                    plugin.commsManager.send(sender, Component.text("Nickname rejected — contains '$hit'.", NamedTextColor.RED))
                    return true
                }
                plugin.databaseManager.execute(
                    "INSERT OR REPLACE INTO nicknames (uuid, nickname) VALUES (?, ?)",
                    sender.uniqueId.toString(), nick
                )
                val display = renderNick(sender, nick)
                sender.displayName(display)
                sender.playerListName(display)
                plugin.commsManager.send(sender, Component.text("Nickname set to ", NamedTextColor.GREEN).append(display))
            }
            "reset", "off", "clear" -> {
                val targetName = args.getOrNull(1)
                if (targetName != null) {
                    // Admin reset another player's nickname — works for console and ops
                    if (!sender.hasPermission("joshymc.nick.others")) {
                        if (sender is Player) plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                        else sender.sendMessage("No permission.")
                        return true
                    }
                    val target = Bukkit.getPlayer(targetName)
                    if (target == null) {
                        if (sender is Player) plugin.commsManager.send(sender, Component.text("Player not found or not online.", NamedTextColor.RED))
                        else sender.sendMessage("Player not found or not online.")
                        return true
                    }
                    plugin.databaseManager.execute("DELETE FROM nicknames WHERE uuid = ?", target.uniqueId.toString())
                    target.displayName(Component.text(target.name))
                    target.playerListName(Component.text(target.name))
                    if (sender is Player) plugin.commsManager.send(sender, Component.text("Cleared ${target.name}'s nickname.", NamedTextColor.GREEN))
                    else sender.sendMessage("Cleared ${target.name}'s nickname.")
                    plugin.commsManager.send(target, Component.text("Your nickname was cleared by an admin.", NamedTextColor.YELLOW))
                } else {
                    // Self reset — requires being a player
                    if (sender !is Player) { sender.sendMessage("Usage: /nick reset <player>"); return true }
                    plugin.databaseManager.execute("DELETE FROM nicknames WHERE uuid = ?", sender.uniqueId.toString())
                    sender.displayName(Component.text(sender.name))
                    sender.playerListName(Component.text(sender.name))
                    plugin.commsManager.send(sender, Component.text("Nickname cleared.", NamedTextColor.GREEN))
                }
            }
            "other" -> {
                // /nick other <player> <nick> — admin set someone else's nick
                if (!sender.hasPermission("joshymc.nick.others")) {
                    if (sender is Player) plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    else sender.sendMessage("No permission.")
                    return true
                }
                val targetName = args.getOrNull(1)
                val nick = args.drop(2).joinToString(" ")
                if (targetName == null || nick.isBlank()) {
                    if (sender is Player) plugin.commsManager.send(sender, Component.text("Usage: /nick other <player> <nickname>", NamedTextColor.RED))
                    else sender.sendMessage("Usage: /nick other <player> <nickname>")
                    return true
                }
                val target = Bukkit.getPlayer(targetName)
                if (target == null) {
                    if (sender is Player) plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                    else sender.sendMessage("Player not found.")
                    return true
                }
                val plain = nick.replace("&[0-9a-fk-or]".toRegex(), "")
                if (ProfanityFilter.contains(plain) && !sender.hasPermission("joshymc.nick.bypassfilter")) {
                    val hit = ProfanityFilter.firstHit(plain) ?: "banned word"
                    if (sender is Player) plugin.commsManager.send(sender, Component.text("Nickname rejected — contains '$hit'.", NamedTextColor.RED))
                    else sender.sendMessage("Nickname rejected — contains '$hit'.")
                    return true
                }
                plugin.databaseManager.execute(
                    "INSERT OR REPLACE INTO nicknames (uuid, nickname) VALUES (?, ?)",
                    target.uniqueId.toString(), nick
                )
                val display = renderNick(target, nick)
                target.displayName(display)
                target.playerListName(display)
                if (sender is Player) plugin.commsManager.send(sender, Component.text("Set ${target.name}'s nickname to ", NamedTextColor.GREEN).append(display))
                else sender.sendMessage("Set ${target.name}'s nickname to ${nick}.")
            }
            else -> {
                val help = Component.text("/nick set <name>", NamedTextColor.YELLOW)
                    .append(Component.text(" — set your nickname\n", NamedTextColor.GRAY))
                    .append(Component.text("/nick reset", NamedTextColor.YELLOW))
                    .append(Component.text(" — clear your nickname\n", NamedTextColor.GRAY))
                    .append(Component.text("/nick reset <player>", NamedTextColor.YELLOW))
                    .append(Component.text(" — clear someone's nickname (admin)\n", NamedTextColor.GRAY))
                    .append(Component.text("/nick other <player> <name>", NamedTextColor.YELLOW))
                    .append(Component.text(" — set someone's nickname (admin)", NamedTextColor.GRAY))
                if (sender is Player) plugin.commsManager.send(sender, help)
                else sender.sendMessage("/nick reset <player>  — clear someone's nickname (admin)\n/nick other <player> <name>  — set someone's nickname (admin)")
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("set", "reset", "other").filter { it.startsWith(args[0].lowercase()) }
            2 -> when {
                args[0].equals("other", true) && sender.hasPermission("joshymc.nick.others") ->
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                args[0].equals("reset", true) && sender.hasPermission("joshymc.nick.others") ->
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
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

            val core = plugin.commsManager.parseLegacy(nick)
            val display = if (player.isOp || player.hasPermission("joshymc.nick.noprefix")) {
                core
            } else {
                Component.text("~", NamedTextColor.GRAY).append(core)
            }
            player.displayName(display)
            player.playerListName(display)
        }
    }
}
