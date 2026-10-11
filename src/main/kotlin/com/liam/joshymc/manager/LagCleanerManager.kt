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
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Mob
import org.bukkit.entity.Tameable
import org.bukkit.entity.Warden

class LagCleanerManager(private val plugin: Joshymc) {

    private var checkTaskId: Int = -1
    private var isClearingInProgress = false

    /** Countdown warnings + the clear itself, so a reload/stop can cancel them and they never overlap. */
    private val pendingTaskIds = mutableListOf<Int>()

    /** When the last clear (automatic or manual) finished, in epoch ms. 0 = never. */
    private var lastClearFinishedAt: Long = 0L

    private var entityThreshold: Int = 500
    private var itemThreshold: Int = 200
    private var checkIntervalSeconds: Int = 30
    private var minimumTriggerIntervalSeconds: Int = 300

    fun start() {
        val enabled = plugin.config.getBoolean("lag-cleaner.enabled", true)
        if (!enabled) return

        entityThreshold = plugin.config.getInt("lag-cleaner.entity-threshold", 1500)
        itemThreshold = plugin.config.getInt("lag-cleaner.item-threshold", 800)
        checkIntervalSeconds = plugin.config.getInt("lag-cleaner.check-interval-seconds", 60)
        minimumTriggerIntervalSeconds = plugin.config.getInt("lag-cleaner.minimum-trigger-interval-seconds", 300)

        val checkTicks = checkIntervalSeconds * 20L

        // Periodically check entity counts
        checkTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (isClearingInProgress) return@Runnable
            if (isOnCooldown()) return@Runnable
            checkAndClear()
        }, checkTicks, checkTicks)

        plugin.logger.info("[LagCleaner] Monitoring entities (thresholds: $entityThreshold hostile mobs, $itemThreshold items, checking every ${checkIntervalSeconds}s, at most one clear every ${minimumTriggerIntervalSeconds}s).")
    }

    fun stop() {
        if (checkTaskId != -1) {
            plugin.server.scheduler.cancelTask(checkTaskId)
            checkTaskId = -1
        }
        cancelPendingTasks()
        isClearingInProgress = false
    }

    private fun isOnCooldown(): Boolean =
        System.currentTimeMillis() - lastClearFinishedAt < minimumTriggerIntervalSeconds * 1000L

    private fun schedule(delayTicks: Long, task: () -> Unit) {
        val id = plugin.server.scheduler.scheduleSyncDelayedTask(plugin, task, delayTicks)
        if (id != -1) pendingTaskIds.add(id)
    }

    private fun cancelPendingTasks() {
        for (id in pendingTaskIds) plugin.server.scheduler.cancelTask(id)
        pendingTaskIds.clear()
    }

    /** Ends a clear: drops the countdown task ids, starts the cooldown and lets the next check run. */
    private fun finishClear() {
        pendingTaskIds.clear()
        lastClearFinishedAt = System.currentTimeMillis()
        isClearingInProgress = false
    }

    private fun checkAndClear() {
        // Fresh counts every check, using the same filter the clear itself uses
        val hostileTotal = lagClearWorlds().sumOf { world -> world.entities.count(::isEligibleForLagClear) }
        val itemTotal = plugin.server.worlds.sumOf { world -> world.getEntitiesByClass(Item::class.java).count { !isShulkerBox(it) } }

        val hostileExceeded = hostileTotal >= entityThreshold
        val itemExceeded = itemTotal >= itemThreshold

        if (!hostileExceeded && !itemExceeded) return

        isClearingInProgress = true

        val reasonParts = mutableListOf<String>()
        if (hostileExceeded) reasonParts.add("High hostile mob count detected: $hostileTotal")
        if (itemExceeded) reasonParts.add("High dropped item count detected: $itemTotal")
        val reason = reasonParts.joinToString(", ")

        // 30 second warning
        plugin.commsManager.broadcast(
            Component.text("⚠ ", TextColor.color(0xFFAA00))
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
        schedule(20L * 20) { // 20 seconds after first = 10 seconds remaining
            plugin.commsManager.broadcast(
                Component.text("⚠ ", TextColor.color(0xFF5500))
                    .append(Component.text("Clearing in ", NamedTextColor.YELLOW))
                    .append(Component.text("10 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }

        // 3 second final warning
        schedule(27L * 20) { // 27 seconds after first = 3 seconds remaining
            plugin.commsManager.broadcast(
                Component.text("⚠ ", NamedTextColor.RED)
                    .append(Component.text("Clearing in ", NamedTextColor.RED))
                    .append(Component.text("3 seconds!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }

        // Execute clear
        schedule(30L * 20) {
            try {
                executeClear()
            } finally {
                finishClear()
            }
        }
    }

    private fun executeClear() {
        val (itemCount, mobCount) = clearItemsAndMobs()
        broadcastCleared(itemCount, mobCount)
    }

    private fun broadcastCleared(itemCount: Int, mobCount: Int) {
        plugin.commsManager.broadcast(
            Component.text("✔ ", NamedTextColor.GREEN)
                .append(Component.text("Cleared ", NamedTextColor.GREEN))
                .append(Component.text("$itemCount items", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                .append(Component.text(" and ", NamedTextColor.GREEN))
                .append(Component.text("$mobCount hostile mobs", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.ADMIN
        )
    }

    /**
     * Removes ground items (every world, shulker boxes kept) and every eligible hostile
     * mob in the Overworld, Nether and End. Returns how many items and mobs were actually
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
                if (!isEligibleForLagClear(entity)) continue
                entity.remove()
                if (!entity.isValid) mobCount++
            }
        }

        return itemCount to mobCount
    }

    /**
     * Manually trigger a ground item + hostile mob clear with a 10-second countdown.
     * Ignores the automatic cooldown, but still starts it once the clear finishes.
     */
    fun triggerManualClear() {
        if (isClearingInProgress) return
        isClearingInProgress = true

        plugin.commsManager.broadcast(
            Component.text("⚠ ", TextColor.color(0xFFAA00))
                .append(Component.text("Manual lag clear triggered", NamedTextColor.YELLOW))
                .decoration(TextDecoration.ITALIC, false),
            CommunicationsManager.Category.ADMIN
        )
        plugin.commsManager.broadcast(
            Component.text("  Items and hostile mobs clearing in ", NamedTextColor.YELLOW)
                .append(Component.text("10 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
            CommunicationsManager.Category.ADMIN
        )

        // 5 second warning
        schedule(5L * 20) { // 5 seconds
            plugin.commsManager.broadcast(
                Component.text("⚠ ", TextColor.color(0xFF5500))
                    .append(Component.text("Clearing in ", NamedTextColor.YELLOW))
                    .append(Component.text("5 seconds", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }

        // 3 second final warning
        schedule(7L * 20) { // 7 seconds
            plugin.commsManager.broadcast(
                Component.text("⚠ ", NamedTextColor.RED)
                    .append(Component.text("Clearing in ", NamedTextColor.RED))
                    .append(Component.text("3 seconds!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.ADMIN
            )
        }

        // Execute clear at 10 seconds
        schedule(10L * 20) { // 10 seconds
            try {
                executeClear()
            } finally {
                finishClear()
            }
        }
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
     * The one eligibility check shared by the automatic threshold counter, the
     * automatic clear and manual `/admin lagclear`, so the "detected" count and the
     * "cleared" count are always the same class of mob.
     *
     * Per issue #1087: only hostile mobs ([Enemy]: zombies, skeletons, creepers,
     * spiders, drowned, husks, strays, bogged, witches, phantoms, blazes, ghasts,
     * slimes, magma cubes, silverfish, endermites, ...). Passive mobs, villagers,
     * golems (iron, snow, copper) and neutral mobs like bees, dolphins, foxes,
     * llamas, nautiluses, pandas, polar bears and wolves are never [Enemy]s. On
     * top of that, bosses (Ender Dragon, Wither, Warden), tamed/owned mobs,
     * name-tagged mobs, Citizens NPCs and JoshyMC's own mobs are kept.
     */
    private fun isEligibleForLagClear(entity: Entity): Boolean {
        if (entity !is Mob || entity !is Enemy) return false
        if (entity is Boss || entity is Warden) return false
        if (entity is Tameable && (entity.isTamed || entity.ownerUniqueId != null)) return false
        if (isNameTagged(entity)) return false
        // Citizens marks its NPC entities with "NPC" metadata
        if (entity.hasMetadata("NPC")) return false
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
