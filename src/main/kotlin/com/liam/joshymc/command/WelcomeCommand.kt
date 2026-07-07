package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import kotlin.random.Random

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
        val target = newPlayers.entries.maxByOrNull { it.value.joinedAt }
        if (target == null) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cThere are no new players to welcome right now."))
            return true
        }

        val entry = target.value
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

        if (sender.uniqueId in entry.welcomers) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cYou already welcomed ${newPlayer.name}!"))
            return true
        }

        if (entry.welcomers.size >= 10) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&c10 players have already welcomed ${newPlayer.name}."))
            return true
        }

        entry.welcomers.add(sender.uniqueId)

        // Broadcast welcome message
        val msg = "&6&l★ &e${sender.name} &awelcomed &f${newPlayer.name} &ato the server! &6&l★"
        plugin.server.broadcast(plugin.commsManager.parseLegacy(msg))

        // Flat money reward
        plugin.economyManager.deposit(sender.uniqueId, 10000.0)

        // 10% chance of 1 credit, otherwise an AFK crate key
        if (Random.nextDouble() < 0.1) {
            plugin.creditsManager.deposit(sender.uniqueId, 1.0)
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&aYou received &f${plugin.economyManager.format(10000.0)} &aand &b1 Credit &afor welcoming ${newPlayer.name}!"))
        } else {
            plugin.crateManager.giveKey(sender, "afk", 1)
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&aYou received &f${plugin.economyManager.format(10000.0)} &aand an &bAFK Key &afor welcoming ${newPlayer.name}!"))
        }

        // Remove from map once all 10 welcome slots are filled
        if (entry.welcomers.size >= 10) {
            newPlayers.remove(target.key)
        }

        return true
    }
}
