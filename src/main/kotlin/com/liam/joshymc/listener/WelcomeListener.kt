package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WelcomeListener(private val plugin: Joshymc) : Listener {

    private var firstJoinMessage: String = "&6&l\u2605 &eWelcome &f{player} &eto JoshyMC! Type &f\"welcome\" &ein chat within 30 seconds to welcome them! &6&l\u2605"
    private var firstJoinBroadcast: Boolean = true
    private var joinNumberBroadcast: Boolean = true
    private var joinNumberMessage: String = "&#ff9d2eWelcome &#ffd84d{player} &fto &#ff9d2eJoshyMC&f! &#ffd84d[#{join_number}]"
    private var joinFormat: String = "&8[&a+&8] &7{player}"
    private var leaveFormat: String = "&8[&c-&8] &7{player}"
    private var motdLines: List<String> = listOf(
        "",
        "&b&lWelcome to JoshyMC!",
        "&7Type &6/shop &7to buy items",
        "&7Type &6/claim wand &7to protect your builds",
        "&7Type &6/kit &7to get starter items",
        ""
    )

    companion object {
        private const val WELCOME_WINDOW_MS = 30_000L
        private const val WELCOME_WINDOW_TICKS = 600L // 30 seconds
    }

    data class WelcomeEntry(
        val newcomerName: String,
        val joinedAt: Long,
        // Backed by a ConcurrentHashMap so `add()` is an atomic, thread-safe
        // check-and-set \u2014 chat is processed off the main thread (AsyncChatEvent).
        val welcomers: MutableSet<UUID> = java.util.Collections.newSetFromMap(ConcurrentHashMap())
    )

    // uuid \u2192 WelcomeEntry for new players; each entry lives for WELCOME_WINDOW_MS.
    // ConcurrentHashMap because it's read/written from both the main thread (join)
    // and the async chat thread (welcome trigger).
    val recentNewPlayers = ConcurrentHashMap<UUID, WelcomeEntry>()

    // ── Lifecycle ───────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS first_joins (
                uuid TEXT PRIMARY KEY,
                joined_at INTEGER
            )
        """.trimIndent())

        // Migration: adds the historical unique-player sequence number to
        // players who already have a first_joins row from before this
        // column existed. Throws (harmlessly) if the column is already there.
        try {
            plugin.databaseManager.execute("ALTER TABLE first_joins ADD COLUMN join_number INTEGER")
        } catch (_: Exception) {
            // Column already exists.
        }
        backfillJoinNumbers()

        loadConfig()
        plugin.logger.info("[Welcome] Listener started.")
    }

    /**
     * Assigns join numbers (in join order) to any existing first_joins rows
     * that predate the numbering feature, so the sequence for genuinely new
     * players continues from the current known population instead of
     * restarting at #1.
     */
    private fun backfillJoinNumbers() {
        val unnumbered = plugin.databaseManager.query(
            "SELECT uuid FROM first_joins WHERE join_number IS NULL ORDER BY joined_at ASC, uuid ASC"
        ) { rs -> rs.getString(1) }
        if (unnumbered.isEmpty()) return

        plugin.databaseManager.transaction {
            var next = plugin.databaseManager.queryFirst(
                "SELECT COALESCE(MAX(join_number), 0) FROM first_joins"
            ) { rs -> rs.getInt(1) } ?: 0

            for (uuid in unnumbered) {
                next++
                plugin.databaseManager.execute(
                    "UPDATE first_joins SET join_number = ? WHERE uuid = ?", next, uuid
                )
            }
        }
    }

    private fun loadConfig() {
        val config = plugin.config

        firstJoinMessage = config.getString("welcome.first-join-message", firstJoinMessage) ?: firstJoinMessage
        firstJoinBroadcast = config.getBoolean("welcome.first-join-broadcast", firstJoinBroadcast)
        joinNumberBroadcast = config.getBoolean("welcome.join-number-broadcast", joinNumberBroadcast)
        joinNumberMessage = config.getString("welcome.join-number-message", joinNumberMessage) ?: joinNumberMessage
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
            // Record first join in DB, assigning the next historical
            // unique-player number atomically alongside the insert so two
            // brand-new joins can never be handed the same number.
            val now = System.currentTimeMillis()
            var joinNumber = 0
            plugin.databaseManager.transaction {
                joinNumber = plugin.databaseManager.queryFirst(
                    "SELECT COALESCE(MAX(join_number), 0) + 1 FROM first_joins"
                ) { rs -> rs.getInt(1) } ?: 1
                plugin.databaseManager.execute(
                    "INSERT OR IGNORE INTO first_joins (uuid, joined_at, join_number) VALUES (?, ?, ?)",
                    player.uniqueId.toString(), now, joinNumber
                )
            }

            // Open a 30-second window during which other players can type
            // "welcome" in chat to claim the reward. Reconnecting during (or
            // after) this window never re-opens it — it's keyed off this join.
            recentNewPlayers[player.uniqueId] = WelcomeEntry(name, now)
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                recentNewPlayers.remove(player.uniqueId)
            }, WELCOME_WINDOW_TICKS)

            // Broadcast the numbered first-join welcome
            if (joinNumberBroadcast) {
                val formattedNumber = String.format(Locale.US, "%,d", joinNumber)
                val numberedText = joinNumberMessage
                    .replace("{player}", name)
                    .replace("{join_number}", formattedNumber)
                plugin.server.broadcast(plugin.commsManager.parseLegacy(numberedText))
            }

            // Broadcast the welcome-reward call-to-action
            if (firstJoinBroadcast) {
                val welcomeText = firstJoinMessage.replace("{player}", name)
                plugin.server.broadcast(plugin.commsManager.parseLegacy(welcomeText))
            }

            // Teleport first-time joiners to the configured spawn so they
            // don't drop into a random vanilla world spawn point.
            val spawn = plugin.warpManager.getSpawn()
                ?: org.bukkit.Bukkit.getWorld("spawn")?.spawnLocation
                ?: org.bukkit.Bukkit.getWorlds()[0].spawnLocation
            // Defer one tick so the join is fully processed before we teleport.
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) player.teleport(spawn)
            })
        }

        // Send MOTD lines to the player (first join or returning)
        for (line in motdLines) {
            plugin.commsManager.sendRaw(player, plugin.commsManager.parseLegacy(line))
        }
    }

    /**
     * Route respawns to the configured /spawn warp unless the player has a
     * valid bed or respawn anchor. Otherwise vanilla picks the world's
     * spawnLocation (which is randomized inside the spawn region) and players
     * end up scattered.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (event.isBedSpawn || event.isAnchorSpawn) return

        val spawn = plugin.warpManager.getSpawn()
            ?: org.bukkit.Bukkit.getWorld("spawn")?.spawnLocation
            ?: return
        event.respawnLocation = spawn
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onQuit(event: PlayerQuitEvent) {
        val name = event.player.name

        // Replace vanilla leave message
        val formattedLeave = leaveFormat.replace("{player}", name)
        event.quitMessage(plugin.commsManager.parseLegacy(formattedLeave))
    }

    /**
     * Chat-based replacement for the old /welcome command. Runs after the
     * mute check and Staff Chat redirect (both at LOWEST) via ignoreCancelled,
     * so muted players and staff-chat messages never trigger a reward. The
     * message itself is never cancelled — it goes through as normal chat.
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val plain = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()
        if (!plain.equals("welcome", ignoreCase = true)) return

        val welcomer = event.player
        val now = System.currentTimeMillis()

        // Claim every still-active newcomer this welcomer hasn't already
        // welcomed. `welcomers.add` is an atomic check-and-set on the backing
        // ConcurrentHashMap, so concurrent chat events can't double-reward.
        val newlyWelcomed = mutableListOf<String>()
        for ((newcomerUuid, entry) in recentNewPlayers) {
            if (now - entry.joinedAt > WELCOME_WINDOW_MS) continue
            if (newcomerUuid == welcomer.uniqueId) continue
            if (entry.welcomers.add(welcomer.uniqueId)) {
                newlyWelcomed.add(entry.newcomerName)
            }
        }
        if (newlyWelcomed.isEmpty()) return

        // Economy/crate/message calls aren't safe off the main thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            for (newcomerName in newlyWelcomed) {
                grantWelcomeReward(welcomer, newcomerName)
            }
        })
    }

    private fun grantWelcomeReward(welcomer: Player, newcomerName: String) {
        val msg = "&6&l★ &e${welcomer.name} &awelcomed &f$newcomerName &ato the server! &6&l★"
        plugin.server.broadcast(plugin.commsManager.parseLegacy(msg))

        // Flat reward: $10,000 Money + 1 Credit, every time.
        plugin.economyManager.deposit(welcomer.uniqueId, 10000.0)
        plugin.creditsManager.deposit(welcomer.uniqueId, 1.0)
        plugin.commsManager.send(welcomer, plugin.commsManager.parseLegacy("&aYou received &f${plugin.economyManager.format(10000.0)} &aand &b1 Credit &afor welcoming $newcomerName!"))
    }
}
