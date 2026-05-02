package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ChatGamesManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/chatgame [type]` — admin command to start a chat game on demand.
 *
 * - `/chatgame` — start a random game
 * - `/chatgame math` — start a math game
 * - `/chatgame unscramble` — start an unscramble game
 * - `/chatgame type` — start a "type this token" game
 * - `/chatgame reverse` — start a "type backwards" game
 * - `/chatgame status` — show the active game (and answer, since it's admin)
 */
class ChatGameCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.chatgame")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()

        if (sub == "status") {
            val active = plugin.chatGamesManager.activeGame()
            if (active == null) {
                sender.sendMessage(Component.text("No chat game running.", NamedTextColor.GRAY))
            } else {
                sender.sendMessage(
                    Component.text("Active: ${active.type.displayName} — ", NamedTextColor.YELLOW)
                        .append(Component.text(active.prompt, NamedTextColor.WHITE))
                )
                sender.sendMessage(
                    Component.text("Answer: ", NamedTextColor.GRAY)
                        .append(Component.text(active.answer, NamedTextColor.GREEN))
                )
            }
            return true
        }

        val type = if (sub == null) {
            null
        } else {
            ChatGamesManager.GameType.entries.firstOrNull { it.name.equals(sub, ignoreCase = true) }
                ?: run {
                    sender.sendMessage(
                        Component.text(
                            "Usage: /chatgame [math|unscramble|type|reverse|status]",
                            NamedTextColor.RED
                        )
                    )
                    return true
                }
        }

        val started = if (type == null) {
            plugin.chatGamesManager.startRandomGame()
        } else {
            plugin.chatGamesManager.startGame(type)
        }

        if (!started) {
            sender.sendMessage(Component.text("A chat game is already running.", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("Chat game started.", NamedTextColor.GREEN))
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("joshymc.chatgame")) return emptyList()
        if (args.size != 1) return emptyList()
        val all = ChatGamesManager.GameType.entries.map { it.name.lowercase() } + "status"
        return all.filter { it.startsWith(args[0], ignoreCase = true) }
    }
}
