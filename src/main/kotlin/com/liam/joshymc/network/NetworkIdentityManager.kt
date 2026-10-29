package com.liam.joshymc.network

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode

/**
 * This server's network identity (issue #1323), from `network.server-id` / `network.primary`.
 *
 * The server id is read once at startup and kept until the server restarts, so everything
 * that records or compares it sees one value for the plugin's whole lifetime. The primary
 * flag follows `/joshymc reload`; the services that depend on it read it when they start
 * (the Discord bot is stopped and restarted by every reload), so a change never leaves a
 * second connection or task behind.
 *
 * Primary-only: the Discord bot (unless `network.secondary-discord-bot` is set, for a
 * secondary with its own bot token), update highlights, and the LuckPerms Discord
 * verification group sync. Everything else is local to each server. See docs/network.md.
 */
class NetworkIdentityManager(private val plugin: Joshymc) {

    /** Until the first [load], a secondary with the default id: nothing network-wide starts by accident. */
    @Volatile var identity = NetworkIdentity(DEFAULT_SERVER_ID, primary = false)
        private set
    @Volatile var gamemode: ServerGamemode = ServerGamemode.SURVIVAL
        private set
    @Volatile private var secondaryDiscordBot = false
    private var loaded = false

    val serverId: String get() = identity.serverId
    val isPrimary: Boolean get() = identity.primary

    /** May this server connect the Discord bot? Always on the primary; on a secondary only when opted in. */
    val discordBotAllowed: Boolean get() = isPrimary || secondaryDiscordBot

    /** Reads `network.*`. Called right after the config is (re)loaded, before any service starts. */
    fun load() {
        try {
            val cfg = plugin.config
            val rawGamemode = cfg.getString("gamemode-lock.server-gamemode", "survival")
            // Same fallback as GamemodeLockManager, which logs the unknown value itself.
            val currentGamemode = ServerGamemode.fromId(rawGamemode) ?: ServerGamemode.SURVIVAL
            // get(path, null) ignores the bundled defaults, so a missing key is seen as missing.
            val result = NetworkIdentityConfig.resolve(
                cfg.get("network.server-id", null),
                cfg.get("network.primary", null),
                currentGamemode
            )
            for (problem in result.problems) {
                if (problem.severe) plugin.logger.severe("[Network] ${problem.message}")
                else plugin.logger.warning("[Network] ${problem.message}")
            }

            val previous = identity
            var next = result.identity
            if (loaded && next.serverId != previous.serverId) {
                plugin.logger.warning("[Network] network.server-id changed to '${next.serverId}'; the server id only changes on a restart, still using '${previous.serverId}'.")
                next = next.copy(serverId = previous.serverId)
            }
            identity = next
            gamemode = currentGamemode
            secondaryDiscordBot = cfg.getBoolean("network.secondary-discord-bot", false)

            if (!loaded) {
                plugin.logger.info("Network Server ID: ${next.serverId}")
                plugin.logger.info("Server Gamemode: ${currentGamemode.name}")
                plugin.logger.info("Primary Network Instance: ${next.primary}")
                if (!next.primary) {
                    plugin.logger.info(
                        if (secondaryDiscordBot) "[Network] Secondary instance: update highlights and the Discord verification group sync are left to the primary server; this server connects its own Discord bot (network.secondary-discord-bot)."
                        else "[Network] Secondary instance: the Discord bot, update highlights and the Discord verification group sync are left to the primary server."
                    )
                }
            } else if (next.primary != previous.primary) {
                plugin.logger.info("[Network] Primary Network Instance: ${previous.primary} -> ${next.primary}")
            }
            loaded = true
        } catch (e: Exception) {
            plugin.logger.severe("[Network] Could not read the network settings (keeping ${identity}): ${e.message}")
        }
    }

    companion object {
        const val DEFAULT_SERVER_ID = "survival"
    }
}
