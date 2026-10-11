package com.liam.joshymc.manager

import com.destroystokyo.paper.profile.ProfileProperty
import com.google.gson.JsonParser
import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BedrockUtil
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.meta.SkullMeta
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player-head skins for GUIs without Mojang lookups on the main thread (issue #1091).
 *
 * `SkullMeta.owningPlayer = offlinePlayer` hands Paper an id-only profile, which the
 * server then resolves against sessionserver.mojang.com. For Floodgate (Bedrock) ids
 * that lookup can never succeed, and every GUI open repeated it, spamming
 * "Couldn't look up profile properties" timeouts.
 *
 * [applyHead] only ever puts a profile that already carries its `textures` property on
 * the head; anything else gets the plain generic head. Textures come from, in order:
 * the online player's own profile, the in-memory/SQLite cache (`player_head_textures`,
 * refreshed on every join), and finally one async sessionserver fetch for real Java
 * (version 4, non-Floodgate) ids. Failed fetches back off for [FAILURE_BACKOFF_MS] and
 * are never cached permanently.
 */
class PlayerHeadManager(private val plugin: Joshymc) : Listener {

    private val textures = ConcurrentHashMap<UUID, ProfileProperty>()
    private val failedUntil = ConcurrentHashMap<UUID, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_head_textures (
                uuid TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                signature TEXT,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.query("SELECT uuid, value, signature FROM player_head_textures") { rs ->
            try {
                UUID.fromString(rs.getString("uuid")) to ProfileProperty("textures", rs.getString("value"), rs.getString("signature"))
            } catch (e: Exception) {
                null
            }
        }.filterNotNull().forEach { (uuid, prop) -> textures[uuid] = prop }

        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach { remember(it) }
        plugin.logger.info("[Heads] Loaded ${textures.size} cached head texture(s).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        remember(event.player)
    }

    /**
     * Skins [meta] as [owner]'s head when the texture is known locally, otherwise leaves
     * it as the generic head. Never blocks: a missing Java skin is fetched in the
     * background and shows up the next time the GUI is built.
     */
    fun applyHead(meta: SkullMeta, owner: OfflinePlayer) {
        val texture = textureFor(owner) ?: return
        val profile = Bukkit.createProfile(owner.uniqueId, owner.name)
        profile.setProperty(texture)
        meta.playerProfile = profile
    }

    private fun textureFor(owner: OfflinePlayer): ProfileProperty? {
        val uuid = owner.uniqueId
        val online = owner as? Player ?: Bukkit.getPlayer(uuid)
        if (online != null) remember(online)?.let { return it }

        textures[uuid]?.let { return it }
        if (!isMojangId(uuid)) return null
        // Online Java players already carry their textures; if they don't, don't hammer Mojang.
        if (online != null) return null
        fetchAsync(uuid)
        return null
    }

    /** Caches (and persists, when changed) an online player's own textures. */
    private fun remember(player: Player): ProfileProperty? {
        val prop = player.playerProfile.properties.firstOrNull { it.name == "textures" } ?: return null
        val previous = textures.put(player.uniqueId, prop)
        if (previous?.value != prop.value) persist(player.uniqueId, prop)
        return prop
    }

    private fun persist(uuid: UUID, prop: ProfileProperty) {
        try {
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_head_textures (uuid, value, signature, updated_at) VALUES (?, ?, ?, ?)",
                uuid.toString(), prop.value, prop.signature, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Heads] Failed to save head texture for $uuid: ${e.message}")
        }
    }

    /** Only real Mojang (online-mode, version 4) ids that aren't Floodgate ids may go to sessionserver. */
    private fun isMojangId(uuid: UUID): Boolean =
        uuid.version() == 4 && !BedrockUtil.isFloodgateUuid(uuid)

    private fun fetchAsync(uuid: UUID) {
        val now = System.currentTimeMillis()
        if ((failedUntil[uuid] ?: 0L) > now) return
        if (!inFlight.add(uuid)) return

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val result = try {
                fetchTextures(uuid)
            } catch (e: Exception) {
                plugin.logger.fine("[Heads] Skin lookup for $uuid failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (!plugin.isEnabled) return@Runnable
            Bukkit.getScheduler().runTask(plugin, Runnable {
                inFlight.remove(uuid)
                if (result != null) {
                    failedUntil.remove(uuid)
                    textures[uuid] = result
                    persist(uuid, result)
                } else {
                    failedUntil[uuid] = System.currentTimeMillis() + FAILURE_BACKOFF_MS
                }
            })
        })
    }

    private fun fetchTextures(uuid: UUID): ProfileProperty? {
        val request = HttpRequest.newBuilder(URI.create(SESSION_URL + uuid.toString().replace("-", "") + "?unsigned=false"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val properties = JsonParser.parseString(response.body()).asJsonObject.getAsJsonArray("properties") ?: return null
        for (element in properties) {
            val obj = element.asJsonObject
            if (obj.get("name")?.asString != "textures") continue
            val value = obj.get("value")?.asString ?: return null
            return ProfileProperty("textures", value, obj.get("signature")?.asString)
        }
        return null
    }

    companion object {
        private const val SESSION_URL = "https://sessionserver.mojang.com/session/minecraft/profile/"
        private const val FAILURE_BACKOFF_MS = 5 * 60 * 1000L
    }
}
