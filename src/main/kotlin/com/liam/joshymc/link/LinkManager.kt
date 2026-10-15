package com.liam.joshymc.link

import com.liam.joshymc.Joshymc
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

/**
 * Discord account linking: config + `links.yml` persistence around [LinkSessions], which
 * owns the thread-safe session and link state (issue #1155).
 */
class LinkManager(private val plugin: Joshymc) {

    val sessions = LinkSessions()

    private val linksFile: File get() = plugin.configFile("links.yml")

    val settings: LinkSettings get() = sessions.settings

    fun load() {
        sessions.settings = readSettings()

        val entries = ArrayList<Pair<UUID, String>>()
        if (linksFile.exists()) {
            val config = YamlConfiguration.loadConfiguration(linksFile)
            for (key in config.getKeys(false)) {
                val discordId = config.getString(key) ?: continue
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: continue
                entries += uuid to discordId
            }
        }
        val dropped = sessions.replaceLinks(entries)
        for ((uuid, discordId) in dropped) {
            plugin.logger.warning("[Link] Dropped duplicate link $uuid -> $discordId (that Discord account is already linked to another player).")
        }
        plugin.logger.info("[Link] Loaded ${sessions.linkCount()} linked account(s).")
    }

    @Synchronized
    fun save() {
        val config = YamlConfiguration()
        for ((uuid, discordId) in sessions.snapshotLinks()) {
            config.set(uuid.toString(), discordId)
        }
        config.save(linksFile)
    }

    private fun readSettings(): LinkSettings {
        val section = plugin.config.getConfigurationSection("discord.linking")
        val defaults = LinkSettings()
        if (section == null) return defaults
        return LinkSettings(
            codeLength = section.getInt("code-length", defaults.codeLength).coerceIn(LinkCodes.MIN_LENGTH, LinkCodes.MAX_LENGTH),
            codeExpiryMs = section.getLong("code-expiry-seconds", defaults.codeExpiryMs / 1000).coerceIn(60, 1800) * 1000,
            maxFailedAttempts = section.getInt("max-failed-attempts", defaults.maxFailedAttempts).coerceIn(1, 20),
            attemptCooldownMs = section.getLong("attempt-cooldown-seconds", defaults.attemptCooldownMs / 1000).coerceIn(30, 86_400) * 1000,
            globalMaxFailuresPerMinute = section.getInt("global-max-failed-per-minute", defaults.globalMaxFailuresPerMinute).coerceAtLeast(0),
        )
    }

    fun startLink(playerUuid: UUID, playerName: String): PendingLink = sessions.start(playerUuid, playerName)

    /** Links the account the player's GUI showed and saves; see [LinkSessions.confirm]. */
    fun confirmLink(playerUuid: UUID): LinkSessions.ConfirmResult {
        val result = sessions.confirm(playerUuid)
        if (result is LinkSessions.ConfirmResult.Linked) save()
        return result
    }

    fun cancel(playerUuid: UUID) = sessions.cancel(playerUuid)

    fun getDiscordId(playerUuid: UUID): String? = sessions.getDiscordId(playerUuid)
    fun getMinecraftUuid(discordId: String): String? = sessions.getMinecraftUuid(discordId)?.toString()

    fun isLinked(playerUuid: UUID): Boolean = sessions.isLinked(playerUuid)

    fun unlink(playerUuid: UUID) {
        sessions.unlink(playerUuid)
        save()
    }
}
