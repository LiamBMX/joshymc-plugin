package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class WelcomeListener(private val plugin: Joshymc) : Listener {

    private var firstJoinMessage: String = "&6&l\u2605 &eWelcome &f{player} &eto JoshyMC! &6&l\u2605"
    private var firstJoinBroadcast: Boolean = true
    private var joinFormat: String = "&8[&a+&8] &7{player}"
    private var leaveFormat: String = "&8[&c-&8] &7{player}"
    private var motdLines: List<String> = listOf(
        "",
        "&b&lWelcome to JoshyMC!",
        "&7Type &6/shop &7to buy items",
        "&7Type &6/claim &7to protect your builds",
        "&7Type &6/kit &7to get starter items",
        ""
    )

    // ── Lifecycle ───────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS first_joins (
                uuid TEXT PRIMARY KEY,
                joined_at INTEGER
            )
        """.trimIndent())

        loadConfig()
        plugin.logger.info("[Welcome] Listener started.")
    }

    private fun loadConfig() {
        val config = plugin.config

        firstJoinMessage = config.getString("welcome.first-join-message", firstJoinMessage) ?: firstJoinMessage
        firstJoinBroadcast = config.getBoolean("welcome.first-join-broadcast", firstJoinBroadcast)
        joinFormat = config.getString("welcome.join-format", joinFormat) ?: joinFormat
        leaveFormat = config.getString("welcome.leave-format", leaveFormat) ?: leaveFormat
        motdLines = config.getStringList("welcome.motd").ifEmpty { motdLines }
    }

    // ── Events ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val name = player.name

        // Load nickname if set
        com.liam.joshymc.command.NickCommand.loadNickname(plugin, player)

        // Replace vanilla join message
        val formattedJoin = joinFormat.replace("{player}", name)
        event.joinMessage(plugin.commsManager.parseLegacy(formattedJoin))

        val isFirstJoin = !player.hasPlayedBefore()

        if (isFirstJoin) {
            // Record first join in DB
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO first_joins (uuid, joined_at) VALUES (?, ?)",
                player.uniqueId.toString(), System.currentTimeMillis()
            )

            // Broadcast first-join welcome
            if (firstJoinBroadcast) {
                val welcomeText = firstJoinMessage.replace("{player}", name)
                plugin.server.broadcast(plugin.commsManager.parseLegacy(welcomeText))
            }
        }

        // Send MOTD lines to the player (first join or returning)
        for (line in motdLines) {
            plugin.commsManager.sendRaw(player, plugin.commsManager.parseLegacy(line))
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        val name = event.player.name

        // Replace vanilla leave message
        val formattedLeave = leaveFormat.replace("{player}", name)
        event.quitMessage(plugin.commsManager.parseLegacy(formattedLeave))
    }
}
