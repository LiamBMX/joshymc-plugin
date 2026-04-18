package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.EventPriority
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

class PortalManager(private val plugin: Joshymc) : Listener {

    data class Portal(
        val id: Int,
        val name: String,
        val world: String,
        val x1: Int, val y1: Int, val z1: Int,
        val x2: Int, val y2: Int, val z2: Int,
        val action: String,
        val actionData: String,
        val cooldownSeconds: Int,
        val message: String?
    )

    private val wandKey = NamespacedKey(plugin, "portal_wand")
    private val portalSelections = mutableMapOf<UUID, Pair<Location?, Location?>>()
    private val lastPortalUse = mutableMapOf<UUID, MutableMap<Int, Long>>()
    private val playersInPortal = mutableMapOf<UUID, MutableSet<Int>>()

    private var portals = mutableListOf<Portal>()
    private var detectionTask: BukkitTask? = null

    // ── Lifecycle ──────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS portals (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT UNIQUE NOT NULL,
                world TEXT NOT NULL,
                x1 INTEGER NOT NULL, y1 INTEGER NOT NULL, z1 INTEGER NOT NULL,
                x2 INTEGER NOT NULL, y2 INTEGER NOT NULL, z2 INTEGER NOT NULL,
                action TEXT NOT NULL,
                action_data TEXT NOT NULL,
                cooldown_seconds INTEGER DEFAULT 0,
                message TEXT
            )
        """.trimIndent())

        loadPortals()
        startDetectionTask()
        plugin.logger.info("[PortalManager] Loaded ${portals.size} portals.")
    }

    fun stop() {
        detectionTask?.cancel()
        detectionTask = null
    }

    // ── Portal CRUD ────────────────────────────────────────

    fun createPortal(name: String, player: Player): Boolean {
        val selection = portalSelections[player.uniqueId]
        if (selection == null || selection.first == null || selection.second == null) return false

        val pos1 = selection.first!!
        val pos2 = selection.second!!

        if (pos1.world != pos2.world) return false

        val world = pos1.world.name
        val minX = min(pos1.blockX, pos2.blockX)
        val minY = min(pos1.blockY, pos2.blockY)
        val minZ = min(pos1.blockZ, pos2.blockZ)
        val maxX = max(pos1.blockX, pos2.blockX)
        val maxY = max(pos1.blockY, pos2.blockY)
        val maxZ = max(pos1.blockZ, pos2.blockZ)

        plugin.databaseManager.execute(
            "INSERT INTO portals (name, world, x1, y1, z1, x2, y2, z2, action, action_data, cooldown_seconds) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            name, world, minX, minY, minZ, maxX, maxY, maxZ, "command", "", 0
        )

        loadPortals()
        return true
    }

    fun deletePortal(name: String): Boolean {
        val portal = getPortal(name) ?: return false
        plugin.databaseManager.execute("DELETE FROM portals WHERE name = ?", name)
        playersInPortal.values.forEach { it.remove(portal.id) }
        lastPortalUse.values.forEach { it.remove(portal.id) }
        loadPortals()
        return true
    }

    fun getPortal(name: String): Portal? {
        return portals.find { it.name.equals(name, ignoreCase = true) }
    }

    fun getAllPortals(): List<Portal> = portals.toList()

    fun setAction(name: String, action: String, actionData: String) {
        plugin.databaseManager.execute(
            "UPDATE portals SET action = ?, action_data = ? WHERE name = ?",
            action, actionData, name
        )
        loadPortals()
    }

    fun setCooldown(name: String, seconds: Int) {
        plugin.databaseManager.execute(
            "UPDATE portals SET cooldown_seconds = ? WHERE name = ?",
            seconds, name
        )
        loadPortals()
    }

    fun setMessage(name: String, message: String?) {
        plugin.databaseManager.execute(
            "UPDATE portals SET message = ? WHERE name = ?",
            message, name
        )
        loadPortals()
    }

    // ── Wand ───────────────────────────────────────────────

    fun giveWand(player: Player) {
        val wand = ItemStack(Material.BLAZE_ROD)
        wand.editMeta { meta ->
            meta.displayName(
                Component.text("Portal Wand", NamedTextColor.GOLD)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.text("Left-click: Set position 1", NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("Right-click: Set position 2", NamedTextColor.GRAY)
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)
            ))
            meta.persistentDataContainer.set(wandKey, PersistentDataType.BYTE, 1.toByte())
        }
        player.inventory.addItem(wand)
    }

    fun getSelection(player: Player): Pair<Location?, Location?> {
        return portalSelections[player.uniqueId] ?: Pair(null, null)
    }

    @EventHandler
    fun onWandInteract(event: PlayerInteractEvent) {
        val item = event.item ?: return
        if (item.itemMeta?.persistentDataContainer?.has(wandKey, PersistentDataType.BYTE) != true) return
        if (!event.player.hasPermission("joshymc.portal")) return

        val block = event.clickedBlock ?: return
        event.isCancelled = true

        val player = event.player
        val uuid = player.uniqueId
        val current = portalSelections.getOrPut(uuid) { Pair(null, null) }

        when (event.action) {
            Action.LEFT_CLICK_BLOCK -> {
                portalSelections[uuid] = Pair(block.location, current.second)
                plugin.commsManager.send(player,
                    Component.text("Position 1 set: ", NamedTextColor.GREEN)
                        .append(Component.text("${block.x}, ${block.y}, ${block.z}", NamedTextColor.WHITE))
                )
            }
            Action.RIGHT_CLICK_BLOCK -> {
                portalSelections[uuid] = Pair(current.first, block.location)
                plugin.commsManager.send(player,
                    Component.text("Position 2 set: ", NamedTextColor.GREEN)
                        .append(Component.text("${block.x}, ${block.y}, ${block.z}", NamedTextColor.WHITE))
                )
            }
            else -> {}
        }
    }

    // ── Detection ──────────────────────────────────────────

    private fun startDetectionTask() {
        detectionTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                checkPlayer(player)
            }
        }, 10L, 10L)
    }

    private fun checkPlayer(player: Player) {
        val uuid = player.uniqueId
        val currentlyInside = mutableSetOf<Int>()
        val previouslyInside = playersInPortal.getOrPut(uuid) { mutableSetOf() }

        for (portal in portals) {
            if (isInsidePortal(player, portal)) {
                currentlyInside.add(portal.id)

                if (portal.id !in previouslyInside) {
                    triggerPortal(player, portal)
                }
            }
        }

        playersInPortal[uuid] = currentlyInside
    }

    private fun isInsidePortal(player: Player, portal: Portal): Boolean {
        val loc = player.location
        if (loc.world.name != portal.world) return false

        val px = loc.blockX
        val py = loc.blockY
        val pz = loc.blockZ

        return px in portal.x1..portal.x2
                && py in portal.y1..portal.y2
                && pz in portal.z1..portal.z2
    }

    private fun triggerPortal(player: Player, portal: Portal) {
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()

        // Cooldown check
        if (portal.cooldownSeconds > 0) {
            val cooldowns = lastPortalUse.getOrPut(uuid) { mutableMapOf() }
            val lastUse = cooldowns[portal.id] ?: 0L
            if (now - lastUse < portal.cooldownSeconds * 1000L) return
            cooldowns[portal.id] = now
        }

        // Show message
        if (!portal.message.isNullOrBlank()) {
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(portal.message))
        }

        // Particle animation then execute action
        playPortalAnimation(player) {
            executeAction(player, portal)
        }
    }

    private fun playPortalAnimation(player: Player, onComplete: () -> Unit) {
        var ticks = 0
        val task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (ticks >= 20 || !player.isOnline) {
                Bukkit.getScheduler().cancelTask(-1) // placeholder, handled below
                return@Runnable
            }
            val loc = player.location.add(0.0, 1.0, 0.0)
            player.world.spawnParticle(Particle.PORTAL, loc, 15, 0.5, 0.5, 0.5, 0.3)
            ticks++
        }, 0L, 1L)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            task.cancel()
            if (player.isOnline) onComplete()
        }, 20L)
    }

    // Cancel vanilla portal teleportation when player is inside a custom portal
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPortalTeleport(event: PlayerPortalEvent) {
        val player = event.player
        for (portal in portals) {
            if (isInsidePortal(player, portal)) {
                event.isCancelled = true
                return
            }
        }
    }

    private fun executeAction(player: Player, portal: Portal) {
        when (portal.action.lowercase()) {
            "command" -> {
                if (portal.actionData.isBlank()) return
                val cmd = portal.actionData.replace("{player}", player.name)
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
            }
            "tp" -> {
                val parts = portal.actionData.split(",")
                if (parts.size < 4) return
                val world = Bukkit.getWorld(parts[0].trim()) ?: return
                val x = parts[1].trim().toDoubleOrNull() ?: return
                val y = parts[2].trim().toDoubleOrNull() ?: return
                val z = parts[3].trim().toDoubleOrNull() ?: return
                player.teleport(Location(world, x, y, z, player.location.yaw, player.location.pitch))
            }
            "rtp" -> {
                // Optional action data: world name to RTP in; blank = main overworld
                val worldName = portal.actionData.trim()
                val world = if (worldName.isEmpty()) null else Bukkit.getWorld(worldName)
                plugin.rtpCommand.startForPlayer(player, world)
            }
            "world" -> {
                val world = Bukkit.getWorld(portal.actionData.trim()) ?: return
                player.teleport(world.spawnLocation)
            }
            "warp" -> {
                val warpLoc = plugin.warpManager.getWarp(portal.actionData.trim()) ?: return
                player.teleport(warpLoc)
            }
        }
    }

    // ── Internal ───────────────────────────────────────────

    private fun loadPortals() {
        portals = plugin.databaseManager.query(
            "SELECT * FROM portals ORDER BY name"
        ) { rs ->
            Portal(
                id = rs.getInt("id"),
                name = rs.getString("name"),
                world = rs.getString("world"),
                x1 = rs.getInt("x1"), y1 = rs.getInt("y1"), z1 = rs.getInt("z1"),
                x2 = rs.getInt("x2"), y2 = rs.getInt("y2"), z2 = rs.getInt("z2"),
                action = rs.getString("action"),
                actionData = rs.getString("action_data"),
                cooldownSeconds = rs.getInt("cooldown_seconds"),
                message = rs.getString("message")
            )
        }.toMutableList()
    }
}
