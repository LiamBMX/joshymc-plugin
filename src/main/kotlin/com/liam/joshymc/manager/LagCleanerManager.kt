package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Boss
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Mob
import org.bukkit.entity.Monster
import org.bukkit.entity.Villager

class LagCleanerManager(private val plugin: Joshymc) {

    private var checkTaskId: Int = -1
    private var isClearingInProgress = false

    private var entityThreshold: Int = 500
    private var itemThreshold: Int = 200
    private var checkIntervalSeconds: Int = 30
    private var passiveMobThreshold: Int = 700

    fun start() {
        val enabled = plugin.config.getBoolean("lag-cleaner.enabled", true)
        if (!enabled) return

        entityThreshold = plugin.config.getInt("lag-cleaner.entity-threshold", 1500)
        itemThreshold = plugin.config.getInt("lag-cleaner.item-threshold", 800)
        checkIntervalSeconds = plugin.config.getInt("lag-cleaner.check-interval-seconds", 60)
        passiveMobThreshold = plugin.config.getInt("lag-cleaner.passive-mob-threshold", 700)

        val checkTicks = checkIntervalSeconds * 20L

        // Periodically check entity counts
        checkTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (isClearingInProgress) return@Runnable
            checkAndClear()
        }, checkTicks, checkTicks)

        plugin.logger.info("[LagCleaner] Monitoring entities (thresholds: $entityThreshold hostile mobs, $passiveMobThreshold passive mobs, $itemThreshold items, checking every ${checkIntervalSeconds}s).")
    }

    fun stop() {
        if (checkTaskId != -1) {
            plugin.server.scheduler.cancelTask(checkTaskId)
            checkTaskId = -1
        }
        isClearingInProgress = false
    }

    private fun checkAndClear() {
        var hostileTotal = 0
        var passiveTotal = 0
        var itemTotal = 0

        for (world in plugin.server.worlds) {
            itemTotal += world.getEntitiesByClass(Item::class.java).size
        }
        for (world in lagClearWorlds()) {
            for (entity in world.entities) {
                if (!isEligibleMobForLagClear(entity)) continue
                if (entity is Monster) hostileTotal++ else passiveTotal++
            }
        }

        val hostileExceeded = hostileTotal >= entityThreshold
        val passiveExceeded = passiveTotal >= passiveMobThreshold
        val itemExceeded = itemTotal >= itemThreshold

        if (!hostileExceeded && !passiveExceeded && !itemExceeded) return

        isClearingInProgress = true

        val reasonParts = mutableListOf<String>()
        if (hostileExceeded) reasonParts.add("$hostileTotal hostile mobs")
        if (passiveExceeded) reasonParts.add("$passiveTotal passive mobs")
        if (itemExceeded) reasonParts.add("$itemTotal dropped items")
        val reason = "${reasonParts.joinToString(" and ")} detected"

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
        val (itemCount, mobCount) = clearItemsAndMobs()

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
     * Removes ground items (every world, shulker boxes kept) and every eligible mob in
     * the Overworld, Nether and End. Returns how many items and mobs were actually
     * removed, not just how many were found.
     */
    private fun clearItemsAndMobs(): Pair<Int, Int> {
        var itemCount = 0
        var mobCount = 0

        for (world in plugin.server.worlds) {
            for (item in world.getEntitiesByClass(Item::class.java)) {
                if (isShulkerBox(item)) continue
                item.remove()
                if (!item.isValid) itemCount++
            }
        }

        for (world in lagClearWorlds()) {
            for (entity in world.entities) {
                if (!isEligibleMobForLagClear(entity)) continue
                entity.remove()
                if (!entity.isValid) mobCount++
            }
        }

        return itemCount to mobCount
    }

    /**
     * Manually trigger a ground item + mob clear with a 10-second countdown.
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
            Component.text("  Items and mobs clearing in ", NamedTextColor.YELLOW)
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
            val (itemCount, mobCount) = clearItemsAndMobs()
            plugin.commsManager.broadcast(
                Component.text("\u2714 ", NamedTextColor.GREEN)
                    .append(Component.text("Cleared ", NamedTextColor.GREEN))
                    .append(Component.text("$itemCount items", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                    .append(Component.text(" and ", NamedTextColor.GREEN))
                    .append(Component.text("$mobCount mobs", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
            isClearingInProgress = false
        }, 10L * 20) // 10 seconds
    }

    private fun isShulkerBox(item: Item): Boolean =
        item.itemStack.type.name.endsWith("SHULKER_BOX")

    /**
     * Only the server's own Overworld, Nether and End (the vanilla dimension keys).
     * PvP, event, staff, build, resource, spawn, AFK and every other custom world is
     * left alone.
     */
    private fun lagClearWorlds(): List<World> =
        plugin.server.worlds.filter { it.key in LAG_CLEAR_WORLD_KEYS }

    /**
     * Centralized eligibility check shared by the automatic threshold counters,
     * the automatic clear, and manual `/admin lagclear` so counting and removal
     * never disagree on what's actually eligible.
     *
     * Per Joshy (issue #1077): every mob goes (passive, hostile, neutral, ambient,
     * water, tamed, leashed) except villagers, name-tagged mobs and bosses. Players,
     * armor stands, item frames, projectiles, vehicles and other non-mob entities
     * are never [Mob]s.
     */
    private fun isEligibleMobForLagClear(entity: Entity): Boolean {
        if (entity !is Mob) return false
        if (entity is Villager) return false
        if (entity is Boss) return false
        if (isNameTagged(entity)) return false
        // Mobs JoshyMC spawns itself (combat-log NPCs, relic pets, NPCs, decorations)
        if (entity.scoreboardTags.any { it.startsWith("joshymc") }) return false
        return true
    }

    /** A custom name counts as a name tag unless it's only the mob-stacking "Cow x5" label. */
    private fun isNameTagged(entity: Mob): Boolean =
        entity.customName() != null && !plugin.mobStackManager.hasOnlyStackLabel(entity)

    companion object {
        private val LAG_CLEAR_WORLD_KEYS = setOf(
            NamespacedKey.minecraft("overworld"),
            NamespacedKey.minecraft("the_nether"),
            NamespacedKey.minecraft("the_end"),
        )
    }

}
