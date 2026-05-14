package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

class BubbleButtListener(private val plugin: Joshymc) : Listener {

    private data class BubbleData(
        val center: Location,
        val radius: Double,
        val placedBlocks: List<Location>,
        var task: BukkitTask?,
    )

    private val activeBubbles = mutableMapOf<UUID, BubbleData>()
    private val cooldowns = mutableMapOf<UUID, Long>()

    private val RADIUS = 3.0
    private val DURATION_TICKS = 300L  // 15 seconds
    private val COOLDOWN_MS = 90_000L  // 90 seconds
    // Pop the bubble when the owner reaches this distance from center (approaching the glass wall)
    private val EXIT_THRESHOLD = RADIUS - 1.0

    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        val player = event.player
        if (!plugin.itemManager.isCustomItem(player.inventory.leggings, "bubble_butt_leggings")) return
        if (activeBubbles.containsKey(player.uniqueId)) return

        val now = System.currentTimeMillis()
        val lastUse = cooldowns[player.uniqueId] ?: 0L
        val elapsed = now - lastUse
        if (elapsed < COOLDOWN_MS) {
            val remaining = (COOLDOWN_MS - elapsed) / 1000.0
            plugin.commsManager.send(
                player,
                Component.text("Cooldown: ${"%.1f".format(remaining)}s", NamedTextColor.RED),
            )
            return
        }

        activateBubble(player)
    }

    private fun activateBubble(player: Player) {
        // Center one block above feet so the sphere wraps around the player's body
        val center = player.location.clone().add(0.0, 1.0, 0.0)
        val placed = mutableListOf<Location>()

        val r = RADIUS
        val rMin = r - 0.5
        val rMax = r + 0.5
        for (dx in -4..4) {
            for (dy in -4..4) {
                for (dz in -4..4) {
                    val dist = Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    if (dist < rMin || dist > rMax) continue
                    val loc = center.clone().add(dx.toDouble(), dy.toDouble(), dz.toDouble())
                    val block = loc.block
                    if (block.type == Material.AIR) {
                        block.type = Material.LIGHT_BLUE_STAINED_GLASS
                        placed.add(loc)
                    }
                }
            }
        }

        val task = plugin.server.scheduler.runTaskLater(plugin, Runnable {
            popBubble(player.uniqueId, plugin.server.getPlayer(player.uniqueId))
        }, DURATION_TICKS)

        activeBubbles[player.uniqueId] = BubbleData(center, r, placed, task)
        plugin.commsManager.send(
            player,
            Component.text("Bubble activated!", TextColor.color(0xADD8E6))
                .append(Component.text(" (15s)", NamedTextColor.GRAY)),
        )
    }

    private fun popBubble(uuid: UUID, player: Player? = null) {
        val bubble = activeBubbles.remove(uuid) ?: return
        bubble.task?.cancel()

        for (loc in bubble.placedBlocks) {
            if (loc.block.type == Material.LIGHT_BLUE_STAINED_GLASS) {
                loc.block.type = Material.AIR
            }
        }

        cooldowns[uuid] = System.currentTimeMillis()
        val p = player ?: plugin.server.getPlayer(uuid)
        p?.let {
            plugin.commsManager.send(it, Component.text("Bubble popped.", NamedTextColor.GRAY))
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerMove(event: PlayerMoveEvent) {
        if (!event.hasChangedPosition()) return
        val player = event.player
        val uuid = player.uniqueId

        // If this player has an active bubble, check if they're exiting
        val ownerBubble = activeBubbles[uuid]
        if (ownerBubble != null) {
            val dist = event.to.distance(ownerBubble.center)
            if (dist >= EXIT_THRESHOLD) {
                popBubble(uuid, player)
            }
            return
        }

        // Prevent other players from entering any active bubble
        for ((ownerId, bubble) in activeBubbles) {
            if (ownerId == uuid) continue
            if (event.to.world != bubble.center.world) continue
            val distTo = event.to.distance(bubble.center)
            val distFrom = event.from.distance(bubble.center)
            // Moving from outside into the bubble's interior
            if (distTo < bubble.radius && distFrom >= bubble.radius) {
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        popBubble(uuid, null)
        cooldowns.remove(uuid)
    }
}
