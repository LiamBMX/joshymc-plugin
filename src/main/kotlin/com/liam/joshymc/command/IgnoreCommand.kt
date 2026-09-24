package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class IgnoreCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        // ignorerUUID -> set of ignored UUIDs (in-memory mirror of DB)
        private val cache = ConcurrentHashMap<UUID, MutableSet<UUID>>()

        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable(
                "CREATE TABLE IF NOT EXISTS ignores (ignorer TEXT, ignored TEXT, PRIMARY KEY (ignorer, ignored))"
            )
            plugin.databaseManager.query("SELECT ignorer, ignored FROM ignores") { rs ->
                val ignorer = UUID.fromString(rs.getString("ignorer"))
                val ignored = UUID.fromString(rs.getString("ignored"))
                cache.getOrPut(ignorer) { ConcurrentHashMap.newKeySet() }.add(ignored)
            }
        }

        fun isIgnoring(ignorerUuid: UUID, ignoredUuid: UUID): Boolean =
            cache[ignorerUuid]?.contains(ignoredUuid) == true

        private fun add(plugin: Joshymc, ignorerUuid: UUID, ignoredUuid: UUID) {
            cache.getOrPut(ignorerUuid) { ConcurrentHashMap.newKeySet() }.add(ignoredUuid)
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO ignores (ignorer, ignored) VALUES (?, ?)",
                ignorerUuid.toString(), ignoredUuid.toString()
            )
        }

        private fun remove(plugin: Joshymc, ignorerUuid: UUID, ignoredUuid: UUID) {
            cache[ignorerUuid]?.remove(ignoredUuid)
            plugin.databaseManager.execute(
                "DELETE FROM ignores WHERE ignorer = ? AND ignored = ?",
                ignorerUuid.toString(), ignoredUuid.toString()
            )
        }

        fun getIgnored(ignorerUuid: UUID): Set<UUID> =
            cache[ignorerUuid] ?: emptySet()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /ignore <player> | /ignore list", NamedTextColor.RED))
            return true
        }

        if (args[0].equals("list", ignoreCase = true)) {
            val ignored = getIgnored(sender.uniqueId)
            if (ignored.isEmpty()) {
                plugin.commsManager.send(sender, Component.text("You are not ignoring anyone.", NamedTextColor.GRAY))
                return true
            }
            plugin.commsManager.send(sender, Component.text("Ignored players:", NamedTextColor.GOLD))
            for (uuid in ignored) {
                val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                plugin.commsManager.send(sender, Component.text("  - $name", NamedTextColor.GRAY))
            }
            return true
        }

        val targetName = args[0]
        val target = Bukkit.getPlayer(targetName) ?: run {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        if (target.uniqueId == sender.uniqueId) {
            plugin.commsManager.send(sender, Component.text("You cannot ignore yourself.", NamedTextColor.RED))
            return true
        }

        if (isIgnoring(sender.uniqueId, target.uniqueId)) {
            remove(plugin, sender.uniqueId, target.uniqueId)
            plugin.commsManager.send(sender, Component.text("You are no longer ignoring ${target.name}.", NamedTextColor.GREEN))
        } else {
            add(plugin, sender.uniqueId, target.uniqueId)
            plugin.commsManager.send(sender, Component.text("You are now ignoring ${target.name}.", NamedTextColor.GREEN))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val options = Bukkit.getOnlinePlayers().map { it.name } + listOf("list")
            return options.filter { it.lowercase().startsWith(prefix) }
        }
        return emptyList()
    }
}
