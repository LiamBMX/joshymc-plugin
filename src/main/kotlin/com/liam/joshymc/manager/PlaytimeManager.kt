package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

class PlaytimeManager(private val plugin: Joshymc) : Listener {

    /** In-memory join timestamps (millis) for online players. */
    private val joinTimes = mutableMapOf<UUID, Long>()

    private var saveTaskId: Int = -1

    // ── Lifecycle ───────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS playtime (
                uuid TEXT PRIMARY KEY,
                total_seconds INTEGER DEFAULT 0,
                last_join INTEGER
            )
        """.trimIndent())

        // Register events
        plugin.server.pluginManager.registerEvents(this, plugin)

        // Restore join times for anyone already online (e.g. after reload)
        val now = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            joinTimes[player.uniqueId] = now
        }

        // Periodic save every 5 minutes (6000 ticks)
        saveTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            saveAllOnline()
        }, 6000L, 6000L)

        plugin.logger.info("[Playtime] Manager started.")
    }

    fun stop() {
        saveAllOnline()
        joinTimes.clear()
        if (saveTaskId != -1) {
            plugin.server.scheduler.cancelTask(saveTaskId)
            saveTaskId = -1
        }
    }

    // ── Events ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val now = System.currentTimeMillis()
        joinTimes[uuid] = now

        // Ensure row exists and update last_join
        plugin.databaseManager.execute(
            "INSERT INTO playtime (uuid, total_seconds, last_join) VALUES (?, 0, ?) ON CONFLICT(uuid) DO UPDATE SET last_join = ?",
            uuid.toString(), now, now
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flushPlayer(uuid)
        joinTimes.remove(uuid)
    }

    // ── Internal ────────────────────────────────────────

    /**
     * Flush a single player's accumulated session time to the database.
     */
    private fun flushPlayer(uuid: UUID) {
        val joinTime = joinTimes[uuid] ?: return
        val now = System.currentTimeMillis()
        val sessionSeconds = (now - joinTime) / 1000

        if (sessionSeconds > 0) {
            plugin.databaseManager.execute(
                "UPDATE playtime SET total_seconds = total_seconds + ?, last_join = ? WHERE uuid = ?",
                sessionSeconds, now, uuid.toString()
            )
        }

        // Reset the join time so we don't double-count on next flush
        joinTimes[uuid] = now
    }

    /**
     * Save all currently online players' session time.
     */
    private fun saveAllOnline() {
        for (uuid in joinTimes.keys.toList()) {
            flushPlayer(uuid)
        }
    }

    // ── Public API ──────────────────────────────────────

    /**
     * Returns total playtime in seconds for the given UUID.
     * Includes the current session if the player is online.
     */
    fun getPlaytime(uuid: UUID): Long {
        val stored = plugin.databaseManager.queryFirst(
            "SELECT total_seconds FROM playtime WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getLong("total_seconds") } ?: 0L

        // Add current session time if online
        val joinTime = joinTimes[uuid]
        val sessionExtra = if (joinTime != null) (System.currentTimeMillis() - joinTime) / 1000 else 0L

        return stored + sessionExtra
    }

    /**
     * Formats seconds into a human-readable string like "5d 3h 20m".
     */
    fun formatPlaytime(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"

        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60

        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0) append("${hours}h ")
            if (minutes > 0) append("${minutes}m")
        }.trim()
    }

    // ── Commands ────────────────────────────────────────

    inner class PlaytimeCommand : CommandExecutor, TabCompleter {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (!sender.hasPermission("joshymc.playtime")) {
                plugin.commsManager.send(sender as? Player ?: return true,
                    Component.text("No permission.", NamedTextColor.RED))
                return true
            }

            val target: Pair<String, UUID> = if (args.isNotEmpty()) {
                val name = args[0]
                val offlinePlayer = Bukkit.getOfflinePlayerIfCached(name)
                if (offlinePlayer == null) {
                    // Try online player
                    val online = Bukkit.getPlayerExact(name)
                    if (online == null) {
                        if (sender is Player) {
                            plugin.commsManager.send(sender,
                                Component.text("Player not found.", NamedTextColor.RED))
                        } else {
                            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                        }
                        return true
                    }
                    online.name to online.uniqueId
                } else {
                    (offlinePlayer.name ?: name) to offlinePlayer.uniqueId
                }
            } else {
                if (sender !is Player) {
                    sender.sendMessage(Component.text("Usage: /playtime <player>", NamedTextColor.RED))
                    return true
                }
                sender.name to sender.uniqueId
            }

            val seconds = getPlaytime(target.second)
            val formatted = formatPlaytime(seconds)

            val message = Component.text("${target.first}'s ", NamedTextColor.GRAY)
                .append(Component.text("Playtime: ", NamedTextColor.GRAY))
                .append(Component.text(formatted, NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))

            if (sender is Player) {
                plugin.commsManager.send(sender, message)
            } else {
                sender.sendMessage(message)
            }
            return true
        }

        override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
            if (args.size == 1) {
                return Bukkit.getOnlinePlayers()
                    .map { it.name }
                    .filter { it.startsWith(args[0], ignoreCase = true) }
            }
            return emptyList()
        }
    }

    inner class PlaytimeTopCommand : CommandExecutor {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (!sender.hasPermission("joshymc.playtime")) {
                if (sender is Player) {
                    plugin.commsManager.send(sender,
                        Component.text("No permission.", NamedTextColor.RED))
                } else {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                }
                return true
            }

            // Flush online players so the leaderboard is accurate
            saveAllOnline()

            val topPlayers = plugin.databaseManager.query(
                "SELECT uuid, total_seconds FROM playtime ORDER BY total_seconds DESC LIMIT 10"
            ) { rs ->
                UUID.fromString(rs.getString("uuid")) to rs.getLong("total_seconds")
            }

            if (topPlayers.isEmpty()) {
                if (sender is Player) {
                    plugin.commsManager.send(sender,
                        Component.text("No playtime data yet.", NamedTextColor.GRAY))
                } else {
                    sender.sendMessage(Component.text("No playtime data yet.", NamedTextColor.GRAY))
                }
                return true
            }

            val header = Component.text("--- ", NamedTextColor.DARK_GRAY)
                .append(Component.text("Top Playtimes", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" ---", NamedTextColor.DARK_GRAY))

            if (sender is Player) {
                plugin.commsManager.send(sender, header)
            } else {
                sender.sendMessage(header)
            }

            topPlayers.forEachIndexed { index, (uuid, seconds) ->
                // Add current session time for online players
                val totalSeconds = seconds + run {
                    val joinTime = joinTimes[uuid]
                    if (joinTime != null) (System.currentTimeMillis() - joinTime) / 1000 else 0L
                }

                val name = Bukkit.getOfflinePlayer(uuid).name ?: "Unknown"
                val rank = index + 1
                val formatted = formatPlaytime(totalSeconds)

                val line = Component.text("#$rank ", NamedTextColor.GOLD)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(formatted, NamedTextColor.GREEN))

                if (sender is Player) {
                    plugin.commsManager.sendRaw(sender, line)
                } else {
                    sender.sendMessage(line)
                }
            }

            return true
        }
    }
}
