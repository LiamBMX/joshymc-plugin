package com.liam.joshymc.util

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized Bedrock/Geyser player detection (issue #935). Prefers the Floodgate API
 * (resolved via reflection so this plugin doesn't need a compile-time Floodgate
 * dependency) and falls back to Floodgate's default username prefix ('.') only when
 * Floodgate isn't installed on the server at all.
 */
object BedrockUtil {

    private const val DEFAULT_PREFIX = "."

    private val floodgateApi: Any? by lazy { resolveFloodgateApi() }
    private val isFloodgatePlayerMethod by lazy {
        floodgateApi?.let {
            try {
                it.javaClass.getMethod("isFloodgatePlayer", UUID::class.java)
            } catch (e: Throwable) {
                null
            }
        }
    }

    private val resultCache = ConcurrentHashMap<UUID, Boolean>()

    private fun resolveFloodgateApi(): Any? {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) return null
        return try {
            val apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            apiClass.getMethod("getInstance").invoke(null)
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * True if [uuid] belongs to a player connected through Geyser/Bedrock. Cached per-player
     * since this is called from hot paths (e.g. per inventory click).
     */
    fun isBedrockPlayer(uuid: UUID): Boolean {
        return resultCache.computeIfAbsent(uuid) { detect(it) }
    }

    fun isBedrockPlayer(player: Player): Boolean = isBedrockPlayer(player.uniqueId)

    fun clearCache(uuid: UUID) {
        resultCache.remove(uuid)
    }

    private fun detect(uuid: UUID): Boolean {
        val api = floodgateApi
        val method = isFloodgatePlayerMethod
        if (api != null && method != null) {
            return try {
                method.invoke(api, uuid) as? Boolean ?: false
            } catch (e: Throwable) {
                false
            }
        }

        // No Floodgate on this server — best-effort fallback using the default
        // Floodgate username prefix. Not reliable if the server admin customized it.
        val name = Bukkit.getOfflinePlayer(uuid).name ?: return false
        return name.startsWith(DEFAULT_PREFIX)
    }
}
