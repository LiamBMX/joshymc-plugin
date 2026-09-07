package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta

/**
 * Single source of truth for /sell pricing, loaded from sell-prices.yml. Completely
 * separate from shop.yml/ServerShopManager — /shop is a curated buy-only money sink,
 * /sell is the broad outlet for ordinary survival-obtainable vanilla items.
 */
class SellPriceManager(private val plugin: Joshymc) {

    private val prices = HashMap<Material, Double>()

    fun start() {
        val fileName = "sell-prices.yml"
        val file = plugin.configFile(fileName)
        if (!file.exists()) {
            plugin.saveResource(fileName, false)
        } else {
            mergeMissingFromDefaults(fileName, file)
        }

        prices.clear()
        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("prices")
        if (section == null) {
            plugin.logger.warning("[Sell] sell-prices.yml has no 'prices:' section — /sell will have nothing to sell.")
            return
        }

        for (key in section.getKeys(false)) {
            val material = Material.matchMaterial(key)
            if (material == null) {
                plugin.logger.warning("[Sell] Unknown material in sell-prices.yml: $key")
                continue
            }
            val price = section.getDouble(key)
            if (price > 0.0) prices[material] = price
        }

        plugin.logger.info("[Sell] Loaded ${prices.size} sell price(s).")
    }

    private fun mergeMissingFromDefaults(fileName: String, file: java.io.File) {
        val defaultStream = plugin.getResource(fileName) ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(file)

        val defaultsSection = defaults.getConfigurationSection("prices") ?: return
        val userSection = userCfg.getConfigurationSection("prices") ?: userCfg.createSection("prices")

        var added = 0
        for (key in defaultsSection.getKeys(false)) {
            if (userSection.contains(key)) continue
            userSection.set(key, defaultsSection.get(key))
            added++
        }
        if (added > 0) {
            try {
                userCfg.save(file)
                plugin.logger.info("[Sell] Added $added new sell price(s) from bundled defaults.")
            } catch (e: Exception) {
                plugin.logger.warning("[Sell] Failed to save merged sell-prices.yml: ${e.message}")
            }
        }
    }

    /** Base price per single item, or null if the material isn't configured as sellable. */
    fun getPrice(material: Material): Double? = prices[material]

    /**
     * Whether this exact stack can be sold: the material has a configured price, it isn't a
     * JoshyMC custom item (rejected regardless of matching base material), and — for
     * containers/bundles — it isn't hiding nested items that selling would otherwise destroy.
     */
    fun isSellable(stack: ItemStack): Boolean {
        if (stack.type == Material.AIR) return false
        if (prices[stack.type] == null) return false
        if (plugin.itemManager.getCustomItemId(stack) != null) return false
        if (hasNestedItems(stack)) return false
        return true
    }

    /** Decimal-safe value of the full stack; 0.0 if unsellable. */
    fun getStackValue(stack: ItemStack): Double {
        if (!isSellable(stack)) return 0.0
        val unitPrice = prices[stack.type] ?: return 0.0
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
