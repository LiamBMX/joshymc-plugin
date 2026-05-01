package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Hopper
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask

class HopperPlusManager(private val plugin: Joshymc) : Listener {

    data class HopperData(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        var speed: Int,
        var filterItem: String?
    )

    /** In-memory cache keyed by "world:x:y:z" */
    private val cache = mutableMapOf<String, HopperData>()

    private var tickTask: BukkitTask? = null

    /** Tick counters per hopper for speed scheduling */
    private val tickCounters = mutableMapOf<String, Int>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS upgraded_hoppers (
                world TEXT NOT NULL,
                x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                speed INTEGER NOT NULL DEFAULT 1,
                filter_item TEXT,
                PRIMARY KEY (world, x, y, z)
            )
        """.trimIndent())

        loadAll()
        startTickTask()

        plugin.logger.info("[HopperPlus] Manager started with ${cache.size} upgraded hoppers.")
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        tickCounters.clear()
        cache.clear()
    }

    // ── Database ────────────────────────────────────────────

    private fun loadAll() {
        cache.clear()
        val hoppers = plugin.databaseManager.query(
            "SELECT world, x, y, z, speed, filter_item FROM upgraded_hoppers"
        ) { rs ->
            HopperData(
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getInt("speed"),
                rs.getString("filter_item")
            )
        }
        for (data in hoppers) {
            cache[key(data.world, data.x, data.y, data.z)] = data
        }
    }

    private fun save(data: HopperData) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO upgraded_hoppers (world, x, y, z, speed, filter_item) VALUES (?, ?, ?, ?, ?, ?)",
            data.world, data.x, data.y, data.z, data.speed, data.filterItem
        )
    }

    private fun delete(world: String, x: Int, y: Int, z: Int) {
        val k = key(world, x, y, z)
        cache.remove(k)
        tickCounters.remove(k)
        plugin.databaseManager.execute(
            "DELETE FROM upgraded_hoppers WHERE world = ? AND x = ? AND y = ? AND z = ?",
            world, x, y, z
        )
    }

    fun getHopperData(world: String, x: Int, y: Int, z: Int): HopperData? {
        return cache[key(world, x, y, z)]
    }

    fun resetHopper(world: String, x: Int, y: Int, z: Int) {
        delete(world, x, y, z)
    }

    /**
     * Set or clear a hopper's filter. Pass null to clear. Used by
     * `/hopper filter <material>` since shift+right-click with an item in
     * hand now leaves vanilla block-placement intact.
     */
    fun setFilter(world: String, x: Int, y: Int, z: Int, material: Material?) {
        val k = key(world, x, y, z)
        val data = cache[k] ?: HopperData(world, x, y, z, 1, null)
        data.filterItem = material?.name
        cache[k] = data
        save(data)
    }

    // ── Key helper ──────────────────────────────────────────

    private fun key(world: String, x: Int, y: Int, z: Int) = "$world:$x:$y:$z"

    private fun key(loc: Location) = key(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)

    // ── Tick task for upgraded hoppers ───────────────────────

    private fun startTickTask() {
        tickTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for ((k, data) in cache) {
                if (data.speed <= 1) continue // vanilla speed, let Minecraft handle it

                val world = plugin.server.getWorld(data.world) ?: continue
                val block = world.getBlockAt(data.x, data.y, data.z)
                if (block.type != Material.HOPPER) {
                    // Hopper was removed without a break event — clean up
                    continue
                }

                val hopperState = block.state as? Hopper ?: continue

                // Tick counter logic
                val counter = (tickCounters[k] ?: 0) + 1
                tickCounters[k] = counter

                val interval = when (data.speed) {
                    2 -> 4
                    3 -> 2
                    4 -> 1
                    5 -> 1  // every tick, but transfers full stack
                    else -> 8
                }

                if (counter % interval != 0) continue

                // Transfer items
                transferItems(hopperState, data)

                // Particle effect (subtle, every 20 ticks)
                if (counter % 20 == 0) {
                    world.spawnParticle(
                        Particle.HAPPY_VILLAGER,
                        data.x + 0.5, data.y + 1.0, data.z + 0.5,
                        1, 0.2, 0.1, 0.2, 0.0
                    )
                }
            }
        }, 1L, 1L)
    }

    private fun transferItems(hopper: Hopper, data: HopperData) {
        val hopperInv = hopper.inventory
        val destination = getDestinationInventory(hopper) ?: return

        val maxTransfer = if (data.speed == 5) 64 else 1

        for (slot in 0 until hopperInv.size) {
            val item = hopperInv.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue

            // Filter check
            if (data.filterItem != null) {
                val filterMat = try { Material.valueOf(data.filterItem!!) } catch (_: Exception) { null }
                if (filterMat != null && item.type != filterMat) continue
            }

            val transferAmount = minOf(maxTransfer, item.amount)
            val toTransfer = item.clone().apply { amount = transferAmount }

            val leftover = destination.addItem(toTransfer)
            val transferred = transferAmount - (leftover.values.sumOf { it.amount })

            if (transferred > 0) {
                item.amount -= transferred
                if (item.amount <= 0) {
                    hopperInv.setItem(slot, null)
                }
                break // one transfer per tick (unless level 5 which does a full stack)
            }
        }
    }

    private fun getDestinationInventory(hopper: Hopper): Inventory? {
        val block = hopper.block
        val facing = (block.blockData as org.bukkit.block.data.type.Hopper).facing
        val targetBlock = block.getRelative(facing)
        val targetState = targetBlock.state

        return if (targetState is org.bukkit.block.Container) {
            targetState.inventory
        } else {
            null
        }
    }

    // ── Event: Shift + Right-click to upgrade / set filter ──

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (!event.action.isRightClick) return
        if (!event.player.isSneaking) return

        val block = event.clickedBlock ?: return
        if (block.type != Material.HOPPER) return

        val player = event.player
        val loc = block.location
        val k = key(loc)
        val item = event.item

        // Only intercept gestures that are unambiguously hopper management:
        //   - DIAMOND in hand   → upgrade
        //   - empty hand        → info / clear filter
        // Anything else (including blocks) falls through to vanilla so the
        // player can stack-place a block on top of the hopper.
        val isUpgrade = item != null && item.type == Material.DIAMOND
        val isInfo = item == null || item.type == Material.AIR
        if (!isUpgrade && !isInfo) return

        event.isCancelled = true

        if (item != null && item.type == Material.DIAMOND) {
            // Upgrade speed
            if (!player.hasPermission("joshymc.hopper.upgrade")) {
                plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return
            }

            val data = cache[k] ?: HopperData(loc.world.name, loc.blockX, loc.blockY, loc.blockZ, 1, null)
            if (data.speed >= 5) {
                plugin.commsManager.send(player, Component.text("This hopper is already max level (5).", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return
            }

            data.speed++
            cache[k] = data
            save(data)

            // Consume one diamond
            item.amount--

            val speedLabel = when (data.speed) {
                2 -> "2x"
                3 -> "4x"
                4 -> "8x"
                5 -> "Full Stack"
                else -> "1x"
            }

            plugin.commsManager.send(
                player,
                Component.text("Hopper upgraded to level ${data.speed} ", NamedTextColor.GREEN)
                    .append(Component.text("($speedLabel speed)", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )
            player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.7f, 1.2f)
            loc.world.spawnParticle(Particle.HAPPY_VILLAGER, loc.x + 0.5, loc.y + 1.0, loc.z + 0.5, 10, 0.3, 0.3, 0.3, 0.0)
            return
        }

        // Empty hand — clear filter
        if (item == null || item.type == Material.AIR) {
            val data = cache[k]
            if (data == null) {
                plugin.commsManager.send(player, Component.text("This is a normal hopper.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
                return
            }

            if (data.filterItem != null) {
                data.filterItem = null
                save(data)
                plugin.commsManager.send(player, Component.text("Hopper filter cleared.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 0.8f)
            } else {
                val speedLabel = when (data.speed) {
                    2 -> "2x"
                    3 -> "4x"
                    4 -> "8x"
                    5 -> "Full Stack"
                    else -> "Vanilla"
                }
                plugin.commsManager.send(
                    player,
                    Component.text("Hopper Level ${data.speed} ", NamedTextColor.AQUA)
                        .append(Component.text("($speedLabel speed)", NamedTextColor.GRAY))
                        .append(Component.text(" | Filter: ", NamedTextColor.AQUA))
                        .append(Component.text("None", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
            return
        }
    }

    // ── Event: Filter non-matching items on vanilla transfers ──

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        val destHolder = event.destination.holder
        if (destHolder is Hopper) {
            val loc = destHolder.block.location
            val data = cache[key(loc)] ?: return

            // Block filtered items
            if (data.filterItem != null) {
                val filterMat = try { Material.valueOf(data.filterItem!!) } catch (_: Exception) { null }
                if (filterMat != null && event.item.type != filterMat) {
                    event.isCancelled = true
                    return
                }
            }

            // Cancel vanilla transfer for speed-upgraded hoppers (our tick task handles it)
            if (data.speed > 1) {
                event.isCancelled = true
            }
        }

        // Also apply filter when upgraded hopper is the source
        val srcHolder = event.source.holder
        if (srcHolder is Hopper) {
            val loc = srcHolder.block.location
            val data = cache[key(loc)] ?: return

            if (data.filterItem != null) {
                val filterMat = try { Material.valueOf(data.filterItem!!) } catch (_: Exception) { null }
                if (filterMat != null && event.item.type != filterMat) {
                    event.isCancelled = true
                    return
                }
            }

            if (data.speed > 1) {
                event.isCancelled = true
            }
        }
    }

    // ── Event: Clean up when hopper is broken ───────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.HOPPER) return

        val loc = event.block.location
        val k = key(loc)
        val data = cache[k] ?: return

        delete(loc.world.name, loc.blockX, loc.blockY, loc.blockZ)

        // Refund diamonds based on level (level - 1 diamonds spent)
        val refund = data.speed - 1
        if (refund > 0) {
            event.block.world.dropItemNaturally(loc, ItemStack(Material.DIAMOND, refund))
        }

        plugin.commsManager.send(
            event.player,
            Component.text("Upgraded hopper broken. $refund diamond(s) refunded.", NamedTextColor.YELLOW),
            CommunicationsManager.Category.DEFAULT
        )
    }
}
