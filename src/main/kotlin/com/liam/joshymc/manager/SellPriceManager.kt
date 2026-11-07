package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta

/**
 * The one authoritative sell-price lookup (issue #1416), loaded from `sell-prices.yml > prices:`.
 * /sell (GUI, hand, all), /worth, the held-item Sell line, /market price and Sell Wands all read
 * it. The file's `categories:` block is organizational only (see [SellCatalog]). Completely
 * separate from shop.yml: /shop keeps its own buy prices.
 */
class SellPriceManager(private val plugin: Joshymc) {

    @Volatile
    private var catalog = SellCatalog.Catalog(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyList())
    private var prices: Map<Material, Double> = emptyMap()

    fun start() {
        val fileName = "sell-prices.yml"
        val file = plugin.configFile(fileName)
        if (!file.exists()) {
            plugin.saveResource(fileName, false)
        } else {
            mergeMissingFromDefaults(fileName, file)
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val section = config.getConfigurationSection("prices")
        if (section == null) {
            plugin.logger.warning("[Sell] sell-prices.yml has no 'prices:' section — /sell will have nothing to sell.")
        }
        val rawPrices = section?.getKeys(false)?.map { it to section.get(it) } ?: emptyList()

        val categories = mutableListOf<SellCatalog.CategoryEntry>()
        config.getConfigurationSection("categories")?.let { cats ->
            for (categoryId in cats.getKeys(false)) {
                val items = cats.getConfigurationSection("$categoryId.items") ?: continue
                for (key in items.getKeys(false)) {
                    val item = items.getConfigurationSection(key)
                    categories += SellCatalog.CategoryEntry(categoryId, key, item?.getString("material"), item?.get("sell"))
                }
            }
        }

        val built = SellCatalog.build(rawPrices, categories, ::resolve)
        catalog = built
        prices = built.prices.mapKeys { Material.valueOf(it.key) }

        plugin.logger.info("[Sell] Loaded ${prices.size} sell price(s) from sell-prices.yml > prices:.")
        val lines = SellCatalog.summaryLines(built)
        for (line in lines) plugin.logger.warning("[Sell] $line")
        if (lines.isNotEmpty()) plugin.logger.warning("[Sell] Full list in-game: /sell admin problems")
    }

    private fun resolve(key: String): SellCatalog.Resolution {
        val material = Material.matchMaterial(key) ?: return SellCatalog.Resolution.Unknown
        if (material.isLegacy || material.isAir || !material.isItem) return SellCatalog.Resolution.NotAnItem(material.name)
        return SellCatalog.Resolution.Item(material.name)
    }

    private fun mergeMissingFromDefaults(fileName: String, file: java.io.File) {
        val defaultStream = plugin.getResource(fileName) ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(file)

        if (com.liam.joshymc.util.ConfigUtil.looksLikeParseFailure(file, userCfg)) {
            plugin.logger.severe("[Sell] sell-prices.yml failed to load (invalid YAML) — the existing file has been preserved and was NOT overwritten. Fix the syntax error and reload.")
            return
        }

        val defaultsSection = defaults.getConfigurationSection("prices") ?: return
        val userSection = userCfg.getConfigurationSection("prices") ?: userCfg.createSection("prices")

        // A default is only added when no user key names the same material in any spelling, so a
        // hand-written `kelp: 2.25` never gets a second, conflicting `KELP:` next to it.
        val missing = SellCatalog.missingDefaults(
            defaultsSection.getKeys(false).toList(), userSection.getKeys(false).toList(), ::resolve,
        )
        for (key in missing) userSection.set(key, defaultsSection.get(key))
        if (missing.isNotEmpty()) {
            try {
                com.liam.joshymc.util.ConfigUtil.backup(file, plugin.logger, "Sell")
                userCfg.save(file)
                plugin.logger.info("[Sell] Added ${missing.size} new sell price(s) from bundled defaults: ${missing.joinToString(", ")}")
            } catch (e: Exception) {
                plugin.logger.warning("[Sell] Failed to save merged sell-prices.yml: ${e.message}")
            }
        }
    }

    /** Base price per single item, or null if the material isn't configured as sellable. */
    fun getPrice(material: Material): Double? = prices[material]

    /** The full sell-price catalog — the exact set of materials /sell and /worth accept. */
    fun getAllPrices(): Map<Material, Double> = prices

    /** The loaded catalog with its sources, categories and problems (admin diagnostics). */
    fun catalog(): SellCatalog.Catalog = catalog

    /** Null when this exact stack can be sold, otherwise why not. */
    fun rejectionReason(stack: ItemStack): String? {
        if (stack.type.isAir) return "empty"
        val hasPrice = prices.containsKey(stack.type)
        if (!stack.hasItemMeta()) {
            return SellItemRules.rejection(SellItemRules.Stack(stack.type.name, stack.amount, hasPrice))
        }
        val meta = stack.itemMeta
        return SellItemRules.rejection(
            SellItemRules.Stack(
                material = stack.type.name,
                amount = stack.amount,
                hasPrice = hasPrice,
                customItemId = plugin.itemManager.getCustomItemId(stack),
                dataKeys = meta.persistentDataContainer.keys.mapTo(HashSet()) { it.toString() },
                itemModel = if (meta.hasItemModel()) meta.itemModel?.toString() else null,
                hasCustomModelData = meta.hasCustomModelData(),
                hasNestedItems = hasNestedItems(stack),
            )
        )
    }

    /**
     * Whether this exact stack can be sold: the material has a price and the stack is an ordinary
     * vanilla stack (no JoshyMC custom item, plugin data, custom model or hidden contents).
     */
    fun isSellable(stack: ItemStack): Boolean = rejectionReason(stack) == null

    /** Decimal-safe base value of the full stack (no player bonuses); 0.0 if unsellable. */
    fun getStackValue(stack: ItemStack): Double {
        if (!isSellable(stack)) return 0.0
        val unitPrice = prices[stack.type] ?: return 0.0
        return SellCatalog.roundCents(unitPrice * stack.amount)
    }

    /**
     * What [seller] is paid for the full stack — the ONE payout formula every sell path uses:
     * base price × Flower Armor crop bonus × Catalyst mutation × rank sell multiplier
     * × [extraMultiplier] (a Sell Wand's own multiplier), rounded to cents. 0.0 if unsellable.
     */
    fun getStackValue(stack: ItemStack, seller: Player, extraMultiplier: Double = 1.0): Double {
        if (!isSellable(stack)) return 0.0
        val base = prices[stack.type] ?: return 0.0
        val unit = plugin.serverShopManager.applyCropBonus(base, stack.type, seller.uniqueId) *
            plugin.mutationsManager.getMutationMultiplier(stack) *
            plugin.rankManager.getSellMultiplier(seller) *
            extraMultiplier
        val value = SellCatalog.roundCents(unit * stack.amount)
        return if (value.isFinite() && value > 0.0) value else 0.0
    }

    /** True if a container/bundle item is carrying other items that a sale would delete. */
    private fun hasNestedItems(stack: ItemStack): Boolean {
        val meta = stack.itemMeta ?: return false
        if (meta is BundleMeta) {
            return meta.items.isNotEmpty()
        }
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState
            if (state is Container) {
                return state.inventory.contents.any { it != null && it.type != Material.AIR }
            }
        }
        return false
    }
}
