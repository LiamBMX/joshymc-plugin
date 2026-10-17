package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.VikingBoomerang
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent

/**
 * Keeps the Viking Boomerang's built-in Loyalty III (issue #1218) through item modification.
 * Throwing, returning, storing and picking it up are all vanilla and keep the item as is, so
 * only the stations that rewrite enchantments need a check here, and only when a boomerang
 * is in them (no inventory scanning).
 */
class VikingBoomerangListener(private val plugin: Joshymc) : Listener {

    private fun isBoomerang(item: org.bukkit.inventory.ItemStack?) =
        plugin.itemManager.isCustomItem(item, VikingBoomerang.ID)

    /**
     * Runs after vanilla and the custom enchant merge (HIGH): whatever the anvil made, the
     * boomerang comes out with exactly Loyalty III and its lore lists the other enchants.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAnvil(event: PrepareAnvilEvent) {
        val result = event.result ?: return
        if (!isBoomerang(result)) return
        val fixed = result.clone()
        if (VikingBoomerang.normalize(fixed)) event.result = fixed
    }

    /**
     * A grindstone strips Loyalty and pays XP for it. Putting it back afterwards would turn
     * the boomerang into an endless XP source, so boomerangs can't be ground at all.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onGrindstone(event: PrepareGrindstoneEvent) {
        val inv = event.inventory
        if (isBoomerang(inv.getItem(0)) || isBoomerang(inv.getItem(1))) event.result = null
    }
}
