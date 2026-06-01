package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Chest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID

class SellChestListener(private val plugin: Joshymc) : Listener {

    // "world:x:y:z" -> owner UUID
    private val sellChests = mutableMapOf<String, UUID>()

    fun start() {
        plugin.databaseManager.createTable(
            "CREATE TABLE IF NOT EXISTS sell_chests " +
            "(world TEXT, x INT, y INT, z INT, owner TEXT, PRIMARY KEY (world, x, y, z))"
        )

        plugin.databaseManager.query(
            "SELECT world, x, y, z, owner FROM sell_chests"
        ) { rs ->
            val world = rs.getString("world")
            val x = rs.getInt("x")
            val y = rs.getInt("y")
            val z = rs.getInt("z")
            val owner = UUID.fromString(rs.getString("owner"))
            key(world, x, y, z) to owner
        }.forEach { (k, owner) -> sellChests[k] = owner }

        plugin.logger.info("[SellChest] Loaded ${sellChests.size} sell chest(s).")

        object : BukkitRunnable() {
            override fun run() = tickSell()
        }.runTaskTimer(plugin, 1200L, 1200L)
    }

    private fun key(world: String, x: Int, y: Int, z: Int) = "$world:$x:$y:$z"

    private fun tickSell() {
        val toRemove = mutableListOf<String>()

        for ((k, ownerUuid) in sellChests.toMap()) {
            val parts = k.split(":")
            if (parts.size != 4) { toRemove.add(k); continue }

            val world = plugin.server.getWorld(parts[0]) ?: continue
            val bx = parts[1].toInt()
            val by = parts[2].toInt()
            val bz = parts[3].toInt()

            if (!world.isChunkLoaded(bx shr 4, bz shr 4)) continue

            val block = world.getBlockAt(bx, by, bz)
            if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) {
                toRemove.add(k)
                plugin.databaseManager.execute(
                    "DELETE FROM sell_chests WHERE world=? AND x=? AND y=? AND z=?",
                    parts[0], bx, by, bz
                )
                continue
            }

            val chest = block.state as? Chest ?: continue
            val inventory = chest.inventory

            var totalEarned = 0.0
            val breakdown = mutableMapOf<Material, Int>()

            for (i in 0 until inventory.size) {
                val slot = inventory.getItem(i) ?: continue
                val basePrice = plugin.serverShopManager.getSellPrice(slot.type) ?: continue
                if (basePrice <= 0) continue

                val price = plugin.serverShopManager.applyCropBonus(basePrice, slot.type, ownerUuid)
                totalEarned += price * slot.amount
                breakdown[slot.type] = (breakdown[slot.type] ?: 0) + slot.amount
                inventory.setItem(i, null)
            }

            if (totalEarned <= 0) continue

            plugin.economyManager.deposit(ownerUuid, totalEarned)
            for ((material, amount) in breakdown) {
                plugin.marketManager.recordTransaction(material, "SELL", amount)
            }

            val owner = plugin.server.getPlayer(ownerUuid) ?: continue
            val formatted = plugin.economyManager.format(totalEarned)
            plugin.commsManager.send(
                owner,
                Component.text("Sell Chest sold items for ", NamedTextColor.GREEN)
                    .append(Component.text(formatted, NamedTextColor.GOLD)),
                CommunicationsManager.Category.ECONOMY
            )
            owner.playSound(owner.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
        }

        toRemove.forEach { sellChests.remove(it) }
    }

    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!plugin.itemManager.isCustomItem(event.itemInHand, "sell_chest")) return

        val loc = event.blockPlaced.location
        val worldName = loc.world?.name ?: return
        val ownerUuid = event.player.uniqueId
        val k = key(worldName, loc.blockX, loc.blockY, loc.blockZ)

        sellChests[k] = ownerUuid
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO sell_chests (world, x, y, z, owner) VALUES (?, ?, ?, ?, ?)",
            worldName, loc.blockX, loc.blockY, loc.blockZ, ownerUuid.toString()
        )

        plugin.commsManager.send(
            event.player,
            Component.text("Sell Chest placed! Items inside will auto-sell every 60 seconds.", NamedTextColor.GREEN),
            CommunicationsManager.Category.ECONOMY
        )
    }

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) return

        val loc = block.location
        val k = key(loc.world?.name ?: return, loc.blockX, loc.blockY, loc.blockZ)
        sellChests.remove(k) ?: return

        plugin.databaseManager.execute(
            "DELETE FROM sell_chests WHERE world=? AND x=? AND y=? AND z=?",
            loc.world?.name, loc.blockX, loc.blockY, loc.blockZ
        )

        // Suppress normal drops; return the sell_chest item + any inventory contents
        event.isDropItems = false
        val chest = block.state as? Chest
        chest?.inventory?.let { inv ->
            for (i in 0 until inv.size) {
                val item = inv.getItem(i) ?: continue
                block.world.dropItemNaturally(loc, item)
                inv.setItem(i, null)
            }
        }
        plugin.itemManager.getItem("sell_chest")?.let {
            block.world.dropItemNaturally(loc, it.createItemStack())
        }
    }

    @EventHandler
    fun onEntityExplode(event: EntityExplodeEvent) {
        cleanupExplosionBlocks(event.blockList())
    }

    @EventHandler
    fun onBlockExplode(event: BlockExplodeEvent) {
        cleanupExplosionBlocks(event.blockList())
    }

    private fun cleanupExplosionBlocks(blocks: MutableList<org.bukkit.block.Block>) {
        val iter = blocks.iterator()
        while (iter.hasNext()) {
            val block = iter.next()
            if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) continue

            val loc = block.location
            val k = key(loc.world?.name ?: continue, loc.blockX, loc.blockY, loc.blockZ)
            sellChests.remove(k) ?: continue

            plugin.databaseManager.execute(
                "DELETE FROM sell_chests WHERE world=? AND x=? AND y=? AND z=?",
                loc.world?.name, loc.blockX, loc.blockY, loc.blockZ
            )

            // Remove from explosion list and drop sell_chest item + contents instead
            iter.remove()
            val chest = block.state as? Chest
            chest?.inventory?.let { inv ->
                for (i in 0 until inv.size) {
                    val item = inv.getItem(i) ?: continue
                    block.world.dropItemNaturally(loc, item)
                    inv.setItem(i, null)
                }
            }
            plugin.itemManager.getItem("sell_chest")?.let {
                block.world.dropItemNaturally(loc, it.createItemStack())
            }
        }
    }
}
