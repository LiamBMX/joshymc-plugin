package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scoreboard.DisplaySlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScoreboardManager(private val plugin: Joshymc) : Listener {

    private var sidebarTaskId: Int = -1
    private var tabTaskId: Int = -1

    private val kills = ConcurrentHashMap<UUID, Int>()
    private val deaths = ConcurrentHashMap<UUID, Int>()

    fun start() {
        // Load kills/deaths from DB
        loadStats()

        // Set up scoreboards for all online players
        for (player in Bukkit.getOnlinePlayers()) {
            setupScoreboard(player)
            updateTabName(player)
        }

        // Sidebar update every 2 seconds (40 ticks)
        sidebarTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                updateSidebar(player)
            }
        }, 0L, 40L)

        // Tab list header/footer + tab names every 5 seconds (100 ticks)
        tabTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                updateTabHeaderFooter(player)
                updateTabName(player)
            }
        }, 0L, 100L)

        plugin.logger.info("[Scoreboard] Started scoreboard manager.")
    }

    fun stop() {
        if (sidebarTaskId != -1) {
            plugin.server.scheduler.cancelTask(sidebarTaskId)
            sidebarTaskId = -1
        }
        if (tabTaskId != -1) {
            plugin.server.scheduler.cancelTask(tabTaskId)
            tabTaskId = -1
        }

        // Save all stats before shutdown
        saveAllStats()

        // Clear scoreboards for all online players
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = Bukkit.getScoreboardManager().newScoreboard
        }
    }

    // ── Event Handlers ──────────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        setupScoreboard(player)
        updateSidebar(player)
        updateTabHeaderFooter(player)
        updateTabName(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        // Optionally keep kills/deaths in memory for when they rejoin
        // No cleanup needed for scoreboard — it's discarded with the player
    }

    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        deaths.merge(victim.uniqueId, 1, Int::plus)
        saveStats(victim.uniqueId)

        val killer = victim.killer
        if (killer != null) {
            kills.merge(killer.uniqueId, 1, Int::plus)
            saveStats(killer.uniqueId)
        }
    }

    // ── Sidebar ─────────────────────────────────────────────────────

    private fun setupScoreboard(player: Player) {
        val board = Bukkit.getScoreboardManager().newScoreboard
        val objective = board.registerNewObjective(
            "joshymc_sidebar",
            "dummy",
            colorize("&6&lJOSHYMC SURVIVAL")
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        // Hide the red score numbers
        objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank())
        player.scoreboard = board
    }

    private fun updateSidebar(player: Player) {
        val board = player.scoreboard
        val objective = board.getObjective("joshymc_sidebar") ?: return

        // Clear existing entries
        for (entry in board.entries.toSet()) {
            board.resetScores(entry)
        }

        val balance = formatCompact(plugin.economyManager.getBalance(player))
        val rank = plugin.rankManager.getPlayerRank(player)
        val rankTag = rank?.displayTag?.let { colorize(it) } ?: colorize("&7None")
        val team = plugin.teamManager.getPlayerTeam(player.uniqueId) ?: "None"
        val playerKills = kills.getOrDefault(player.uniqueId, 0)
        val playtime = formatPlaytime(plugin.playtimeManager.getPlaytime(player.uniqueId))
        val ping = player.ping

        // Date/time
        val now = java.time.LocalDateTime.now()
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mma")
        val dateStr = now.format(dateFmt)

        // Lines matching the TAB style from the screenshot
        val lines = mutableListOf<String>()
        lines.add(colorize("&6&m                         ") + uniquePad(0))  // top separator
        lines.add(colorize("&7$dateStr"))                                      // date/time
        lines.add(colorize("&r") + uniquePad(1))                              // blank
        lines.add(colorize("&6&l${player.name}"))                             // player name bold gold
        lines.add(colorize("&6| &7\u1D18\u026A\u0274\u0262&6: &3$ping"))     // | ᴘɪɴɢ: value
        lines.add(colorize("&6| &7\u0280\u1D00\u0274\u1D0B&6:&r ") + rankTag) // | ʀᴀɴᴋ: [tag]
        if (team != "None") {
            lines.add(colorize("&6| &7\u1D1B\u1D07\u1D00\u1D0D&6: &b$team")) // | ᴛᴇᴀᴍ: name
        }
        lines.add(colorize("&r") + uniquePad(2))                              // blank
        lines.add(colorize("&6&lStats:"))                                     // Stats header
        lines.add(colorize("&6| &7\u1D0D\u1D0F\u0274\u1D07\u028F&6: &a$$balance"))  // | ᴍᴏɴᴇʏ: $value
        lines.add(colorize("&6| &7\u1D0B\u026A\u029F\u029F\uA731&6: &c$playerKills"))  // | ᴋɪʟʟs: value
        lines.add(colorize("&6| &7\u1D18\u029F\u1D00\u028F\u1D1B\u026A\u1D0D\u1D07&6: &e$playtime"))  // | ᴘʟᴀʏᴛɪᴍᴇ: value
        lines.add(colorize("&r") + uniquePad(3))                              // blank
        lines.add(colorize("&7\u1D05\u026A\uA731\u1D04\u1D0F\u0280\u1D05.\u0262\u0262/\u1D0A\u1D0F\uA731\u029C\u028F\u1D0D\u1D04"))  // ᴅɪsᴄᴏʀᴅ.ɢɢ/ᴊᴏsʜʏᴍᴄ
        lines.add(colorize("&6&m                         ") + uniquePad(4))  // bottom separator

        // Set scores: top line gets highest score
        for ((index, line) in lines.withIndex()) {
            objective.getScore(line).score = lines.size - index
        }
    }

    private fun formatPlaytime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        return "${days}d ${hours}h"
    }

    /**
     * Creates an invisible unique padding string using color code pairs.
     * Each call with a different [index] produces a visually blank but unique string.
     */
    private fun uniquePad(index: Int): String {
        val colors = arrayOf("§a", "§b", "§c", "§d", "§e", "§f")
        return colors.getOrElse(index) { "§${index}" } + "§r"
    }

    // ── Tab List ────────────────────────────────────────────────────

    private fun updateTabHeaderFooter(player: Player) {
        val online = Bukkit.getOnlinePlayers().size
        val ping = player.ping

        val logoChar = "\uE000"
        val header = plugin.commsManager.parseLegacy("&6&m                                   &r\n")
            .append(Component.text(logoChar, NamedTextColor.WHITE))
            .append(plugin.commsManager.parseLegacy(
                "\n\n" +
                "&7\u028F\u1D0F\u1D1C\u0280 \u1D18\u026A\u0274\u0262&6: $ping\n" +
                "&7\u1D0F\u0274\u029F\u026A\u0274\u1D07 \u1D18\u029F\u1D00\u028F\u1D07\u0280\uA731&6: $online\n" +
                "&r"
            ))

        val footer = plugin.commsManager.parseLegacy(
            "\n" +
            "&6&m                                   &r\n" +
            "&7\u1D21\u1D07\u0299\uA731\u1D1B\u1D0F\u0280\u1D07&6: \uA731\u1D1B\u1D0F\u0280\u1D07.\u1D0A\u1D0F\uA731\u029C\u028F\u1D0D\u1D04.\u0274\u1D07\u1D1B\n" +  // ᴡᴇʙsᴛᴏʀᴇ: sᴛᴏʀᴇ.ᴊᴏsʜʏᴍᴄ.ɴᴇᴛ
            "&7\u1D05\u026A\uA731\u1D04\u1D0F\u0280\u1D05&6: \u1D05\u026A\uA731\u1D04\u1D0F\u0280\u1D05.\u0262\u0262/\u1D0A\u1D0F\uA731\u029C\u028F\u1D0D\u1D04"  // ᴅɪsᴄᴏʀᴅ: ᴅɪsᴄᴏʀᴅ.ɢɢ/ᴊᴏsʜʏᴍᴄ
        )

        player.sendPlayerListHeaderAndFooter(header, footer)
    }

    private fun updateTabName(player: Player) {
        val prefix = plugin.rankManager.getPrefix(player)
        val displayPlain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(player.displayName())
        val name = if (displayPlain != player.name) {
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().serialize(player.displayName())
        } else {
            player.name
        }
        player.playerListName(
            plugin.commsManager.parseLegacy("$prefix$name")
        )

        // Sort tab list by rank weight using scoreboard teams on EVERY player's scoreboard
        val rank = plugin.rankManager.getPlayerRank(player)
        val weight = rank?.weight ?: 0
        val sortKey = String.format("%03d", 999 - weight) // owner(100) → "899", default(0) → "999"
        val teamName = "ztab_${sortKey}"

        // Apply sort team on ALL online players' scoreboards so everyone sees the correct order
        for (online in Bukkit.getOnlinePlayers()) {
            val board = online.scoreboard
            // Remove this player from any old tab sort teams on this board
            for (t in board.teams.toList()) {
                if (t.name.startsWith("ztab_") && t.hasEntry(player.name)) {
                    t.removeEntry(player.name)
                }
            }
            // Add to correct sort team
            var team = board.getTeam(teamName)
            if (team == null) {
                team = board.registerNewTeam(teamName)
            }
            team.addEntry(player.name)
        }
    }

    // ── Utility ─────────────────────────────────────────────────────

    /**
     * Translates `&` color codes to [ChatColor] for legacy scoreboard strings.
     */
    private fun colorize(text: String): String {
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    private fun formatCompact(amount: Double): String {
        return when {
            amount >= 1_000_000_000_000 -> "${"%.1f".format(amount / 1_000_000_000_000)}T"
            amount >= 1_000_000_000 -> "${"%.1f".format(amount / 1_000_000_000)}B"
            amount >= 1_000_000 -> "${"%.1f".format(amount / 1_000_000)}M"
            amount >= 1_000 -> "${"%.1f".format(amount / 1_000)}K"
            else -> "${"%.0f".format(amount)}"
        }.replace(".0K", "K").replace(".0M", "M").replace(".0B", "B").replace(".0T", "T")
    }

    // ── Kill/Death Persistence ──────────────────────────────────────

    private fun loadStats() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid TEXT PRIMARY KEY,
                kills INTEGER DEFAULT 0,
                deaths INTEGER DEFAULT 0
            )
        """.trimIndent())

        kills.clear()
        deaths.clear()
        plugin.databaseManager.query("SELECT uuid, kills, deaths FROM player_stats") { rs ->
            val uuid = UUID.fromString(rs.getString("uuid"))
            val k = rs.getInt("kills")
            val d = rs.getInt("deaths")
            if (k > 0) kills[uuid] = k
            if (d > 0) deaths[uuid] = d
        }
    }

    private fun saveStats(uuid: UUID) {
        val k = kills.getOrDefault(uuid, 0)
        val d = deaths.getOrDefault(uuid, 0)
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_stats (uuid, kills, deaths) VALUES (?, ?, ?)",
            uuid.toString(), k, d
        )
    }

    fun saveAllStats() {
        for (uuid in (kills.keys + deaths.keys)) {
            saveStats(uuid)
        }
    }
}
