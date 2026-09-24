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
import org.bukkit.inventory.ItemStack
import org.bukkit.scoreboard.DisplaySlot
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScoreboardManager(private val plugin: Joshymc) : Listener {

    private var sidebarTaskId: Int = -1
    private var tabTaskId: Int = -1

    private val kills = ConcurrentHashMap<UUID, Int>()
    private val deaths = ConcurrentHashMap<UUID, Int>()
    private val sidebarLines = mutableMapOf<UUID, List<Component>>()

    /** All-time peak concurrent (real, connected) player count. Single source of truth. */
    private var peakPlayers: Int = 0

    /** Highest 5-player milestone (>= 20) that has already been rewarded. Persisted server-wide. */
    private var highestRewardedMilestone: Int = 0

    fun start() {
        // Load kills/deaths from DB
        loadStats()

        // Load the persisted all-time peak player count
        loadPeakPlayers()

        // Load (or first-time initialize) the highest player-count milestone already rewarded
        loadMilestoneState()

        // Set up scoreboards for all online players
        for (player in Bukkit.getOnlinePlayers()) {
            setupScoreboard(player)
            updateTabName(player)
        }

        // In case the stored peak is stale (e.g. table just created), check current count too
        checkPeakPlayers(Bukkit.getOnlinePlayers().size)

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
        sidebarLines.clear()

        // Clear scoreboards for all online players
        for (player in Bukkit.getOnlinePlayers()) {
            player.scoreboard = Bukkit.getScoreboardManager().newScoreboard
        }
    }

    // ── Event Handlers ──────────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        checkPeakPlayers(Bukkit.getOnlinePlayers().size)
        setupScoreboard(player)
        updateSidebar(player)
        updateTabHeaderFooter(player)
        updateTabName(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        sidebarLines.remove(event.player.uniqueId)
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

            val killerTeam = plugin.teamManager.getPlayerTeam(killer.uniqueId)
            if (killerTeam != null) {
                plugin.teamManager.addTeamKill(killerTeam)
            }
        }
    }

    // ── Sidebar ─────────────────────────────────────────────────────

    private fun setupScoreboard(player: Player) {
        sidebarLines.remove(player.uniqueId)
        val board = Bukkit.getScoreboardManager().newScoreboard
        val objective = board.registerNewObjective(
            "joshymc_sidebar",
            org.bukkit.scoreboard.Criteria.DUMMY,
            plugin.commsManager.parseLegacy("&6&lJoshyMC")
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

    /** Public entry point so the /scoreboard toggle can refresh instantly instead of waiting on the 2s tick. */
    fun refreshSidebar(player: Player) {
        updateSidebar(player)
    }

    private fun updateSidebar(player: Player) {
        val board = player.scoreboard
        val objective = board.getObjective("joshymc_sidebar") ?: return

        if (!plugin.settingsManager.getSetting(player, SCOREBOARD_SETTING_KEY)) {
            if (objective.displaySlot != null) objective.displaySlot = null
            return
        }
        if (objective.displaySlot != DisplaySlot.SIDEBAR) objective.displaySlot = DisplaySlot.SIDEBAR

        val balance = plugin.economyManager.formatShort(plugin.economyManager.getBalance(player))
        val credits = plugin.creditsManager.format(plugin.creditsManager.getBalance(player))
        val rank = plugin.rankManager.getPlayerRank(player)
        val rankTagComponent = rank?.displayTag?.let { plugin.commsManager.parseLegacy(it) }
            ?: plugin.commsManager.parseLegacy("&7None")
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        val team = teamName?.let { plugin.teamManager.getTeam(it)?.displayName } ?: "No Team"
        val playerKills = kills.getOrDefault(player.uniqueId, 0)
        val playerDeaths = deaths.getOrDefault(player.uniqueId, 0)
        val playtime = plugin.playtimeManager.formatPlaytimeShort(plugin.playtimeManager.getPlaytime(player.uniqueId))
        val ping = player.ping
        val dateTime = java.time.ZonedDateTime.now(plugin.timezoneManager.zoneFor(player)).format(DATE_TIME_FMT)

        val lines = mutableListOf<Component>()
        lines.add(plugin.commsManager.parseLegacy("&b${player.name} &7[&f$ping&7]"))
        lines.add(plugin.commsManager.parseLegacy("&f$dateTime"))
        lines.add(Component.empty())
        lines.add(
            plugin.commsManager.parseLegacy("&d\u2605 &f\u0280\u1D00\u0274\u1D0B&8: ")
                .append(rankTagComponent)
        )
        lines.add(plugin.commsManager.parseLegacy("&a$ &f\u1D0D\u1D0F\u0274\u1D07\u028F&8: &a$$balance"))
        lines.add(plugin.commsManager.parseLegacy("&e\u26C3 &f\u1D04\u0280\u1D07\u1D05\u026A\u1D1B\uA731&8: &e$credits"))
        lines.add(plugin.commsManager.parseLegacy("&c\u2694 &f\u1D0B\u026A\u029F\u029F\uA731&8: &c$playerKills"))
        lines.add(plugin.commsManager.parseLegacy("&6\u2620 &f\u1D05\u1D07\u1D00\u1D1B\u029C\uA731&8: &6$playerDeaths"))
        lines.add(Component.empty())
        lines.add(plugin.commsManager.parseLegacy("&f&l\u026A\u0274\uA730\u1D0F"))
        lines.add(plugin.commsManager.parseLegacy("&f\u1D1B\u1D07\u1D00\u1D0D&8: &b$team"))
        lines.add(plugin.commsManager.parseLegacy("&f\u1D18\u029F\u1D00\u028F\u1D1B\u026A\u1D0D\u1D07&8: &e$playtime"))

        // Team-prefix trick: each line is rendered as a team's prefix (Component)
        // attached to a unique invisible "entry" string. Top line gets the highest score.
        val previous = sidebarLines[player.uniqueId]
        for ((index, component) in lines.withIndex()) {
            val entry = lineEntry(index)
            val teamName = "sbline_$index"
            val existing = board.getTeam(teamName)
            val sbTeam = existing ?: board.registerNewTeam(teamName).also {
                it.addEntry(entry)
                objective.getScore(entry).score = lines.size - index
            }
            if (existing == null || previous?.getOrNull(index) != component) sbTeam.prefix(component)
        }
        sidebarLines[player.uniqueId] = lines
    }

    /** Unique, visually-empty entry string per sidebar row (combo of two color codes). */
    private fun lineEntry(index: Int): String {
        val hex = "0123456789abcdef"
        val a = hex[(index / 16) and 0xF]
        val b = hex[index and 0xF]
        return "\u00A7$a\u00A7$b"
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
                "&7\u1D18\u1D07\u1D00\u1D0B \u1D18\u029F\u1D00\u028F\u1D07\u0280\uA731&6: $peakPlayers\n" +
                "\n" +
                "&r"
            ))

        val footer = plugin.commsManager.parseLegacy(
            "\n" +
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

    }

    // ── Utility ─────────────────────────────────────────────────────

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

    // ── All-Time Peak Player Count ────────────────────────────────────

    private fun loadPeakPlayers() {
        val dbFile = java.io.File(plugin.dataFolder, "data.db")
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS peak_players (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                count INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        val persisted = plugin.databaseManager.queryFirst(
            "SELECT count FROM peak_players WHERE id = 1"
        ) { it.getInt("count") }
        peakPlayers = persisted ?: 0

        val online = Bukkit.getOnlinePlayers().size
        plugin.logger.info("[PeakPlayers] DB path: ${dbFile.absolutePath}")
        plugin.logger.info("[PeakPlayers] Persisted peak loaded: ${persisted ?: "none (row missing, defaulted to 0)"}")
        plugin.logger.info("[PeakPlayers] Online at startup: $online")
        plugin.logger.info("[PeakPlayers] In-memory peak after startup: $peakPlayers")
    }

    /** Bukkit.getOnlinePlayers() only ever contains real connected players — NPCs/bots never appear here. */
    private fun checkPeakPlayers(online: Int) {
        if (online <= peakPlayers) return
        val previous = peakPlayers
        peakPlayers = online
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO peak_players (id, count) VALUES (1, ?)",
            peakPlayers
        )
        plugin.logger.info("[PeakPlayers] New all-time peak: $peakPlayers (previous: $previous)")
        checkMilestoneReward(peakPlayers)
    }

    // ── Peak Player Milestone Rewards ─────────────────────────────────

    private fun loadMilestoneState() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_milestones (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                highest_rewarded INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        val stored = plugin.databaseManager.queryFirst(
            "SELECT highest_rewarded FROM player_milestones WHERE id = 1"
        ) { it.getInt("highest_rewarded") }

        highestRewardedMilestone = if (stored != null) {
            stored
        } else {
            // First deploy after this feature was added: don't retroactively reward
            // milestones the server already historically passed — just baseline
            // to the highest one already implied by the existing peak.
            val initial = milestoneFor(peakPlayers)
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_milestones (id, highest_rewarded) VALUES (1, ?)",
                initial
            )
            initial
        }
    }

    /** Highest completed 5-player milestone at or below [online], or 0 if below the 20-player threshold. */
    private fun milestoneFor(online: Int): Int {
        if (online < MILESTONE_START) return 0
        return (online / MILESTONE_STEP) * MILESTONE_STEP
    }

    private fun checkMilestoneReward(peak: Int) {
        val milestone = milestoneFor(peak)
        if (milestone <= highestRewardedMilestone) return

        highestRewardedMilestone = milestone
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_milestones (id, highest_rewarded) VALUES (1, ?)",
            highestRewardedMilestone
        )

        rewardOnlinePlayersForMilestone(milestone)
    }

    private fun rewardOnlinePlayersForMilestone(milestone: Int) {
        val moneyKey = plugin.itemManager.getItem("money_key")
        val creditKey = plugin.itemManager.getItem("credit_key")
        if (moneyKey == null || creditKey == null) {
            plugin.logger.warning("[Scoreboard] Could not find money_key/credit_key custom item(s) for milestone reward.")
            return
        }

        for (player in Bukkit.getOnlinePlayers()) {
            giveOrDrop(player, moneyKey.createItemStack().apply { amount = 5 })
            giveOrDrop(player, creditKey.createItemStack().apply { amount = 1 })
        }

        plugin.commsManager.broadcast(
            plugin.commsManager.parseLegacy(
                "&6&l🎉 PLAYER MILESTONE!\n" +
                "&e» &fWe just reached &6$milestone &fplayers online!\n" +
                "&e» &fEveryone online received &a5 Money Keys &f+ &b1 Credit Key&f!"
            )
        )
    }

    /** Adds to inventory, safely dropping any overflow at the player's feet instead of discarding it. */
    private fun giveOrDrop(player: Player, stack: ItemStack) {
        val leftover = player.inventory.addItem(stack)
        for (drop in leftover.values) {
            player.world.dropItemNaturally(player.location, drop)
        }
    }

    companion object {
        const val SCOREBOARD_SETTING_KEY = "scoreboard"

        /** e.g. "09/06/26 12:36 PM" — compact so the sidebar stays narrow. */
        private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yy hh:mm a")

        private const val MILESTONE_START = 20
        private const val MILESTONE_STEP = 5
    }
}
