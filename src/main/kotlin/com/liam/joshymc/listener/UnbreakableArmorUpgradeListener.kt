package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory

/**
 * The Haunted Hollow armor and the Shark set became unbreakable in issue #1070, but
 * copies handed out before that are plain breakable Netherite. This flips the
 * unbreakable flag on those copies in place, matched by their `custom_item_id` PDC
 * tag only: nothing else on the item (name, model, equippable, enchants, trims,
 * PDC, damage already taken) is touched, and no item is created or removed.
 *
 * Covers the player's own inventory (armor and offhand included) and ender chest on
 * join, plus any container they open (chests, `/pv` vaults, ...). Copies sitting in a
 * container nobody opens, or inside a shulker box, upgrade once they reach one of those.
 */
class UnbreakableArmorUpgradeListener(private val plugin: Joshymc) : Listener {

    fun start() {
        // Covers a plugin reload/update that doesn't restart the server.
        for (player in Bukkit.getOnlinePlayers()) upgradePlayer(player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        upgradePlayer(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        upgrade(event.inventory)
    }

    private fun upgradePlayer(player: Player) {
        upgrade(player.inventory)
        upgrade(player.enderChest)
    }

    private fun upgrade(inventory: Inventory) {
        for (slot in 0 until inventory.size) {
            val item = inventory.getItem(slot) ?: continue
            if (item.type.isAir) continue
            val id = plugin.itemManager.getCustomItemId(item) ?: continue
            if (id !in UNBREAKABLE_IDS) continue
            val meta = item.itemMeta ?: continue
            if (meta.isUnbreakable) continue
            meta.isUnbreakable = true
            item.itemMeta = meta
            inventory.setItem(slot, item)
        }
    }

    companion object {
        val UNBREAKABLE_IDS = setOf(
            "haunted_hollow_chestplate",
            "haunted_hollow_leggings",
            "haunted_hollow_boots",
            "shark_helmet",
            "shark_chestplate",
            "shark_leggings",
            "shark_boots",
        )
    }
}
