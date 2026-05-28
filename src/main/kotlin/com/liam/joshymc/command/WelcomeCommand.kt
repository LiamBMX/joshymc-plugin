package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class WelcomeCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }

        val newPlayers = plugin.listenerManager.welcomeListener.recentNewPlayers

        if (newPlayers.isEmpty()) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cThere are no new players to welcome right now."))
            return true
        }

        // Pick the most recently joined new player still within the window
        val target = newPlayers.entries.maxByOrNull { it.value }
        if (target == null) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cThere are no new players to welcome right now."))
            return true
        }

        val newPlayer = plugin.server.getPlayer(target.key)
        if (newPlayer == null || !newPlayer.isOnline) {
            newPlayers.remove(target.key)
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cThere are no new players to welcome right now."))
            return true
        }

        if (sender.uniqueId == newPlayer.uniqueId) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cYou cannot welcome yourself."))
            return true
        }

        // Remove from map so only one player gets the reward per new player
        newPlayers.remove(target.key)

        // Broadcast welcome message
        val msg = "&6&l★ &e${sender.name} &awelcomed &f${newPlayer.name} &ato the server! &6&l★"
        plugin.server.broadcast(plugin.commsManager.parseLegacy(msg))

        // Give the welcoming player one AFK crate key
        plugin.crateManager.giveKey(sender, "afk", 1)

        plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&aYou received an &bAFK Key &afor welcoming ${newPlayer.name}!"))

        return true
    }
}
