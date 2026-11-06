package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.staff.CreativeItemRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Refuses Creative inventory actions that would create or copy JoshyMC items (issue #1383,
 * rules in [CreativeItemRules]). Survival inventories, legitimate redemption and staff give
 * commands are untouched; only the Creative client's "set this slot to any item" path is.
 */
class CreativeValuablesListener(private val plugin: Joshymc) : Listener {

    private val lastNotice = ConcurrentHashMap<UUID, Long>()

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCreative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        val item: ItemStack = event.cursor
        if (item.isEmpty || !item.hasItemMeta()) return
        val keys = item.itemMeta.persistentDataContainer.keys.map { it.toString() }
        if (!CreativeItemRules.protectedItem(keys)) return
        if (player.hasPermission(CreativeItemRules.BYPASS_PERMISSION)) return

        event.isCancelled = true
        val now = System.currentTimeMillis()
        val previous = lastNotice[player.uniqueId] ?: 0L
        if (now - previous > NOTICE_INTERVAL_MS) {
            lastNotice[player.uniqueId] = now
            plugin.commsManager.send(
                player,
                Component.text("JoshyMC items can't be created or moved in the Creative inventory. Switch to Survival to move them.", NamedTextColor.RED)
            )
            val tags = keys.filter { it.startsWith("${CreativeItemRules.NAMESPACE}:") }.joinToString(",")
            plugin.logger.warning(
                "[CreativeItems] Refused a Creative inventory action by ${player.name} (${player.uniqueId}) with ${item.type} carrying JoshyMC data ($tags)."
            )
        }
    }

    companion object {
        private const val NOTICE_INTERVAL_MS = 10_000L
    }
}
