package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
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
            updateBelowNameHealth()
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
            org.bukkit.scoreboard.Criteria.DUMMY,
            plugin.commsManager.parseLegacy("&6&lJOSHYMC SURVIVAL")
        )
        objective.displaySlot = DisplaySlot.SIDEBAR
        // Hide the red score numbers
        objective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank())

        // Below-name health display: shows "<HP>/20 ❤" under every player's
        // nameplate from this player's POV. Uses a DUMMY criteria + periodic
        // task (see updateBelowNameHealth) so the suffix can be "/20 ❤".
        // Vanilla HEALTH criteria + HEARTS render type would only show an
        // icon row, no number.
        val belowName = board.registerNewObjective(
            "joshymc_health",
            org.bukkit.scoreboard.Criteria.DUMMY,
            plugin.commsManager.parseLegacy("&7/20 &c❤")
        )
        belowName.displaySlot = DisplaySlot.BELOW_NAME

        player.scoreboard = board

        // Defer one tick so RankManager's join handler can re-register rank
        // teams on this brand-new board. Without this the player joins with
        // a fresh scoreboard and never sees rank prefixes above heads.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) plugin.rankManager.applyTeamFor(player)
        }, 1L)
    }

    /**
     * Push every online player's HP into every viewer's BELOW_NAME objective.
     * Called from the periodic task — values that don't change don't trigger
     * client packets, so the cost is mostly the iteration. HP is rounded to
     * the nearest int so 19.5 → 20 (matches what hearts display anyway).
     */
    private fun updateBelowNameHealth() {
        for (viewer in Bukkit.getOnlinePlayers()) {
            val board = viewer.scoreboard
            val obj = board.getObjective("joshymc_health") ?: continue
            for (subject in Bukkit.getOnlinePlayers()) {
                val hp = subject.health.coerceAtLeast(0.0).toInt()
                obj.getScore(subject.name).score = hp
            }
        }
    }

    private fun updateSidebar(player: Player) {
        val board = player.scoreboard
        val objective = board.getObjective("joshymc_sidebar") ?: return

        // Clear existing line teams + score entries from the previous tick
        for (entry in board.entries.toSet()) {
            board.resetScores(entry)
        }
        for (t in board.teams.toList()) {
            if (t.name.startsWith("sbline_")) t.unregister()
        }

        val balance = formatCompact(plugin.economyManager.getBalance(player))
        val rank = plugin.rankManager.getPlayerRank(player)
        val rankTagComponent = rank?.displayTag?.let { plugin.commsManager.parseLegacy(it) }
            ?: plugin.commsManager.parseLegacy("&7None")
        val team = plugin.teamManager.getPlayerTeam(player.uniqueId) ?: "None"
        val playerKills = kills.getOrDefault(player.uniqueId, 0)
        val playtime = formatPlaytime(plugin.playtimeManager.getPlaytime(player.uniqueId))
        val ping = player.ping

        val now = java.time.LocalDateTime.now()
        val dateFmt = java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mma")
        val dateStr = now.format(dateFmt)

        // Build each line as an Adventure Component so hex codes like &#FF5555
        // on rank tags render via the same native-RGB path as the tab list
        // (instead of legacy §x which Minecraft may render slightly differently).
        val lines = mutableListOf<Component>()
        lines.add(plugin.commsManager.parseLegacy("&6&m                         "))
        lines.add(plugin.commsManager.parseLegacy("&7$dateStr"))
        lines.add(Component.empty())
        lines.add(plugin.commsManager.parseLegacy("&6&l${player.name}"))
        lines.add(plugin.commsManager.parseLegacy("&6| &7\u1D18\u026A\u0274\u0262&6: &3$ping"))
        lines.add(
            plugin.commsManager.parseLegacy("&6| &7\u0280\u1D00\u0274\u1D0B&6:&r ")
                .append(rankTagComponent)
        )
        if (team != "None") {
            lines.add(plugin.commsManager.parseLegacy("&6| &7\u1D1B\u1D07\u1D00\u1D0D&6: &b$team"))
        }
        lines.add(Component.empty())
        lines.add(plugin.commsManager.parseLegacy("&6&lStats:"))
        lines.add(plugin.commsManager.parseLegacy("&6| &7\u1D0D\u1D0F\u0274\u1D07\u028F&6: &a$$balance"))
        lines.add(plugin.commsManager.parseLegacy("&6| &7\u1D0B\u026A\u029F\u029F\uA731&6: &c$playerKills"))
        lines.add(plugin.commsManager.parseLegacy("&6| &7\u1D18\u029F\u1D00\u028F\u1D1B\u026A\u1D0D\u1D07&6: &e$playtime"))
        lines.add(Component.empty())
        lines.add(plugin.commsManager.parseLegacy("&7\u1D05\u026A\uA731\u1D04\u1D0F\u0280\u1D05.\u0262\u0262/\u1D0A\u1D0F\uA731\u029C\u028F\u1D0D\u1D04"))
        lines.add(plugin.commsManager.parseLegacy("&6&m                         "))

        // Team-prefix trick: each line is rendered as a team's prefix (Component)
        // attached to a unique invisible "entry" string. Top line gets the highest score.
        for ((index, component) in lines.withIndex()) {
            val entry = lineEntry(index)
            val teamName = "sbline_$index"
            val sbTeam = board.registerNewTeam(teamName)
            sbTeam.addEntry(entry)
            sbTeam.prefix(component)

            objective.getScore(entry).score = lines.size - index
        }
    }

    /** Unique, visually-empty entry string per sidebar row (combo of two color codes). */
    private fun lineEntry(index: Int): String {
        val hex = "0123456789abcdef"
        val a = hex[(index / 16) and 0xF]
        val b = hex[index and 0xF]
        return "\u00A7$a\u00A7$b"
    }

    private fun formatPlaytime(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        return "${days}d ${hours}h"
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

        // Tab list sort is handled by the jmc_* rank teams (named jmc_00_owner,
        // jmc_01_admin, …, jmc_06_default) — Bukkit sorts the player list by
        // team name, and these are already weight-prefixed in descending
        // order. We previously had a separate ztab_* team layer that ran
        // every 5 seconds and moved each player into a sort team, but a
        // player can only be in ONE scoreboard team per scoreboard, so the
        // ztab_* assignment was overwriting the jmc_* rank team — that's
        // why rank prefixes disappeared after the first tab refresh tick.
        //
        // Make sure stale ztab_* teams from old plugin versions are gone so
        // they don't keep stealing entries.
        for (online in Bukkit.getOnlinePlayers()) {
            val board = online.scoreboard
            board.teams
                .filter { it.name.startsWith("ztab_") }
                .forEach { it.unregister() }
        }
        // Re-anchor the player in their rank team in case anything else
        // moved them (e.g. a /reload mid-session).
        plugin.rankManager.applyTeamFor(player)
    }

    // ── Utility ─────────────────────────────────────────────────────

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
