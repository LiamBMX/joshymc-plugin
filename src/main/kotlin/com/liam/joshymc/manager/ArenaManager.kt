package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.time.Duration
import java.util.UUID

class ArenaManager(private val plugin: Joshymc) : Listener {

    data class Arena(
        val id: Int,
        val name: String,
        val world: String,
        val minY: Int,
        val points: List<Pair<Int, Int>>,
        val enabled: Boolean
    )

    private val arenas = mutableListOf<Arena>()
    private val arenaSelections = mutableMapOf<UUID, MutableList<Pair<Int, Int>>>()
    val playersInArena = mutableMapOf<UUID, Int>()

    private val wandKey = NamespacedKey(plugin, "arena_wand")

    private lateinit var pvpTask: BukkitTask
    private lateinit var particleTask: BukkitTask

    private lateinit var arenasFile: File
    private lateinit var arenasConfig: YamlConfiguration
    private var nextId: Int = 1

    private val comms get() = plugin.commsManager
    private val db get() = plugin.databaseManager

    // ── Lifecycle ────────────────────────────────────────

    fun start() {
        // Initialize YAML storage (supports plugins/joshymc/config/arenas.yml or plugins/joshymc/arenas.yml)
        arenasFile = plugin.configFile("arenas.yml")
        if (!arenasFile.exists()) {
            arenasFile.parentFile?.mkdirs()
            arenasFile.createNewFile()
        }
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile)

        // One-time migration from the old SQLite arenas table to arenas.yml
        migrateFromDatabaseIfNeeded()

        loadArenas()

        pvpTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickPvpZones() }, 20L, 10L)
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickBorderParticles() }, 40L, 30L)

        plugin.logger.info("[Arena] Loaded ${arenas.size} arena(s) from arenas.yml.")
    }

    /**
     * If the legacy SQLite arenas table exists with rows AND arenas.yml is empty,
     * copy them over once. This makes the upgrade seamless for existing servers.
     */
    private fun migrateFromDatabaseIfNeeded() {
        if (arenasConfig.getConfigurationSection("arenas") != null) return // already populated

        try {
            // Try to read from the old DB table — if it doesn't exist this throws
            val rows = db.query("SELECT id, name, world, min_y, points, enabled FROM arenas") { rs ->
                Arena(
                    id = rs.getInt("id"),
                    name = rs.getString("name"),
                    world = rs.getString("world"),
                    minY = rs.getInt("min_y"),
                    points = parsePoints(rs.getString("points")),
                    enabled = rs.getInt("enabled") == 1
                )
            }
            if (rows.isEmpty()) return

            for (arena in rows) {
                writeArenaToConfig(arena)
            }
            arenasConfig.save(arenasFile)
            plugin.logger.info("[Arena] Migrated ${rows.size} arena(s) from data.db to arenas.yml.")
        } catch (_: Exception) {
            // No legacy table, nothing to migrate
        }
    }

    fun shutdown() {
        if (::pvpTask.isInitialized) pvpTask.cancel()
        if (::particleTask.isInitialized) particleTask.cancel()
        playersInArena.clear()
    }

    // ── Public API ───────────────────────────────────────

    fun isInArena(player: Player): Boolean = playersInArena.containsKey(player.uniqueId)

    fun isInArena(uuid: UUID): Boolean = playersInArena.containsKey(uuid)

    fun getArena(name: String): Arena? = arenas.find { it.name.equals(name, ignoreCase = true) }

    fun getAllArenas(): List<Arena> = arenas.toList()

    // ── Storage (arenas.yml) ─────────────────────────────

    private fun loadArenas() {
        arenas.clear()
        // Re-read from disk so external edits are picked up
        arenasConfig = YamlConfiguration.loadConfiguration(arenasFile)
        val section = arenasConfig.getConfigurationSection("arenas") ?: run {
            nextId = 1
            return
        }

        var maxId = 0
        for (key in section.getKeys(false)) {
            val arenaSection = section.getConfigurationSection(key) ?: continue
            try {
                val id = arenaSection.getInt("id", key.toIntOrNull() ?: 0)
                val name = arenaSection.getString("name") ?: key
                val world = arenaSection.getString("world") ?: continue
                val minY = arenaSection.getInt("min-y")
                val enabled = arenaSection.getBoolean("enabled", true)
                val points = arenaSection.getStringList("points").mapNotNull { ptStr ->
                    val parts = ptStr.split(",")
                    if (parts.size == 2) {
                        val x = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                        val z = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                        x to z
                    } else null
                }
                arenas.add(Arena(id, name, world, minY, points, enabled))
                if (id > maxId) maxId = id
            } catch (e: Exception) {
                plugin.logger.warning("[Arena] Failed to load arena '$key': ${e.message}")
            }
        }
        nextId = maxId + 1
    }

    private fun writeArenaToConfig(arena: Arena) {
        val path = "arenas.${arena.name.lowercase()}"
        arenasConfig.set("$path.id", arena.id)
        arenasConfig.set("$path.name", arena.name)
        arenasConfig.set("$path.world", arena.world)
        arenasConfig.set("$path.min-y", arena.minY)
        arenasConfig.set("$path.enabled", arena.enabled)
        arenasConfig.set("$path.points", arena.points.map { "${it.first},${it.second}" })
    }

    private fun saveArenasFile() {
        try {
            arenasConfig.save(arenasFile)
        } catch (e: Exception) {
            plugin.logger.warning("[Arena] Failed to save arenas.yml: ${e.message}")
        }
    }

    private fun parsePoints(json: String): List<Pair<Int, Int>> {
        // Format: [[x1,z1],[x2,z2],...]
        val cleaned = json.trim().removeSurrounding("[", "]")
        if (cleaned.isBlank()) return emptyList()
        val pairs = mutableListOf<Pair<Int, Int>>()
        var depth = 0
        var current = StringBuilder()
        for (c in cleaned) {
            when {
                c == '[' -> { depth++; current.append(c) }
                c == ']' -> { depth--; current.append(c)
                    if (depth == 0) {
                        val inner = current.toString().removeSurrounding("[", "]").split(",")
                        if (inner.size == 2) {
                            pairs.add(inner[0].trim().toInt() to inner[1].trim().toInt())
                        }
                        current = StringBuilder()
                    }
                }
                c == ',' && depth == 0 -> { /* skip separator between pairs */ }
                else -> current.append(c)
            }
        }
        return pairs
    }

    private fun serializePoints(points: List<Pair<Int, Int>>): String {
        return points.joinToString(",", "[", "]") { "[${it.first},${it.second}]" }
    }

    private fun createArena(name: String, world: String, minY: Int, points: List<Pair<Int, Int>>) {
        val arena = Arena(nextId++, name, world, minY, points, true)
        writeArenaToConfig(arena)
        saveArenasFile()
        loadArenas()
    }

    private fun deleteArena(name: String): Boolean {
        val arena = arenas.find { it.name.equals(name, ignoreCase = true) } ?: return false
        playersInArena.entries.removeIf { it.value == arena.id }
        arenasConfig.set("arenas.${arena.name.lowercase()}", null)
        saveArenasFile()
        loadArenas()
        return true
    }

    private fun setEnabled(name: String, enabled: Boolean): Boolean {
        val arena = arenas.find { it.name.equals(name, ignoreCase = true) } ?: return false
        if (!enabled) {
            playersInArena.entries.removeIf { it.value == arena.id }
        }
        arenasConfig.set("arenas.${arena.name.lowercase()}.enabled", enabled)
        saveArenasFile()
        loadArenas()
        return true
    }

    // ── Point-in-polygon ─────────────────────────────────

    fun isInsidePolygon(x: Double, z: Double, points: List<Pair<Int, Int>>): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val xi = points[i].first.toDouble()
            val zi = points[i].second.toDouble()
            val xj = points[j].first.toDouble()
            val zj = points[j].second.toDouble()

            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun findArenaAt(player: Player): Arena? {
        val loc = player.location
        val worldName = loc.world.name
        return arenas.firstOrNull { arena ->
            arena.enabled
                    && arena.world == worldName
                    && loc.blockY >= arena.minY
                    && isInsidePolygon(loc.x, loc.z, arena.points)
        }
    }

    // ── PvP zone tick (every 10 ticks) ───────────────────

    private fun tickPvpZones() {
        for (player in Bukkit.getOnlinePlayers()) {
            val arena = findArenaAt(player)
            val wasIn = playersInArena[player.uniqueId]

            if (arena != null && wasIn == null) {
                // Refuse to admit combat-tagged players into the arena cache.
                // If they got knockback-pushed across the boundary while
                // tagged, they're being farmed \u2014 don't let the cache mark
                // them as "in arena" so the onDamage check still treats them
                // as outside.
                if (plugin.combatManager.isTagged(player)) {
                    continue
                }
                // AFK players also shouldn't be admitted (they can't leave
                // by themselves and would be sitting ducks).
                if (plugin.afkManager.isAfk(player)) {
                    continue
                }

                // Entering arena \u2014 force PvP on so the player can fight without
                // having to flip /pvp first. The setting persists, but most
                // players want it on inside the arena anyway.
                playersInArena[player.uniqueId] = arena.id
                if (!plugin.settingsManager.getSetting(player, CombatManager.PVP_SETTING_KEY)) {
                    plugin.settingsManager.setSetting(player, CombatManager.PVP_SETTING_KEY, true)
                    comms.send(player, Component.text("PvP auto-enabled \u2014 you entered an arena.", NamedTextColor.YELLOW))
                }
                player.showTitle(Title.title(
                    Component.text("\u2694 PvP Zone", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
                    Component.text(arena.name, NamedTextColor.GRAY),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
                ))
                player.playSound(player.location, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.3f, 1.2f)
            } else if (arena == null && wasIn != null) {
                // Leaving arena — remove barrier if they had one
                val oldArena = arenas.find { it.id == wasIn }
                if (oldArena != null) hideBarrier(player, oldArena)
                playersInArena.remove(player.uniqueId)
                player.showTitle(Title.title(
                    Component.text("Safe Zone", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                    Component.empty(),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(500))
                ))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, 1.5f)
            } else if (arena != null && wasIn != null && arena.id != wasIn) {
                // Moved to a different arena
                playersInArena[player.uniqueId] = arena.id
            }
        }

        // Clean up offline players
        playersInArena.keys.removeIf { Bukkit.getPlayer(it) == null }

        // Tick combat barriers
        tickCombatBarriers()
    }

    // ── Border particles (every 30 ticks) ────────────────

    private fun tickBorderParticles() {
        val dustOptions = Particle.DustOptions(Color.RED, 2.5f)

        for (arena in arenas) {
            if (!arena.enabled) continue
            val world = Bukkit.getWorld(arena.world) ?: continue
            val y = arena.minY + 1.0

            val nearbyPlayers = world.players.filter { p ->
                arena.points.any { pt ->
                    val dx = p.location.x - pt.first
                    val dz = p.location.z - pt.second
                    dx * dx + dz * dz < 2500 // within 50 blocks
                }
            }
            if (nearbyPlayers.isEmpty()) continue

            val points = arena.points
            for (i in points.indices) {
                val p1 = points[i]
                val p2 = points[(i + 1) % points.size]

                val dx = (p2.first - p1.first).toDouble()
                val dz = (p2.second - p1.second).toDouble()
                val dist = kotlin.math.sqrt(dx * dx + dz * dz)
                if (dist < 0.1) continue

                val steps = (dist / 1.0).toInt().coerceAtLeast(1) // Every 1 block
                for (s in 0..steps) {
                    val t = s.toDouble() / steps
                    val px = p1.first + dx * t + 0.5
                    val pz = p1.second + dz * t + 0.5

                    // Draw at 3 Y levels for visibility
                    for (yOff in 0..2) {
                        val loc = Location(world, px, y + yOff, pz)
                        for (viewer in nearbyPlayers) {
                            viewer.spawnParticle(Particle.DUST, loc, 2, 0.05, 0.2, 0.05, 0.0, dustOptions)
                        }
                    }
                }
            }
        }
    }

    // ── Arena wand interaction ────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onWandUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(wandKey, PersistentDataType.INTEGER)) return

        event.isCancelled = true

        val player = event.player
        val block = event.clickedBlock ?: return
        val x = block.x
        val z = block.z

        val selection = arenaSelections.getOrPut(player.uniqueId) { mutableListOf() }
        selection.add(x to z)

        comms.send(player, Component.text("Point #${selection.size} added at ($x, $z). Total: ${selection.size} points.", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.2f)

        // Spawn green particle at the point
        val loc = Location(block.world, x + 0.5, block.y + 1.0, z + 0.5)
        player.spawnParticle(Particle.HAPPY_VILLAGER, loc, 10, 0.3, 0.3, 0.3, 0.0)
    }

    // ── PvP override ─────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val attacker = getDamager(event) ?: return
        val victim = event.entity as? Player ?: return

        // AFK players are invulnerable, full stop. Even if both happen to be
        // inside the polygon (because someone shoved an AFK player across
        // the border with knockback), don't let the arena override the AFK
        // damage immunity.
        if (plugin.afkManager.isAfk(victim) || plugin.afkManager.isAfk(attacker)) {
            event.isCancelled = true
            return
        }

        val attackerCached = playersInArena[attacker.uniqueId]
        val victimCached = playersInArena[victim.uniqueId]
        val attackerLiveId = liveArenaFor(attacker)?.id
        val victimLiveId = liveArenaFor(victim)?.id

        // Strict same-arena check: BOTH players must be admitted to the
        // same arena (cache) AND currently inside that arena's polygon
        // (live). This rejects:
        //   - Attacker stepped out (cache says in, live says no) — won't
        //     uncancel, falls to the cross-border path below.
        //   - Victim got pushed in via knockback (cache=null because we
        //     refuse to admit tagged players in tickPvpZones) — won't
        //     uncancel.
        val sameArena = attackerCached != null
                && attackerCached == victimCached
                && attackerLiveId == attackerCached
                && victimLiveId == attackerCached
        if (sameArena) {
            event.isCancelled = false
            return
        }

        // Anyone touching an arena (cached OR live) but not paired up by
        // the strict check above — cancel the hit. Covers shoot-from-
        // outside, hit-into-outside, and the pushed-in-victim case.
        val attackerInAny = attackerCached != null || attackerLiveId != null
        val victimInAny = victimCached != null || victimLiveId != null
        if (attackerInAny || victimInAny) {
            event.isCancelled = true
            plugin.combatManager.untag(attacker)
            plugin.combatManager.untag(victim)
            return
        }

        // In spawn world but NOT in an arena — block ALL PvP
        if (victim.world.name == "spawn") {
            event.isCancelled = true
            plugin.combatManager.untag(attacker)
            plugin.combatManager.untag(victim)
        }
    }

    /** Live polygon check on the player's current location, so a step
     *  across the boundary in the last 0.5s window doesn't get missed. */
    private fun liveArenaFor(player: Player): Arena? = findArenaAt(player)

    /**
     * Real server-side arena boundary while combat-tagged. The fake-barrier
     * block-change packet was just a visual hint; players could keep
     * walking and the client would reconcile to the actual air block on
     * the next position update. Now we cancel any PlayerMoveEvent whose
     * destination crosses out of the polygon while the player is tagged,
     * pinning them back at the last in-arena position.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onCombatBoundary(event: org.bukkit.event.player.PlayerMoveEvent) {
        val player = event.player
        if (!plugin.combatManager.isTagged(player)) return

        val from = event.from
        val to = event.to ?: return
        if (from.blockX == to.blockX && from.blockZ == to.blockZ) return

        // Only enforce when the player IS in an arena. Find via cached map
        // first; fall back to live polygon check on `from` to handle players
        // who got tagged just outside the cache refresh window.
        val arenaId = playersInArena[player.uniqueId]
        val arena = (if (arenaId != null) arenas.find { it.id == arenaId } else null)
            ?: findArenaAt(player)
            ?: return

        val fromInside = isInsidePolygon(from.x, from.z, arena.points)
        val toInside = isInsidePolygon(to.x, to.z, arena.points)
        if (fromInside && !toInside) {
            // Pin them at the last in-polygon position. Use setTo so head
            // rotation is preserved (to.yaw / to.pitch) and only the position
            // is reverted — feels less janky than cancelling the whole event.
            val pinned = from.clone()
            pinned.yaw = to.yaw
            pinned.pitch = to.pitch
            event.setTo(pinned)
            plugin.commsManager.sendActionBar(
                player,
                Component.text("You can't leave the arena while in combat!", NamedTextColor.RED)
            )
        }
    }

    // ── Combat barrier: fake barrier blocks along arena border ──────────

    /** Players who currently have barrier walls shown */
    private val barrierPlayers = mutableSetOf<UUID>()
    /** Cached barrier block locations per arena (computed once) */
    private val arenaBarrierBlocks = mutableMapOf<Int, List<Location>>()

    /**
     * Compute barrier block positions along the polygon border for an arena.
     * Creates a wall 5 blocks tall at each border point.
     */
    private fun computeBarrierBlocks(arena: Arena): List<Location> {
        val world = Bukkit.getWorld(arena.world) ?: return emptyList()
        val blocks = mutableListOf<Location>()
        val points = arena.points
        val wallHeight = 10

        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            val dx = (p2.first - p1.first).toDouble()
            val dz = (p2.second - p1.second).toDouble()
            val dist = kotlin.math.sqrt(dx * dx + dz * dz)
            if (dist < 0.1) continue

            val steps = dist.toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val t = s.toDouble() / steps
                val bx = (p1.first + dx * t).toInt()
                val bz = (p1.second + dz * t).toInt()
                for (dy in 0 until wallHeight) {
                    blocks.add(Location(world, bx.toDouble(), (arena.minY + dy).toDouble(), bz.toDouble()))
                }
            }
        }
        return blocks
    }

    /**
     * Send fake BARRIER blocks to a player along the arena border.
     */
    fun showBarrier(player: Player, arena: Arena) {
        if (barrierPlayers.contains(player.uniqueId)) return
        barrierPlayers.add(player.uniqueId)

        val blocks = arenaBarrierBlocks.getOrPut(arena.id) { computeBarrierBlocks(arena) }
        val world = Bukkit.getWorld(arena.world) ?: return
        for (loc in blocks) {
            // Only place barrier on air blocks — don't overwrite existing builds
            val real = world.getBlockAt(loc)
            if (real.type.isAir || real.type == Material.CAVE_AIR) {
                player.sendBlockChange(loc, Material.BARRIER.createBlockData())
            }
        }
    }

    /**
     * Remove fake barrier blocks — send the real block data back.
     */
    fun hideBarrier(player: Player, arena: Arena) {
        if (!barrierPlayers.remove(player.uniqueId)) return

        val blocks = arenaBarrierBlocks[arena.id] ?: return
        val world = Bukkit.getWorld(arena.world) ?: return
        for (loc in blocks) {
            val realBlock = world.getBlockAt(loc)
            player.sendBlockChange(loc, realBlock.blockData)
        }
    }

    /**
     * Check combat state changes — show/hide barriers in the PvP zone tick.
     */
    private fun tickCombatBarriers() {
        for (player in Bukkit.getOnlinePlayers()) {
            val uuid = player.uniqueId
            val arenaId = playersInArena[uuid] ?: continue
            val arena = arenas.find { it.id == arenaId } ?: continue

            val inCombat = plugin.combatManager.isTagged(player)
            val hasBarrier = barrierPlayers.contains(uuid)

            if (inCombat && !hasBarrier) {
                showBarrier(player, arena)
                player.sendActionBar(Component.text("Combat barrier active!", NamedTextColor.RED))
            } else if (!inCombat && hasBarrier) {
                hideBarrier(player, arena)
                player.sendActionBar(Component.text("Combat barrier dropped.", NamedTextColor.GREEN))
            }
        }

        // Clean up barriers for players who left the arena or went offline
        val toRemove = mutableListOf<UUID>()
        for (uuid in barrierPlayers) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !playersInArena.containsKey(uuid)) {
                toRemove.add(uuid)
            }
        }
        for (uuid in toRemove) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val arenaId = playersInArena[uuid]
            val arena = if (arenaId != null) arenas.find { it.id == arenaId } else arenas.firstOrNull()
            if (arena != null) hideBarrier(player, arena)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        val arenaId = playersInArena.remove(uuid)
        if (arenaId != null) {
            val arena = arenas.find { it.id == arenaId }
            if (arena != null) hideBarrier(event.player, arena)
        }
        barrierPlayers.remove(uuid)
    }

    private fun getDamager(event: EntityDamageByEntityEvent): Player? {
        val damager = event.damager
        if (damager is Player) return damager
        if (damager is org.bukkit.entity.Projectile) {
            val shooter = damager.shooter
            if (shooter is Player) return shooter
        }
        return null
    }

    // ── Wand item builder ────────────────────────────────

    private fun createWand(): ItemStack {
        val item = ItemStack(Material.GOLD_BLOCK)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text("Arena Wand", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.empty(),
            Component.text("Right-click blocks to add arena border points.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Use /arena create <name> <min-y> to save.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))
        meta.persistentDataContainer.set(wandKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    // ── Command handler ──────────────────────────────────

    inner class ArenaCommand : CommandExecutor, TabCompleter {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (sender !is Player) {
                sender.sendMessage("Players only.")
                return true
            }
            if (!sender.hasPermission("joshymc.arena")) {
                comms.send(sender, Component.text("No permission.", NamedTextColor.RED))
                return true
            }

            if (args.isEmpty()) {
                sendUsage(sender)
                return true
            }

            when (args[0].lowercase()) {
                "wand" -> handleWand(sender)
                "create" -> handleCreate(sender, args)
                "delete" -> handleDelete(sender, args)
                "list" -> handleList(sender)
                "info" -> handleInfo(sender, args)
                "enable" -> handleToggle(sender, args, true)
                "disable" -> handleToggle(sender, args, false)
                "points" -> handlePoints(sender)
                "undo" -> handleUndo(sender)
                "clear" -> handleClear(sender)
                "tp" -> handleTeleport(sender, args)
                "debug" -> {
                    val loc = sender.location
                    comms.send(sender, Component.text("Your position: ${loc.x.toInt()}, ${loc.z.toInt()}", NamedTextColor.GRAY))
                    for (arena in arenas) {
                        val inside = isInsidePolygon(loc.x, loc.z, arena.points)
                        val yOk = loc.blockY >= arena.minY
                        val worldOk = loc.world.name == arena.world
                        val color = if (inside && yOk && worldOk) NamedTextColor.GREEN else NamedTextColor.RED
                        comms.send(sender, Component.text("${arena.name}: polygon=$inside y=$yOk(${loc.blockY}>=${arena.minY}) world=$worldOk | ${arena.points.size} points", color))
                    }
                }
                else -> sendUsage(sender)
            }
            return true
        }

        private fun sendUsage(player: Player) {
            val lines = listOf(
                "",
                "&6&lArena Commands:",
                "&e/arena wand &7- Get selection wand",
                "&e/arena points &7- Show current selection",
                "&e/arena undo &7- Remove last point",
                "&e/arena clear &7- Clear selection",
                "&e/arena create <name> <min-y> &7- Create arena",
                "&e/arena delete <name> &7- Delete arena",
                "&e/arena list &7- List all arenas",
                "&e/arena info <name> &7- Arena details",
                "&e/arena enable/disable <name> &7- Toggle arena",
                "&e/arena tp <name> &7- Teleport to arena center",
                ""
            )
            for (line in lines) {
                player.sendMessage(comms.parseLegacy(line))
            }
        }

        private fun handleWand(player: Player) {
            player.inventory.addItem(createWand())
            comms.send(player, Component.text("Arena wand added to inventory.", NamedTextColor.GREEN))
        }

        private fun handleCreate(player: Player, args: Array<out String>) {
            if (args.size < 3) {
                comms.send(player, Component.text("Usage: /arena create <name> <min-y>", NamedTextColor.RED))
                return
            }
            val name = args[1]
            val minY = args[2].toIntOrNull()
            if (minY == null) {
                comms.send(player, Component.text("min-y must be a number.", NamedTextColor.RED))
                return
            }

            val selection = arenaSelections[player.uniqueId]
            if (selection == null || selection.size < 3) {
                comms.send(player, Component.text("You need at least 3 points. Use the arena wand to add points.", NamedTextColor.RED))
                return
            }

            if (getArena(name) != null) {
                comms.send(player, Component.text("An arena with that name already exists.", NamedTextColor.RED))
                return
            }

            createArena(name, player.world.name, minY, selection.toList())
            arenaSelections.remove(player.uniqueId)

            comms.send(player, Component.text("Arena ", NamedTextColor.GREEN)
                .append(Component.text(name, NamedTextColor.GOLD))
                .append(Component.text(" created with ${selection.size} points.", NamedTextColor.GREEN)))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f)
        }

        private fun handleDelete(player: Player, args: Array<out String>) {
            if (args.size < 2) {
                comms.send(player, Component.text("Usage: /arena delete <name>", NamedTextColor.RED))
                return
            }
            val name = args[1]
            if (deleteArena(name)) {
                comms.send(player, Component.text("Arena ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.GOLD))
                    .append(Component.text(" deleted.", NamedTextColor.GREEN)))
            } else {
                comms.send(player, Component.text("Arena not found.", NamedTextColor.RED))
            }
        }

        private fun handleList(player: Player) {
            if (arenas.isEmpty()) {
                comms.send(player, Component.text("No arenas defined.", NamedTextColor.GRAY))
                return
            }
            comms.send(player, Component.text("Arenas (${arenas.size}):", NamedTextColor.GOLD))
            for (arena in arenas) {
                val status = if (arena.enabled)
                    Component.text(" [ENABLED]", NamedTextColor.GREEN)
                else
                    Component.text(" [DISABLED]", NamedTextColor.RED)
                comms.send(player, Component.text(" - ${arena.name}", NamedTextColor.YELLOW)
                    .append(Component.text(" (${arena.world}, ${arena.points.size} pts)", NamedTextColor.GRAY))
                    .append(status))
            }
        }

        private fun handleInfo(player: Player, args: Array<out String>) {
            if (args.size < 2) {
                comms.send(player, Component.text("Usage: /arena info <name>", NamedTextColor.RED))
                return
            }
            val arena = getArena(args[1])
            if (arena == null) {
                comms.send(player, Component.text("Arena not found.", NamedTextColor.RED))
                return
            }
            val status = if (arena.enabled) "Enabled" else "Disabled"
            val playersInside = playersInArena.count { it.value == arena.id }
            comms.send(player, Component.text("Arena: ${arena.name}", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            comms.send(player, Component.text("  World: ${arena.world}", NamedTextColor.GRAY))
            comms.send(player, Component.text("  Min Y: ${arena.minY}", NamedTextColor.GRAY))
            comms.send(player, Component.text("  Points: ${arena.points.size}", NamedTextColor.GRAY))
            comms.send(player, Component.text("  Status: $status", NamedTextColor.GRAY))
            comms.send(player, Component.text("  Players inside: $playersInside", NamedTextColor.GRAY))
        }

        private fun handleToggle(player: Player, args: Array<out String>, enable: Boolean) {
            if (args.size < 2) {
                comms.send(player, Component.text("Usage: /arena ${if (enable) "enable" else "disable"} <name>", NamedTextColor.RED))
                return
            }
            val name = args[1]
            if (setEnabled(name, enable)) {
                val state = if (enable) "enabled" else "disabled"
                comms.send(player, Component.text("Arena ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.GOLD))
                    .append(Component.text(" $state.", NamedTextColor.GREEN)))
            } else {
                comms.send(player, Component.text("Arena not found.", NamedTextColor.RED))
            }
        }

        private fun handlePoints(player: Player) {
            val selection = arenaSelections[player.uniqueId]
            if (selection == null || selection.isEmpty()) {
                comms.send(player, Component.text("No points selected. Use the arena wand.", NamedTextColor.GRAY))
                return
            }
            comms.send(player, Component.text("Current selection: ${selection.size} point(s)", NamedTextColor.YELLOW))
            for ((i, pt) in selection.withIndex()) {
                comms.send(player, Component.text("  #${i + 1}: (${pt.first}, ${pt.second})", NamedTextColor.GRAY))
            }
        }

        private fun handleUndo(player: Player) {
            val selection = arenaSelections[player.uniqueId]
            if (selection == null || selection.isEmpty()) {
                comms.send(player, Component.text("No points to undo.", NamedTextColor.RED))
                return
            }
            val removed = selection.removeAt(selection.size - 1)
            comms.send(player, Component.text("Removed point (${removed.first}, ${removed.second}). Remaining: ${selection.size}", NamedTextColor.YELLOW))
        }

        private fun handleClear(player: Player) {
            arenaSelections.remove(player.uniqueId)
            comms.send(player, Component.text("Selection cleared.", NamedTextColor.YELLOW))
        }

        private fun handleTeleport(player: Player, args: Array<out String>) {
            if (args.size < 2) {
                comms.send(player, Component.text("Usage: /arena tp <name>", NamedTextColor.RED))
                return
            }
            val arena = getArena(args[1])
            if (arena == null) {
                comms.send(player, Component.text("Arena not found.", NamedTextColor.RED))
                return
            }
            val world = Bukkit.getWorld(arena.world)
            if (world == null) {
                comms.send(player, Component.text("World not loaded.", NamedTextColor.RED))
                return
            }

            // Calculate polygon center
            val cx = arena.points.sumOf { it.first }.toDouble() / arena.points.size + 0.5
            val cz = arena.points.sumOf { it.second }.toDouble() / arena.points.size + 0.5
            val loc = Location(world, cx, arena.minY.toDouble() + 1.0, cz)
            loc.y = world.getHighestBlockYAt(loc.blockX, loc.blockZ).toDouble() + 1.0

            player.teleport(loc)
            comms.send(player, Component.text("Teleported to arena ", NamedTextColor.GREEN)
                .append(Component.text(arena.name, NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN)))
        }

        override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
            if (!sender.hasPermission("joshymc.arena")) return emptyList()

            return when (args.size) {
                1 -> {
                    val subs = listOf("wand", "create", "delete", "list", "info", "enable", "disable", "points", "undo", "clear", "tp")
                    subs.filter { it.startsWith(args[0].lowercase()) }
                }
                2 -> {
                    when (args[0].lowercase()) {
                        "delete", "info", "enable", "disable", "tp" -> {
                            arenas.map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
                        }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        }
    }
}
