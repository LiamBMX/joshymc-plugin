package com.liam.joshymc.link

import com.liam.joshymc.Joshymc
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LinkManager(private val plugin: Joshymc) {

    data class PendingLink(
        val playerUuid: UUID,
        val playerName: String,
        val code: String,
        val createdAt: Long = System.currentTimeMillis(),
        var discordId: String? = null,
        var discordName: String? = null,
    )

    // Code -> PendingLink
    val pendingLinks = ConcurrentHashMap<String, PendingLink>()

    // MC UUID -> Discord ID (persistent)
    private val links = ConcurrentHashMap<String, String>()

    // Discord ID -> MC UUID (reverse lookup)
    private val reverseLinks = ConcurrentHashMap<String, String>()

    private val linksFile: File get() = plugin.configFile("links.yml")

    fun load() {
        if (!linksFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(linksFile)
        for (key in config.getKeys(false)) {
            val discordId = config.getString(key) ?: continue
            links[key] = discordId
            reverseLinks[discordId] = key
        }
        plugin.logger.info("[Link] Loaded ${links.size} linked account(s).")
    }

    fun save() {
        val config = YamlConfiguration()
        for ((uuid, discordId) in links) {
            config.set(uuid, discordId)
        }
        config.save(linksFile)
    }

    fun generateCode(playerUuid: UUID, playerName: String): PendingLink {
        // Remove any existing pending link for this player
        pendingLinks.values.removeIf { it.playerUuid == playerUuid }

        val code = (1000..9999).random().toString()
        val pending = PendingLink(playerUuid, playerName, code)
        pendingLinks[code] = pending
        return pending
    }

    fun findPendingByCode(code: String): PendingLink? {
        val pending = pendingLinks[code] ?: return null
        // Expire after 5 minutes
        if (System.currentTimeMillis() - pending.createdAt > 300_000) {
            pendingLinks.remove(code)
            return null
        }
        return pending
    }

    fun confirmLink(code: String): Boolean {
        val pending = pendingLinks.remove(code) ?: return false
        val discordId = pending.discordId ?: return false

        links[pending.playerUuid.toString()] = discordId
        reverseLinks[discordId] = pending.playerUuid.toString()
        save()
        return true
    }

    fun getDiscordId(playerUuid: UUID): String? = links[playerUuid.toString()]
    fun getMinecraftUuid(discordId: String): String? = reverseLinks[discordId]

    fun isLinked(playerUuid: UUID): Boolean = links.containsKey(playerUuid.toString())

    fun unlink(playerUuid: UUID) {
        val discordId = links.remove(playerUuid.toString())
        if (discordId != null) {
            reverseLinks.remove(discordId)
        }
        save()
    }
}
