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
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class VanishCommand(private val plugin: Joshymc) : CommandExecutor, Listener, TabCompleter {

    private val vanished: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var actionBarTaskId: Int = -1

    fun isVanished(player: Player): Boolean = vanished.contains(player.uniqueId)

    fun start() {
        // Action bar reminder every 2 seconds (40 ticks)
        actionBarTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            val message = plugin.commsManager.parseLegacy("&c&lVANISHED")
            for (uuid in vanished) {
                val player = Bukkit.getPlayer(uuid) ?: continue
                player.sendActionBar(message)
            }
        }, 40L, 40L)
    }

    fun stop() {
        if (actionBarTaskId != -1) {
            plugin.server.scheduler.cancelTask(actionBarTaskId)
            actionBarTaskId = -1
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.vanish")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        val target: Player = if (args.isNotEmpty()) {
            val found = Bukkit.getPlayer(args[0])
            if (found == null) {
                plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                return true
            }
            found
        } else {
            sender
        }

        if (vanished.contains(target.uniqueId)) {
            unvanish(target)
            plugin.commsManager.send(sender, Component.text("${target.name} is now ", NamedTextColor.GRAY)
                .append(Component.text("visible", NamedTextColor.GREEN)), CommunicationsManager.Category.ADMIN)
        } else {
            vanish(target)
            plugin.commsManager.send(sender, Component.text("${target.name} is now ", NamedTextColor.GRAY)
                .append(Component.text("vanished", NamedTextColor.RED)), CommunicationsManager.Category.ADMIN)
        }

        return true
    }

    private fun vanish(player: Player) {
        vanished.add(player.uniqueId)
        for (online in Bukkit.getOnlinePlayers()) {
            if (online == player) continue
            if (online.hasPermission("joshymc.vanish")) continue
            online.hidePlayer(plugin, player)
        }
    }

    private fun unvanish(player: Player) {
        vanished.remove(player.uniqueId)
        for (online in Bukkit.getOnlinePlayers()) {
            if (online == player) continue
            online.showPlayer(plugin, player)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val joiner = event.player

        // If the joiner is vanished, suppress join message and hide from non-staff
        if (vanished.contains(joiner.uniqueId)) {
            event.joinMessage(null)
            for (online in Bukkit.getOnlinePlayers()) {
                if (online == joiner) continue
                if (online.hasPermission("joshymc.vanish")) continue
                online.hidePlayer(plugin, joiner)
            }
        }

        // Hide all currently vanished players from the joiner (unless they have vanish perm)
        if (!joiner.hasPermission("joshymc.vanish")) {
            for (uuid in vanished) {
                val vanishedPlayer = Bukkit.getPlayer(uuid) ?: continue
                joiner.hidePlayer(plugin, vanishedPlayer)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val quitter = event.player

        // Suppress quit message if vanished
        if (vanished.contains(quitter.uniqueId)) {
            event.quitMessage(null)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission("joshymc.vanish")) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
