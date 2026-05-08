package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Animals
import org.bukkit.entity.EnderDragon
import org.bukkit.entity.Item
import org.bukkit.entity.Monster
import org.bukkit.entity.NPC
import org.bukkit.entity.Villager
import org.bukkit.entity.Wither
import org.bukkit.entity.ArmorStand

class LagCleanerManager(private val plugin: Joshymc) {

    private var checkTaskId: Int = -1
    private var isClearingInProgress = false

    private var entityThreshold: Int = 500
    private var itemThreshold: Int = 200
    private var checkIntervalSeconds: Int = 30

    fun start() {
        val enabled = plugin.config.getBoolean("lag-cleaner.enabled", true)
        if (!enabled) return

        entityThreshold = plugin.config.getInt("lag-cleaner.entity-threshold", 1500)
        itemThreshold = plugin.config.getInt("lag-cleaner.item-threshold", 800)
        checkIntervalSeconds = plugin.config.getInt("lag-cleaner.check-interval-seconds", 60)

        val checkTicks = checkIntervalSeconds * 20L

        // Periodically check entity counts
        checkTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (isClearingInProgress) return@Runnable
            checkAndClear()
        }, checkTicks, checkTicks)

        plugin.logger.info("[LagCleaner] Monitoring entities (threshold: $entityThreshold entities, $itemThreshold items, checking every ${checkIntervalSeconds}s).")
    }

    fun stop() {
        if (checkTaskId != -1) {
            plugin.server.scheduler.cancelTask(checkTaskId)
            checkTaskId = -1
        }
        isClearingInProgress = false
    }

    private fun checkAndClear() {
        var totalEntities = 0
        var totalItems = 0

        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                when (entity) {
                    is Item -> totalItems++
                    is Monster, is Animals -> totalEntities++
                }
            }
        }

        val needsClear = totalEntities >= entityThreshold || totalItems >= itemThreshold

        if (!needsClear) return

        isClearingInProgress = true

        val reason = when {
            totalEntities >= entityThreshold && totalItems >= itemThreshold ->
                "$totalEntities entities and $totalItems ground items detected"
            totalEntities >= entityThreshold ->
                "$totalEntities entities detected"
            else ->
                "$totalItems ground items detected"
        }

        // 30 second warning
        plugin.commsManager.broadcast(
            Component.text("\u26A0 ", TextColor.color(0xFFAA00))
                .append(Component.text("Lag clear scheduled ", NamedTextColor.YELLOW))
                .append(Component.text("— $reason", NamedTextColor.GRAY))
                .decoration(TextDecoration.ITALIC, false),
            CommunicationsManager.Category.ADMIN
        )
        plugin.commsManager.broadcast(
            Component.text("  Clearing in ", NamedTextColor.YELLOW)
                .append(Component.text("30 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.ADMIN
        )

        // 10 second warning
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            plugin.commsManager.broadcast(
                Component.text("\u26A0 ", TextColor.color(0xFF5500))
                    .append(Component.text("Clearing in ", NamedTextColor.YELLOW))
                    .append(Component.text("10 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }, 20L * 20) // 20 seconds after first = 10 seconds remaining

        // 3 second final warning
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            plugin.commsManager.broadcast(
                Component.text("\u26A0 ", NamedTextColor.RED)
                    .append(Component.text("Clearing in ", NamedTextColor.RED))
                    .append(Component.text("3 seconds!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }, 27L * 20) // 27 seconds after first = 3 seconds remaining

        // Execute clear
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            executeClear()
            isClearingInProgress = false
        }, 30L * 20)
    }

    private fun executeClear() {
        var itemCount = 0
        var mobCount = 0

        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                when {
                    // Clear ground items
                    entity is Item -> {
                        entity.remove()
                        itemCount++
                    }
                    // Clear hostile + passive mobs (except protected ones)
                    (entity is Monster || entity is Animals) && !isProtected(entity) -> {
                        entity.remove()
                        mobCount++
                    }
                }
            }
        }

        plugin.commsManager.broadcast(
            Component.text("\u2714 ", NamedTextColor.GREEN)
                .append(Component.text("Cleared ", NamedTextColor.GREEN))
                .append(Component.text("$itemCount items", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" and ", NamedTextColor.GREEN))
                .append(Component.text("$mobCount mobs", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.ADMIN
        )
    }

    /**
     * Manually trigger a ground item clear with a 10-second countdown.
     */
    fun triggerManualClear() {
        if (isClearingInProgress) return
        isClearingInProgress = true

        plugin.commsManager.broadcast(
            Component.text("\u26A0 ", TextColor.color(0xFFAA00))
                .append(Component.text("Manual lag clear triggered", NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false),
            CommunicationsManager.Category.ADMIN
        )
        plugin.commsManager.broadcast(
            Component.text("  Ground items clearing in ", NamedTextColor.YELLOW)
                .append(Component.text("10 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.ADMIN
        )

        // 5 second warning
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            plugin.commsManager.broadcast(
                Component.text("\u26A0 ", TextColor.color(0xFF5500))
                    .append(Component.text("Clearing in ", NamedTextColor.YELLOW))
                    .append(Component.text("5 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }, 5L * 20) // 5 seconds

        // 3 second final warning
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            plugin.commsManager.broadcast(
                Component.text("\u26A0 ", NamedTextColor.RED)
                    .append(Component.text("Clearing in ", NamedTextColor.RED))
                    .append(Component.text("3 seconds!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }, 7L * 20) // 7 seconds

        // Execute clear at 10 seconds
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, {
            var count = 0
            for (world in plugin.server.worlds) {
                for (entity in world.entities) {
                    if (entity is Item) {
                        entity.remove()
                        count++
                    }
                }
            }
            plugin.commsManager.broadcast(
                Component.text("\u2714 ", NamedTextColor.GREEN)
                    .append(Component.text("Cleared ", NamedTextColor.GREEN))
                    .append(Component.text("$count ground items", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
            isClearingInProgress = false
        }, 10L * 20) // 10 seconds
    }

    /**
     * Per Joshy's request: keep nametagged, tamed, and villagers.
     * Plus an unavoidable safety set: bosses, plugin NPCs/entities, leashed,
     * and ArmorStands (not really mobs).
     */
    private fun isProtected(entity: org.bukkit.entity.Entity): Boolean {
        if (entity.customName() != null) return true
        if (entity.scoreboardTags.any { it.startsWith("joshymc") }) return true

        return when (entity) {
            is Villager -> true
            is NPC -> true
            is EnderDragon -> true
            is Wither -> true
            is ArmorStand -> true
            is org.bukkit.entity.Tameable -> (entity as org.bukkit.entity.Tameable).isTamed
            else -> entity is org.bukkit.entity.LivingEntity && entity.isLeashed
        }
    }

}
