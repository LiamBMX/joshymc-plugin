package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta

/**
 * Deposit-GUI safety layer for /sell. Prices themselves come from
 * ServerShopManager.getSellPrice(), which loads sell-prices.yml — the same source
 * /worth reads from, so /sell and /worth always agree on a price. This class adds the
 * checks specific to the deposit GUI: custom-item rejection and nested-container safety.
 */
class SellPriceManager(private val plugin: Joshymc) {

    /** Base price per single item, or null if the material isn't configured as sellable. */
    fun getPrice(material: Material): Double? = plugin.serverShopManager.getSellPrice(material)

    /**
     * Whether this exact stack can be sold: the material has a configured price, it isn't a
     * JoshyMC custom item (rejected regardless of matching base material), and — for
     * containers/bundles — it isn't hiding nested items that selling would otherwise destroy.
     */
    fun isSellable(stack: ItemStack): Boolean {
        if (stack.type == Material.AIR) return false
        if (getPrice(stack.type) == null) return false
        if (plugin.itemManager.getCustomItemId(stack) != null) return false
        if (hasNestedItems(stack)) return false
        return true
    }

    /** Decimal-safe value of the full stack; 0.0 if unsellable. */
    fun getStackValue(stack: ItemStack): Double {
        if (!isSellable(stack)) return 0.0
        val unitPrice = getPrice(stack.type) ?: return 0.0
        val raw = unitPrice * stack.amount
        return Math.round(raw * 100.0) / 100.0
    }

    /** True if a container/bundle item is carrying other items that a sale would delete. */
    private fun hasNestedItems(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        if (meta is BundleMeta) {
            return meta.items.isNotEmpty()
        }
        if (meta is BlockStateMeta) {
            val state = meta.blockState
            if (state is Container) {
                return state.inventory.contents.any { it != null && it.type != Material.AIR }
            }
        }
        return false
    }
}
