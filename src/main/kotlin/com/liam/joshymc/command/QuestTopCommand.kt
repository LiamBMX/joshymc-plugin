package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

class QuestTopCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.questtop")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return true
        }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val rows = plugin.databaseManager.query(
                "SELECT uuid, COUNT(*) AS n FROM quest_progress WHERE completed = 1 GROUP BY uuid ORDER BY n DESC LIMIT 10"
            ) { rs ->
                try { UUID.fromString(rs.getString("uuid")) to rs.getInt("n") } catch (_: Exception) { null }
            }.filterNotNull()

            plugin.server.scheduler.runTask(plugin, Runnable {
                if (rows.isEmpty()) {
                    plugin.commsManager.send(sender, Component.text("No quest data recorded yet.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
                } else {
                    plugin.commsManager.send(
                        sender,
                        Component.text("Top Questers", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true),
                        CommunicationsManager.Category.DEFAULT
                    )
                    for ((index, pair) in rows.withIndex()) {
                        val (uuid, count) = pair
                        val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                        sender.sendMessage(
                            Component.text(" ${index + 1}. ", NamedTextColor.GRAY)
                                .append(Component.text(name, NamedTextColor.WHITE))
                                .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                                .append(Component.text("$count quests", NamedTextColor.YELLOW))
                        )
                    }
                }
            })
        })
        return true
    }
}
