package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.generator.ChunkGenerator
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.scoreboard.Team
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AFKManager(private val plugin: Joshymc) {

    private val afkPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val preAfkLocations = ConcurrentHashMap<UUID, Location>()
    private val teleporting = ConcurrentHashMap.newKeySet<UUID>()

    /**
     * AFK reward item. Resolution order at give time:
     *   1. [crateKeyType] — pulled from the crate registry as a key item
     *   2. [customItemId] — built from the custom-item registry
     *   3. [material] fallback
     */
    data class RewardItem(
        val material: Material,
        val amount: Int,
        val chance: Int,
        val customItemId: String? = null,
        val crateKeyType: String? = null
    )

    private var worldName = "afk"
    private var rewardEnabled = true
    private var rewardMoney = 0.0
    private var rewardXp = 0
    private var rewardItems: List<RewardItem> = emptyList()
    private var rewardIntervalTicks = 200L
    private var rewardIntervalSeconds = 10L
    private val random = Random()

    private var rewardTaskId = -1
    private var countdownTaskId = -1

    // Track per-player time until next reward for title display
    private val nextRewardTime = ConcurrentHashMap<UUID, Long>()
    // Track when player went AFK
    private val afkStartTime = ConcurrentHashMap<UUID, Long>()

    fun start() {
        worldName = plugin.config.getString("afk.world", "afk") ?: "afk"
        rewardEnabled = plugin.config.getBoolean("afk.reward.enabled", true)

        // Persistent pre-AFK location store — survives server restarts so a
        // player who quits while AFK isn't stranded in the void on rejoin.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS afk_pre_locations (
                uuid TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL
            )
        """.trimIndent())

        rewardMoney = plugin.config.getDouble("afk.reward.money", 0.0)
        rewardXp = plugin.config.getInt("afk.reward.xp", 0)
        rewardItems = loadRewardItems()

        val interval = plugin.config.getLong("afk.reward.interval", 10)
        val unit = plugin.config.getString("afk.reward.unit", "seconds") ?: "seconds"
        rewardIntervalSeconds = when (unit.lowercase()) {
            "minutes" -> interval * 60
            else -> interval
        }
        rewardIntervalTicks = rewardIntervalSeconds * 20

        // Create AFK void world if it doesn't exist
        ensureAfkWorld()

        if (rewardEnabled) {
            // Reward task ticks every second and only rewards a player when
            // their personal nextRewardTime has expired. Previously this ran
            // on a fixed global cadence (rewardIntervalTicks), so a player
            // who went AFK 10 seconds before the next tick would receive a
            // full reward that fast — producing the "got a key in 10 sec
            // even though interval=600s" glitch.
            rewardTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
                giveRewards()
            }, 20L, 20L)

            // Countdown title task — runs every second
            countdownTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
                updateCountdownTitles()
            }, 20L, 20L)
        }

        // Set up the no-collide team so multiple AFK players can stand on the
        // same bedrock without pushing each other off (which then cancels AFK).
        ensureNoCollideTeam()

        plugin.logger.info("[AFK] Manager started (world=$worldName, reward=${if (rewardEnabled) "${rewardItems.size} item type(s) / money=$rewardMoney / xp=$rewardXp every ${rewardIntervalSeconds}s" else "disabled"}).")
    }

    /**
     * Load reward items from `afk.reward.items`. Falls back to the legacy
     * single-item fields (`afk.reward.item` + `amount`) so older configs keep working.
     *
     * The `items` list is intentionally generous about format, since users
     * frequently hand-edit YAML and miss the schema. Accepted entry shapes:
     *
     *   - Map form (recommended):
     *       items:
     *         - material: afk_key       # vanilla material, custom item id, or "key:<crate>"
     *           amount: 1
     *           chance: 100
     *
     *   - String form:
     *       items:
     *         - afk_key                  # implicit amount=1, chance=100
     *         - "afk_key:1"              # name:amount
     *         - "afk_key:1:50"           # name:amount:chance
     *         - "key:afk:1:100"          # crate-key shorthand also works inside the string
     *
     * Resolution order for the name part:
     *   1. Crate key prefix    ("crate:<type>", "key:<type>")
     *   2. Bukkit Material     ("DIRT", "iron_ingot")
     *   3. Custom item id      ("afk_key", "easter_egg")
     */
    private fun loadRewardItems(): List<RewardItem> {
        val parsed = mutableListOf<RewardItem>()
        // ConfigurationSection.getList preserves the raw entries (maps OR strings).
        val raw = plugin.config.getList("afk.reward.items")
        if (raw != null) {
            for (entry in raw) {
                val item = parseRewardEntry(entry)
                if (item != null) {
                    parsed.add(item)
                } else {
                    plugin.logger.warning("[AFK] Could not parse reward entry: $entry")
                }
            }
        }

        if (parsed.isEmpty()) {
            // Legacy single-item fallback (also supports custom item ids and crate keys).
            val legacyName = plugin.config.getString("afk.reward.item", "DIRT") ?: "DIRT"
            val legacyAmt = plugin.config.getInt("afk.reward.amount", 1).coerceAtLeast(1)
            val resolved = resolveRewardItem(legacyName, legacyAmt, 100)
            if (resolved != null) {
                parsed.add(resolved)
            } else {
                plugin.logger.warning("[AFK] Legacy reward item '$legacyName' not found — defaulting to DIRT.")
                parsed.add(RewardItem(Material.DIRT, legacyAmt, 100))
            }
        }

        // Surface what we actually loaded so misconfigurations are obvious.
        for (r in parsed) {
            val label = r.crateKeyType?.let { "crate-key '$it'" }
                ?: r.customItemId?.let { "custom item '$it'" }
                ?: "material ${r.material.name}"
            plugin.logger.info("[AFK] Reward → $label x${r.amount} @ ${r.chance}%")
        }
        return parsed
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRewardEntry(entry: Any?): RewardItem? {
        when (entry) {
            null -> return null
            is Map<*, *> -> {
                val matName = entry["material"] as? String ?: return null
                val amt = (entry["amount"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                val chance = (entry["chance"] as? Number)?.toInt()?.coerceIn(0, 100) ?: 100
                return resolveRewardItem(matName, amt, chance)
            }
            is String -> {
                // Format: "<name>[:amount[:chance]]" — but "<name>" itself may
                // contain a single colon for the crate-key prefix ("key:afk").
                val trimmed = entry.trim()
                if (trimmed.isEmpty()) return null

                val (namePart, tail) = splitNameAndTail(trimmed)
                val parts = if (tail.isEmpty()) emptyList() else tail.split(':')
                val amt = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
                val chance = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 100) ?: 100
                return resolveRewardItem(namePart, amt, chance)
            }
            else -> return null
        }
    }

    /**
     * Splits a string like `"afk_key:1:50"` or `"key:afk:1:100"` into the
     * name portion (which may itself contain one colon for `key:` / `crate:`
     * prefixes) and the trailing numeric suffix.
     */
    private fun splitNameAndTail(s: String): Pair<String, String> {
        val lower = s.lowercase()
        if (lower.startsWith("crate:") || lower.startsWith("key:")) {
            // Two segments belong to the name: prefix + crate type
            val parts = s.split(':', limit = 4)
            return when (parts.size) {
                1, 2 -> s to ""                                   // "key:afk"
                3 -> "${parts[0]}:${parts[1]}" to parts[2]        // "key:afk:1"
                else -> "${parts[0]}:${parts[1]}" to "${parts[2]}:${parts[3]}"  // "key:afk:1:100"
            }
        }
        val idx = s.indexOf(':')
        return if (idx < 0) s to "" else s.substring(0, idx) to s.substring(idx + 1)
    }

    /**
     * Resolve a config string into a [RewardItem]. Accepts:
     *   - Bukkit material name           ("DIRT", "iron_ingot")
     *   - Custom item id                  ("afk_key", "easter_egg")
     *   - Crate key                       ("crate:afk", "key:vote")
     *   - "<id>:<amount>" shorthand       ("afk_key:1")
     *
     * The "crate:" / "key:" prefix is checked first so a crate type named "afk"
     * isn't confused with a custom item also called "afk_key". If the lookup
     * fails for any of these, returns null and the caller logs a warning.
     */
    private fun resolveRewardItem(rawName: String, defaultAmount: Int, chance: Int): RewardItem? {
        val name = rawName.trim()
        if (name.isEmpty()) return null

        // Explicit crate-key prefix: "crate:<type>" or "key:<type>".
        val lower = name.lowercase()
        if (lower.startsWith("crate:") || lower.startsWith("key:")) {
            val crateType = name.substringAfter(':').trim().lowercase()
            if (crateType.isEmpty()) return null
            // Use Material.PAPER as a placeholder; the real material comes from
            // CrateManager.createKeyStack at give time.
            return RewardItem(Material.PAPER, defaultAmount, chance, crateKeyType = crateType)
        }

        // Allow "name:amount" shorthand (pebblehost legacy format).
        val (key, amount) = if (name.contains(':')) {
            val parts = name.split(':', limit = 2)
            val parsedAmt = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: defaultAmount
            parts[0].trim() to parsedAmt
        } else {
            name to defaultAmount
        }

        // 1. Try vanilla Material
        val mat = Material.matchMaterial(key)
        if (mat != null) {
            return RewardItem(mat, amount, chance)
        }

        // 2. Try custom item id (case-insensitive — registry stores lowercase keys)
        val customId = key.lowercase()
        val custom = plugin.itemManager.getItem(customId)
        if (custom != null) {
            return RewardItem(custom.material, amount, chance, customItemId = customId)
        }

        return null
    }

    fun stop() {
        if (rewardTaskId != -1) {
            plugin.server.scheduler.cancelTask(rewardTaskId)
            rewardTaskId = -1
        }
        if (countdownTaskId != -1) {
            plugin.server.scheduler.cancelTask(countdownTaskId)
            countdownTaskId = -1
        }
        afkPlayers.clear()
        preAfkLocations.clear()
        teleporting.clear()
        nextRewardTime.clear()
        afkStartTime.clear()
    }

    fun toggleAfk(player: Player): Boolean {
        return if (isAfk(player)) {
            setAfk(player, false)
            false
        } else {
            setAfk(player, true)
            true
        }
    }

    fun setAfk(player: Player, afk: Boolean) {
        if (afk) {
            afkPlayers.add(player.uniqueId)
            val loc = player.location.clone()
            preAfkLocations[player.uniqueId] = loc
            persistPreAfkLocation(player.uniqueId, loc)
            afkStartTime[player.uniqueId] = System.currentTimeMillis()
            nextRewardTime[player.uniqueId] = System.currentTimeMillis() + (rewardIntervalSeconds * 1000)

            // Update display name with [AFK] tag, preserving any active nickname
            player.displayName(
                Component.text("[AFK] ", NamedTextColor.GRAY)
                    .append(player.displayName())
            )

            // Teleport to AFK world
            val afkWorld = Bukkit.getWorld(worldName)
            if (afkWorld != null) {
                teleporting.add(player.uniqueId)
                val spawnLoc = Location(afkWorld, 0.5, 65.0, 0.5, 0f, 0f)
                player.teleport(spawnLoc)
                // Remove teleport flag after a tick
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    teleporting.remove(player.uniqueId)
                }, 3L)
            } else {
                plugin.logger.warning("[AFK] World '$worldName' not found — skipping teleportation.")
            }

            // Apply permanent Blindness so the AFK area is just darkness with
            // the reward countdown title overlaid. ambient=true + hidden
            // particles/icon keeps the screen clean.
            player.addPotionEffect(
                PotionEffect(
                    PotionEffectType.BLINDNESS,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    /* ambient = */ true,
                    /* particles = */ false,
                    /* icon = */ false,
                )
            )

            // Disable collisions so other AFK players can't bump this player
            // off the spawn block (which would otherwise trigger the move
            // handler and cancel AFK).
            joinNoCollideTeam(player)

            plugin.commsManager.send(player,
                Component.text("You are now AFK.", NamedTextColor.GRAY),
                CommunicationsManager.Category.AFK
            )
        } else {
            afkPlayers.remove(player.uniqueId)
            nextRewardTime.remove(player.uniqueId)

            // Calculate AFK duration
            val startTime = afkStartTime.remove(player.uniqueId)
            val durationText = if (startTime != null) {
                val totalSeconds = (System.currentTimeMillis() - startTime) / 1000
                formatDuration(totalSeconds)
            } else "unknown"

            // Restore display name — reset to IGN first, then re-apply nick if present
            player.displayName(Component.text(player.name))
            player.playerListName(Component.text(player.name))
            com.liam.joshymc.command.NickCommand.loadNickname(plugin, player)

            // Clear title
            player.clearTitle()

            // Lift the AFK-zone blindness
            player.removePotionEffect(PotionEffectType.BLINDNESS)

            // Restore collisions + rank team
            leaveNoCollideTeam(player)

            // Teleport back
            val previousLocation = preAfkLocations.remove(player.uniqueId)
            clearPersistedPreAfkLocation(player.uniqueId)
            if (previousLocation != null) {
                teleporting.add(player.uniqueId)
                player.teleport(previousLocation)
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    teleporting.remove(player.uniqueId)
                }, 3L)
            }

            plugin.commsManager.send(player,
                Component.text("You are no longer AFK. You were AFK for ", NamedTextColor.GRAY)
                    .append(Component.text(durationText, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.AFK
            )
        }
    }

    fun isAfk(player: Player): Boolean = afkPlayers.contains(player.uniqueId)
    fun isAfk(uuid: UUID): Boolean = afkPlayers.contains(uuid)
    fun isTeleporting(player: Player): Boolean = teleporting.contains(player.uniqueId)

    fun handleQuit(player: Player) {
        if (isAfk(player)) {
            // Teleport back before quit data is saved so they rejoin at their original location
            val prev = preAfkLocations.remove(player.uniqueId)
            if (prev != null) {
                player.teleport(prev)
            }
            afkPlayers.remove(player.uniqueId)
            nextRewardTime.remove(player.uniqueId)
            afkStartTime.remove(player.uniqueId)
            teleporting.remove(player.uniqueId)
            // Blindness gets persisted with the player save; clear it so they
            // don't rejoin still blinded.
            player.removePotionEffect(PotionEffectType.BLINDNESS)
            leaveNoCollideTeam(player)
            // The persisted location row is intentionally kept until the player
            // rejoins, so handleJoin can rescue them if they spawn in the AFK world.
        }
    }

    /**
     * Rescue a player who logs in while still inside the AFK world (e.g. server
     * crashed before [handleQuit] could teleport them out). Sends them back to
     * their stored pre-AFK location, or the spawn world spawn as a fallback.
     */
    fun handleJoin(player: Player) {
        val inAfkWorld = player.world.name == worldName
        val saved = loadPersistedPreAfkLocation(player.uniqueId)

        if (inAfkWorld) {
            val target = saved ?: fallbackSpawnLocation()
            if (target != null) {
                // Defer one tick so the join is fully processed before teleport
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    if (player.isOnline) {
                        teleporting.add(player.uniqueId)
                        player.teleport(target)
                        plugin.server.scheduler.runTaskLater(plugin, Runnable {
                            teleporting.remove(player.uniqueId)
                        }, 3L)
                    }
                }, 2L)
            }
            // Player is being rescued out of the AFK world — strip the
            // AFK-zone blindness so they're not stranded blind at spawn,
            // and put them back in their rank team.
            player.removePotionEffect(PotionEffectType.BLINDNESS)
            leaveNoCollideTeam(player)
        }

        clearPersistedPreAfkLocation(player.uniqueId)
    }

    private fun fallbackSpawnLocation(): Location? {
        val warpSpawn = plugin.warpManager.getSpawn()
        if (warpSpawn != null) return warpSpawn
        val spawnWorld = Bukkit.getWorld("spawn") ?: Bukkit.getWorlds().firstOrNull { it.name != worldName }
        return spawnWorld?.spawnLocation
    }

    private fun persistPreAfkLocation(uuid: UUID, loc: Location) {
        val world = loc.world ?: return
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO afk_pre_locations (uuid, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?)",
            uuid.toString(), world.name, loc.x, loc.y, loc.z, loc.yaw.toDouble(), loc.pitch.toDouble()
        )
    }

    private fun clearPersistedPreAfkLocation(uuid: UUID) {
        plugin.databaseManager.execute(
            "DELETE FROM afk_pre_locations WHERE uuid = ?",
            uuid.toString()
        )
    }

    private fun loadPersistedPreAfkLocation(uuid: UUID): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM afk_pre_locations WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            val w = Bukkit.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(w, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    private fun giveRewards() {
        val now = System.currentTimeMillis()
        for (uuid in afkPlayers) {
            val player = Bukkit.getPlayer(uuid) ?: continue

            // Only reward players whose personal countdown has expired.
            val nextTime = nextRewardTime[uuid] ?: continue
            if (now < nextTime) continue

            val granted = mutableListOf<String>()

            if (rewardMoney > 0.0) {
                plugin.economyManager.deposit(uuid, rewardMoney)
                granted.add("+${plugin.economyManager.format(rewardMoney)}")
            }

            if (rewardXp > 0) {
                player.giveExpLevels(rewardXp)
                granted.add("+$rewardXp XP")
            }

            for (reward in rewardItems) {
                if (reward.chance < 100 && random.nextInt(100) >= reward.chance) continue
                val stack = when {
                    reward.crateKeyType != null ->
                        plugin.crateManager.createKeyStack(reward.crateKeyType, reward.amount)
                            ?: run {
                                plugin.logger.warning("[AFK] Crate type '${reward.crateKeyType}' not registered — skipping reward.")
                                continue
                            }
                    reward.customItemId != null ->
                        plugin.itemManager.getItem(reward.customItemId)?.createItemStack(reward.amount)
                            ?: ItemStack(reward.material, reward.amount)
                    else ->
                        ItemStack(reward.material, reward.amount)
                }
                val leftover = player.inventory.addItem(stack)
                if (leftover.isNotEmpty()) {
                    leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
                }
                val label = reward.crateKeyType?.let { "$it key" }
                    ?: reward.customItemId
                    ?: reward.material.name.lowercase().replace('_', ' ')
                granted.add("+${reward.amount} $label")
            }

            // Reset countdown
            nextRewardTime[uuid] = now + (rewardIntervalSeconds * 1000)

            if (granted.isNotEmpty()) {
                plugin.commsManager.sendActionBar(player,
                    Component.text(granted.joinToString("  "), NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }
    }

    private fun updateCountdownTitles() {
        val now = System.currentTimeMillis()
        for (uuid in afkPlayers) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val nextTime = nextRewardTime[uuid] ?: continue
            val remaining = ((nextTime - now) / 1000).coerceAtLeast(0)

            val title = Title.title(
                Component.text("AFK", TextColor.color(0x55FFFF))
                    .decoration(TextDecoration.BOLD, true),
                Component.text("Next reward in ", NamedTextColor.GRAY)
                    .append(Component.text(formatCountdown(remaining), NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
            )
            player.showTitle(title)
        }
    }

    /**
     * Pretty-print a countdown for the AFK title. Examples:
     *   600s → "10m"
     *   599s → "9m 59s"
     *   59s  → "59s"
     */
    private fun formatCountdown(totalSeconds: Long): String {
        if (totalSeconds < 60) return "${totalSeconds}s"
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
    }

    /**
     * Create the AFK no-collision scoreboard team if it doesn't exist.
     * Players added to this team can't push each other (or be pushed),
     * so two AFK players on the same bedrock block don't get bumped off
     * (which would otherwise cancel AFK via the move handler).
     */
    private fun ensureNoCollideTeam() {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        val team = board.getTeam(NO_COLLIDE_TEAM) ?: board.registerNewTeam(NO_COLLIDE_TEAM)
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER)
    }

    private fun joinNoCollideTeam(player: Player) {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        val team = board.getTeam(NO_COLLIDE_TEAM) ?: return
        // addEntry auto-removes the player from any other team on this
        // scoreboard (e.g. their rank team), which is what we want — we'll
        // restore the rank team via RankManager.applyTeamFor on un-AFK.
        team.addEntry(player.name)
    }

    private fun leaveNoCollideTeam(player: Player) {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        val team = board.getTeam(NO_COLLIDE_TEAM) ?: return
        team.removeEntry(player.name)
        // Restore the player's rank team so their nameplate prefix returns.
        plugin.rankManager.applyTeamFor(player)
    }

    companion object {
        private const val NO_COLLIDE_TEAM = "jmc_afk_nocollide"
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Creates a void world with a single bedrock block at 0, 64, 0.
     */
    private fun ensureAfkWorld() {
        if (Bukkit.getWorld(worldName) != null) return

        plugin.logger.info("[AFK] Creating void world '$worldName'...")

        val creator = WorldCreator(worldName)
            .type(WorldType.FLAT)
            .environment(World.Environment.NORMAL)
            .generator(object : ChunkGenerator() {
                // Empty generator — void world
            })
            .generateStructures(false)

        val world = creator.createWorld()
        if (world != null) {
            world.setSpawnLocation(0, 65, 0)
            world.setGameRuleValue("doDaylightCycle", "false")
            world.setGameRuleValue("doWeatherCycle", "false")
            world.setGameRuleValue("doMobSpawning", "false")
            world.setGameRuleValue("doFireTick", "false")
            world.time = 6000 // Noon

            // Place a single bedrock block
            world.getBlockAt(0, 64, 0).type = Material.BEDROCK

            plugin.logger.info("[AFK] Void world '$worldName' created.")
        } else {
            plugin.logger.severe("[AFK] Failed to create world '$worldName'!")
        }
    }
}
