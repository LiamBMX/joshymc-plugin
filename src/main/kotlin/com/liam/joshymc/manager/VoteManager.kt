package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.sun.net.httpserver.HttpServer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.net.InetSocketAddress
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class VoteManager(private val plugin: Joshymc) : Listener {

    private var httpServer: HttpServer? = null

    private var enabled = false
    private var port = 8192
    private var secretKey = "change-me-to-a-random-string"
    private var moneyReward = 25000.0
    private var claimBlockReward = 50
    private var itemRewards = listOf<String>()
    private var voteLinks = listOf<Pair<String, String>>()
    private var streakBonuses = mapOf<Int, Double>()

    fun start() {
        val cfg = plugin.config
        enabled = cfg.getBoolean("voting.enabled", false)
        if (!enabled) {
            plugin.logger.info("[Vote] Voting is disabled in config.")
            return
        }

        port = cfg.getInt("voting.port", 8192)
        secretKey = cfg.getString("voting.secret-key", secretKey) ?: secretKey
        moneyReward = cfg.getDouble("voting.rewards.money", 25000.0)
        claimBlockReward = cfg.getInt("voting.rewards.claim-blocks", 50)
        itemRewards = cfg.getStringList("voting.rewards.items")

        voteLinks = cfg.getMapList("voting.vote-links").mapNotNull { map ->
            val name = map["name"]?.toString() ?: return@mapNotNull null
            val url = map["url"]?.toString() ?: return@mapNotNull null
            name to url
        }

        streakBonuses = mutableMapOf<Int, Double>().also { bonuses ->
            val section = cfg.getConfigurationSection("voting.streak-bonuses")
            section?.getKeys(false)?.forEach { key ->
                val days = key.toIntOrNull() ?: return@forEach
                bonuses[days] = section.getDouble(key)
            }
        }

        createTables()
        startHttpServer()

        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("[Vote] VoteManager started on port $port.")
    }

    fun stop() {
        httpServer?.stop(0)
        httpServer = null
    }

    // ══════════════════════════════════════════════════════════
    //  DATABASE
    // ══════════════════════════════════════════════════════════

    private fun createTables() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_votes (
                uuid TEXT NOT NULL,
                username TEXT NOT NULL,
                service TEXT,
                voted_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS vote_streaks (
                uuid TEXT PRIMARY KEY,
                current_streak INTEGER DEFAULT 0,
                last_vote_date TEXT,
                total_votes INTEGER DEFAULT 0
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS pending_vote_rewards (
                uuid TEXT PRIMARY KEY,
                rewards TEXT NOT NULL
            )
        """.trimIndent())
    }

    // ══════════════════════════════════════════════════════════
    //  HTTP SERVER
    // ══════════════════════════════════════════════════════════

    private fun startHttpServer() {
        val server = HttpServer.create(InetSocketAddress(port), 0)

        server.createContext("/vote") { exchange ->
            try {
                val query = exchange.requestURI.query ?: ""
                val params = parseQueryParams(query)
                val username = params["username"]
                val key = params["key"]
                val service = params["service"] ?: "Unknown"

                if (key != secretKey) {
                    val response = "Invalid key"
                    exchange.sendResponseHeaders(403, response.length.toLong())
                    exchange.responseBody.use { it.write(response.toByteArray()) }
                    return@createContext
                }

                if (username.isNullOrBlank()) {
                    val response = "Missing username"
                    exchange.sendResponseHeaders(400, response.length.toLong())
                    exchange.responseBody.use { it.write(response.toByteArray()) }
                    return@createContext
                }

                // Process vote on the main thread
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    processVote(username, service)
                })

                val response = "Vote registered"
                exchange.sendResponseHeaders(200, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            } catch (e: Exception) {
                plugin.logger.warning("[Vote] Error handling vote request: ${e.message}")
                val response = "Internal error"
                exchange.sendResponseHeaders(500, response.length.toLong())
                exchange.responseBody.use { it.write(response.toByteArray()) }
            }
        }

        server.executor = null
        server.start()
        httpServer = server
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split("&").mapNotNull { param ->
            val parts = param.split("=", limit = 2)
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()
    }

    // ══════════════════════════════════════════════════════════
    //  VOTE PROCESSING
    // ══════════════════════════════════════════════════════════

    fun processVote(username: String, service: String) {
        val player = Bukkit.getPlayerExact(username)
        val uuid: UUID
        val resolvedName: String

        if (player != null) {
            uuid = player.uniqueId
            resolvedName = player.name
        } else {
            // Try to resolve offline player
            @Suppress("DEPRECATION")
            val offlinePlayer = Bukkit.getOfflinePlayer(username)
            if (!offlinePlayer.hasPlayedBefore() && !offlinePlayer.isOnline) {
                plugin.logger.warning("[Vote] Unknown player: $username")
                return
            }
            uuid = offlinePlayer.uniqueId
            resolvedName = offlinePlayer.name ?: username
        }

        // Record the vote
        plugin.databaseManager.execute(
            "INSERT INTO player_votes (uuid, username, service, voted_at) VALUES (?, ?, ?, ?)",
            uuid.toString(), resolvedName, service, System.currentTimeMillis()
        )

        // Update streak
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()

        val streakData = plugin.databaseManager.queryFirst(
            "SELECT current_streak, last_vote_date, total_votes FROM vote_streaks WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            Triple(rs.getInt("current_streak"), rs.getString("last_vote_date"), rs.getInt("total_votes"))
        }

        val currentStreak: Int
        val totalVotes: Int

        if (streakData == null) {
            currentStreak = 1
            totalVotes = 1
            plugin.databaseManager.execute(
                "INSERT INTO vote_streaks (uuid, current_streak, last_vote_date, total_votes) VALUES (?, ?, ?, ?)",
                uuid.toString(), currentStreak, today, totalVotes
            )
        } else {
            val (oldStreak, lastDate, oldTotal) = streakData

            if (lastDate == today) {
                // Already voted today — still count the vote record but don't change streak
                plugin.logger.info("[Vote] $resolvedName already voted today, skipping streak update.")
                return
            }

            currentStreak = if (lastDate == yesterday) oldStreak + 1 else 1
            totalVotes = oldTotal + 1

            plugin.databaseManager.execute(
                "UPDATE vote_streaks SET current_streak = ?, last_vote_date = ?, total_votes = ? WHERE uuid = ?",
                currentStreak, today, totalVotes, uuid.toString()
            )
        }

        // Build reward description for pending rewards
        val rewardParts = mutableListOf<String>()

        // Money reward
        if (moneyReward > 0) {
            rewardParts.add("money:$moneyReward")
        }

        // Claim blocks
        if (claimBlockReward > 0) {
            rewardParts.add("claimblocks:$claimBlockReward")
        }

        // Item rewards
        for (itemEntry in itemRewards) {
            rewardParts.add("item:$itemEntry")
        }

        // Streak bonus
        val streakBonus = streakBonuses[currentStreak]
        if (streakBonus != null && streakBonus > 0) {
            rewardParts.add("streakbonus:$streakBonus")
        }

        if (player != null && player.isOnline) {
            giveRewards(player, rewardParts, currentStreak)
            sendVoteMessage(player, currentStreak)
        } else {
            // Store pending rewards
            val rewardsStr = rewardParts.joinToString(";")
            plugin.databaseManager.execute(
                """INSERT INTO pending_vote_rewards (uuid, rewards) VALUES (?, ?)
                   ON CONFLICT(uuid) DO UPDATE SET rewards = rewards || ';' || ?""",
                uuid.toString(), rewardsStr, rewardsStr
            )
        }

        // Broadcast
        plugin.commsManager.broadcast(
            Component.text(resolvedName, NamedTextColor.GREEN)
                .append(Component.text(" voted and received rewards! ", NamedTextColor.GRAY))
                .append(Component.text("Vote streak: ", NamedTextColor.GRAY))
                .append(Component.text("$currentStreak", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun giveRewards(player: org.bukkit.entity.Player, rewardParts: List<String>, streak: Int) {
        for (part in rewardParts) {
            val (type, value) = part.split(":", limit = 2)
            when (type) {
                "money" -> {
                    val amount = value.toDoubleOrNull() ?: continue
                    plugin.economyManager.deposit(player.uniqueId, amount)
                }
                "claimblocks" -> {
                    val amount = value.toIntOrNull() ?: continue
                    plugin.claimManager.addBlocks(player.uniqueId, amount)
                }
                "item" -> {
                    val itemParts = value.split(":", limit = 2)
                    val itemId = itemParts[0]
                    val amount = itemParts.getOrNull(1)?.toIntOrNull() ?: 1
                    val customItem = plugin.itemManager.getItem(itemId)
                    if (customItem != null) {
                        val stack = customItem.createItemStack()
                        stack.amount = amount
                        val leftover = player.inventory.addItem(stack)
                        for (drop in leftover.values) {
                            player.world.dropItemNaturally(player.location, drop)
                        }
                    } else {
                        plugin.logger.warning("[Vote] Unknown item reward: $itemId")
                    }
                }
                "streakbonus" -> {
                    val amount = value.toDoubleOrNull() ?: continue
                    plugin.economyManager.deposit(player.uniqueId, amount)
                    plugin.commsManager.send(
                        player,
                        Component.text("Streak bonus! ", NamedTextColor.GOLD)
                            .decoration(TextDecoration.BOLD, true)
                            .append(Component.text("+${plugin.economyManager.format(amount)}", NamedTextColor.GREEN)
                                .decoration(TextDecoration.BOLD, false))
                            .append(Component.text(" for a $streak-day streak!", NamedTextColor.GRAY)
                                .decoration(TextDecoration.BOLD, false)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        }
    }

    private fun sendVoteMessage(player: org.bukkit.entity.Player, streak: Int) {
        plugin.commsManager.send(
            player,
            Component.text("Thanks for voting! ", NamedTextColor.GREEN)
                .append(Component.text("Your streak: ", NamedTextColor.GRAY))
                .append(Component.text("$streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.DEFAULT
        )

        // Show what they received
        val parts = mutableListOf<String>()
        if (moneyReward > 0) parts.add(plugin.economyManager.format(moneyReward))
        if (claimBlockReward > 0) parts.add("$claimBlockReward claim blocks")
        if (itemRewards.isNotEmpty()) parts.add("${itemRewards.size} item(s)")

        if (parts.isNotEmpty()) {
            plugin.commsManager.send(
                player,
                Component.text("Rewards: ", NamedTextColor.GRAY)
                    .append(Component.text(parts.joinToString(", "), NamedTextColor.GREEN)),
                CommunicationsManager.Category.DEFAULT
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    //  PENDING REWARDS (JOIN)
    // ══════════════════════════════════════════════════════════

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        if (!enabled) return
        val player = event.player
        val uuid = player.uniqueId.toString()

        val pendingRewards = plugin.databaseManager.queryFirst(
            "SELECT rewards FROM pending_vote_rewards WHERE uuid = ?",
            uuid
        ) { rs -> rs.getString("rewards") } ?: return

        // Delete first to prevent dupes
        val deleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM pending_vote_rewards WHERE uuid = ?",
            uuid
        )
        if (deleted == 0) return

        val rewardParts = pendingRewards.split(";").filter { it.isNotBlank() }
        val streak = getStreak(player.uniqueId)

        // Delay slightly so the player is fully loaded
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            giveRewards(player, rewardParts, streak)
            plugin.commsManager.send(
                player,
                Component.text("You had pending vote rewards! They have been delivered.", NamedTextColor.GREEN),
                CommunicationsManager.Category.DEFAULT
            )
        }, 40L) // 2 second delay
    }

    // ══════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════

    fun getStreak(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT current_streak FROM vote_streaks WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getInt("current_streak") } ?: 0
    }

    fun getTotalVotes(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT total_votes FROM vote_streaks WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getInt("total_votes") } ?: 0
    }

    fun getVoteLinks(): List<Pair<String, String>> = voteLinks

    /**
     * Returns the top voters by total votes, limited to [limit].
     */
    fun getTopVoters(limit: Int = 10): List<Triple<String, Int, Int>> {
        return plugin.databaseManager.query(
            """SELECT vs.uuid, vs.total_votes, vs.current_streak
               FROM vote_streaks vs
               ORDER BY vs.total_votes DESC
               LIMIT ?""",
            limit
        ) { rs ->
            Triple(rs.getString("uuid"), rs.getInt("total_votes"), rs.getInt("current_streak"))
        }
    }

    /**
     * Gets the last known username for a UUID from vote records.
     */
    fun getLastUsername(uuid: String): String {
        return plugin.databaseManager.queryFirst(
            "SELECT username FROM player_votes WHERE uuid = ? ORDER BY voted_at DESC LIMIT 1",
            uuid
        ) { rs -> rs.getString("username") } ?: "Unknown"
    }
}
