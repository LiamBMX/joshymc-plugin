package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory

/**
 * Brings custom item copies handed out before a change up to date, in place:
 *
 *  - The Haunted Hollow armor and the Shark set became unbreakable in issue #1070, but
 *    copies handed out before that are plain breakable Netherite: the unbreakable flag is
 *    flipped on.
 *  - The Ice Skates were renamed Snowveil Skates in issue #1098 (same `ice_skates` id):
 *    copies still showing the old name get the new one. Copies renamed on an anvil keep
 *    their custom name.
 *
 * Matched by the `custom_item_id` PDC tag only; nothing else on the item (model,
 * equippable, enchants, trims, PDC, damage already taken) is touched, and no item is
 * created or removed.
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
            val unbreakable = id in UNBREAKABLE_IDS
            val oldName = RENAMED[id]
            if (!unbreakable && oldName == null) continue
            val meta = item.itemMeta ?: continue
            var changed = false
            if (unbreakable && !meta.isUnbreakable) {
                meta.isUnbreakable = true
                changed = true
            }
            if (oldName != null) {
                val name = meta.displayName()
                val current = plugin.itemManager.getItem(id)?.displayName
                if (name != null && current != null &&
                    PlainTextComponentSerializer.plainText().serialize(name) == oldName
                ) {
                    meta.displayName(current)
                    changed = true
                }
            }
            if (!changed) continue
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

        /** Custom item id -> the display name its older copies carry. */
        val RENAMED = mapOf(
            "ice_skates" to "Ice Skates",
        )
    }
}
