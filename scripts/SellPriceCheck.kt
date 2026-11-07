// Checks the /sell price catalog (issue #1416): the Bukkit-free catalog and stack rules, the
// bundled sell-prices.yml against Paper 26.2's real Material names, the farmable materials the
// issue asks for, and crafting arbitrage against the sell prices and /shop buy prices. The Gradle
// build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/sell-price-check.init.gradle.kts sellPriceCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.manager.SellCatalog
import com.liam.joshymc.manager.SellCatalog.Kind
import com.liam.joshymc.manager.SellCatalog.Resolution
import com.liam.joshymc.manager.SellItemRules
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/**
 * SellPriceManager's resolution, minus `isItem`/`isAir`: those need a running server's registries.
 * The server-side loader still refuses non-items, and [BLOCK_ONLY] covers the names this check
 * would otherwise mistake for items.
 */
private fun resolve(key: String): Resolution {
    val material = Material.matchMaterial(key) ?: return Resolution.Unknown
    if (material.isLegacy || material.name == "AIR" || material.name in BLOCK_ONLY) return Resolution.NotAnItem(material.name)
    return Resolution.Item(material.name)
}

private val BLOCK_ONLY = setOf("PUMPKIN_STEM", "MELON_STEM", "ATTACHED_PUMPKIN_STEM", "ATTACHED_MELON_STEM",
    "WATER", "LAVA", "CAVE_AIR", "VOID_AIR", "KELP_PLANT", "BAMBOO_SAPLING", "COCOA", "CARROTS", "POTATOES",
    "BEETROOTS", "SWEET_BERRY_BUSH", "BIG_DRIPLEAF_STEM", "CAVE_VINES", "CAVE_VINES_PLANT", "TALL_SEAGRASS", "FIRE", "SOUL_FIRE")

/** A fake resolver for the pure catalog tests. */
private fun fake(key: String): Resolution = when (val k = key.uppercase().removePrefix("MINECRAFT:")) {
    "WATER" -> Resolution.NotAnItem("WATER")
    "KELP", "DRIED_KELP", "STONE", "COBBLESTONE", "SUGAR_CANE", "WHEAT", "SHULKER_BOX", "RED_SHULKER_BOX",
    "DIAMOND_SWORD", "PUMPKIN" -> Resolution.Item(k)
    else -> Resolution.Unknown
}

private fun yaml(path: String) = YamlConfiguration.loadConfiguration(File(path))

fun main() {
    // ── Price parsing ───────────────────────────────────
    check("int price", SellCatalog.parsePrice(15) == SellCatalog.Price.Valid(15.0))
    check("decimal price", SellCatalog.parsePrice(2.25) == SellCatalog.Price.Valid(2.25))
    check("quoted number price", SellCatalog.parsePrice("0.75") == SellCatalog.Price.Valid(0.75))
    fun kind(raw: Any?) = (SellCatalog.parsePrice(raw) as? SellCatalog.Price.Invalid)?.kind
    check("text price is invalid", kind("cheap") == Kind.INVALID_PRICE)
    check("empty price is invalid", kind(null) == Kind.INVALID_PRICE)
    check("boolean price is invalid", kind(true) == Kind.INVALID_PRICE)
    check("negative price", kind(-1) == Kind.NEGATIVE_PRICE)
    check("NaN price", kind(Double.NaN) == Kind.NON_FINITE_PRICE)
    check("NaN text price", kind("NaN") == Kind.NON_FINITE_PRICE)
    check("infinite price", kind(Double.POSITIVE_INFINITY) == Kind.NON_FINITE_PRICE)
    check("0 disables", kind(0) == Kind.DISABLED)
    check("absurd price rejected", kind(5_000_000) == Kind.PRICE_TOO_HIGH)
    check("cents rounding", SellCatalog.roundCents(2.25 * 3) == 6.75 && SellCatalog.roundCents(0.1 * 3) == 0.3)

    // ── Catalog building ────────────────────────────────
    val built = SellCatalog.build(
        prices = listOf(
            "STONE" to 0.75, "kelp" to 2.0, "KELP" to 2.25, "dried_kelp" to 6, "WATER" to 1, "NOPE" to 1,
            "SHULKER_BOX" to 100, "DIAMOND_SWORD" to 50, "WHEAT" to -5, "SUGAR_CANE" to 0, "minecraft:pumpkin" to 20,
        ),
        categories = listOf(
            SellCatalog.CategoryEntry("blocks", "STONE", null, 0.5),
            SellCatalog.CategoryEntry("blocks", "COBBLESTONE", null, 0.5),
            SellCatalog.CategoryEntry("farming", "WHEAT", null, 15),
            SellCatalog.CategoryEntry("farming", "PUMPKIN", null, 20),
            SellCatalog.CategoryEntry("farming", "MYSTERY", null, 1),
        ),
        resolve = ::fake,
    )
    check("prices: wins over a conflicting category value", built.prices["STONE"] == 0.75)
    check("category conflict reported", built.problems.any { it.kind == Kind.CATEGORY_CONFLICT && it.key == "blocks.STONE" })
    check("equal category value is not a conflict", built.problems.none { it.key == "farming.PUMPKIN" })
    check("category alone never makes an item sellable", "COBBLESTONE" !in built.prices)
    check("category-only reported", built.problems.any { it.kind == Kind.CATEGORY_ONLY && it.key == "blocks.COBBLESTONE" })
    check("unknown category material reported", built.problems.any { it.kind == Kind.CATEGORY_UNKNOWN })
    check("items outside categories are sellable", built.prices["DRIED_KELP"] == 6.0)
    check("namespaced key resolves", built.prices["PUMPKIN"] == 20.0)
    check("duplicate spelling: later entry wins (old behavior)", built.prices["KELP"] == 2.25 && built.sourceKeys["KELP"] == "KELP")
    check("duplicate reported", built.problems.any { it.kind == Kind.DUPLICATE && it.key == "KELP" })
    check("unknown material reported", built.problems.any { it.kind == Kind.UNKNOWN_MATERIAL && it.key == "NOPE" })
    check("non-item reported", built.problems.any { it.kind == Kind.NOT_AN_ITEM && it.key == "WATER" })
    check("shulker box never sellable", "SHULKER_BOX" !in built.prices && built.rejected["SHULKER_BOX"]?.kind == Kind.UNSUPPORTED)
    check("weapons never sellable", "DIAMOND_SWORD" !in built.prices)
    check("negative price rejected", "WHEAT" !in built.prices && built.rejected["WHEAT"]?.kind == Kind.NEGATIVE_PRICE)
    check("price 0 disables quietly", "SUGAR_CANE" !in built.prices && built.problems.none { it.key == "SUGAR_CANE" })
    check("rejected entries don't get category-only noise", built.problems.none { it.key == "farming.WHEAT" })
    val later = SellCatalog.build(listOf("KELP" to 2.0, "kelp" to "oops"), emptyList(), ::fake)
    check("an invalid later duplicate disables the material", "KELP" !in later.prices)
    check("summary is one line per kind", SellCatalog.summaryLines(built).size == built.problems.map { it.kind }.distinct().size)
    val many = SellCatalog.build((1..40).map { "UNKNOWN_$it" to 1 }, emptyList(), ::fake)
    check("summary caps examples", SellCatalog.summaryLines(many).single().contains("and ${40 - SellCatalog.LOG_EXAMPLES} more"))
    check("default merge skips other spellings",
        SellCatalog.missingDefaults(listOf("KELP", "STONE", "DRIED_KELP"), listOf("kelp", "minecraft:stone"), ::fake) == listOf("DRIED_KELP"))

    // ── Stack rules ─────────────────────────────────────
    fun stack(material: String = "KELP", keys: Set<String> = emptySet(), model: String? = null, id: String? = null,
              cmd: Boolean = false, nested: Boolean = false, priced: Boolean = true) =
        SellItemRules.rejection(SellItemRules.Stack(material, 64, priced, id, keys, model, cmd, nested))
    check("plain stack sells", stack() == null)
    check("unpriced stack refused", stack(priced = false) != null)
    check("custom item refused", stack("PRISMARINE_SHARD", id = "void_shard") != null)
    check("crate key refused", stack("TRIPWIRE_HOOK", setOf("joshymc:crate_key")) != null)
    check("money voucher refused", stack("PAPER", setOf("joshymc:voucher_type", "joshymc:voucher_amount")) != null)
    check("fly voucher refused", stack("FEATHER", setOf("joshymc:voucher_type", "joshymc:fly_voucher_seconds")) != null)
    check("chat tag voucher refused", stack("PAPER", setOf("joshymc:chattag_id")) != null)
    check("resurge key refused", stack("TRIPWIRE_HOOK", setOf("joshymc:resurge_key")) != null)
    check("other plugin's data refused", stack(keys = setOf("excellentcrates:key")) != null)
    check("custom item model refused", stack(model = "joshymc:token") != null)
    check("vanilla item model is fine", stack(model = "minecraft:kelp") == null)
    check("custom model data refused", stack(cmd = true) != null)
    check("filled container refused", stack("CHEST", nested = true) != null)
    check("shulker refused even if priced", stack("WHITE_SHULKER_BOX") != null)
    check("tools refused even if priced", stack("NETHERITE_PICKAXE") != null)
    check("Catalyst mutation still sells", stack(keys = setOf("joshymc:catalyst_mutation")) == null)
    check("death drops still sell", stack(keys = setOf("joshymc:death_drop")) == null)
    check("signed items still sell", stack(keys = setOf("joshymc:signed", "joshymc:signer_uuid", "joshymc:signer_name", "joshymc:signed_at")) == null)
    val fishKeys = setOf("joshymc:fish_id", "joshymc:fish_weight", "joshymc:fish_rarity")
    check("JoshyMC fish on a fish base sells as that fish", stack("SALMON", fishKeys, "joshymc:fish/koi") == null)
    check("JoshyMC fish on a Heart of the Sea base refused", stack("HEART_OF_THE_SEA", fishKeys, "joshymc:fish/abyss") != null)
    check("fish exception doesn't cover other models", stack("COD", fishKeys, "joshymc:token") != null)
    check("fish keys alone don't pass on non-fish", stack("KELP", fishKeys) != null)

    // ── Bundled sell-prices.yml ─────────────────────────
    val sellYaml = yaml("src/main/resources/sell-prices.yml")
    val pricesSection = sellYaml.getConfigurationSection("prices")
    check("bundled file has prices:", pricesSection != null)
    val rawPrices = pricesSection!!.getKeys(false).map { it to pricesSection.get(it) }
    val categories = mutableListOf<SellCatalog.CategoryEntry>()
    val cats = sellYaml.getConfigurationSection("categories")
    for (id in cats?.getKeys(false) ?: emptySet()) {
        val items = cats!!.getConfigurationSection("$id.items") ?: continue
        for (key in items.getKeys(false)) {
            val item = items.getConfigurationSection(key)
            categories += SellCatalog.CategoryEntry(id, key, item?.getString("material"), item?.get("sell"))
            check("bundled category $id.$key has no sell value", item?.contains("sell") != true)
        }
    }
    val bundled = SellCatalog.build(rawPrices, categories, ::resolve)
    for (p in bundled.problems) System.err.println("  bundled problem: ${p.kind.label} ${p.key}: ${p.detail}")
    check("bundled catalog has no problems", bundled.problems.isEmpty())
    check("every bundled key is the exact Material name", rawPrices.all { (k, _) -> (resolve(k) as? Resolution.Item)?.name == k })
    val price = bundled.prices

    val required = listOf(
        // Farming
        "KELP", "DRIED_KELP", "DRIED_KELP_BLOCK", "SUGAR_CANE", "BAMBOO", "CACTUS", "WHEAT", "CARROT", "POTATO",
        "BEETROOT", "PUMPKIN", "MELON", "MELON_SLICE", "COCOA_BEANS", "NETHER_WART", "SWEET_BERRIES", "GLOW_BERRIES",
        "BROWN_MUSHROOM", "RED_MUSHROOM", "HONEYCOMB", "HONEY_BOTTLE", "HAY_BLOCK",
        // Resource farms
        "COBBLESTONE", "STONE", "COBBLED_DEEPSLATE", "BASALT", "MOSS_BLOCK", "MOSS_CARPET", "BONE_MEAL", "CLAY",
        "CLAY_BALL", "MUD", "SNOW_BLOCK", "SNOWBALL",
        // Tree farms
        "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "JUNGLE_LOG", "ACACIA_LOG", "DARK_OAK_LOG", "MANGROVE_LOG", "CHERRY_LOG",
        "PALE_OAK_LOG", "CRIMSON_STEM", "WARPED_STEM", "BAMBOO_BLOCK", "STRIPPED_BAMBOO_BLOCK",
        "STRIPPED_OAK_LOG", "STRIPPED_SPRUCE_LOG", "STRIPPED_BIRCH_LOG", "STRIPPED_JUNGLE_LOG", "STRIPPED_ACACIA_LOG",
        "STRIPPED_DARK_OAK_LOG", "STRIPPED_MANGROVE_LOG", "STRIPPED_CHERRY_LOG", "STRIPPED_PALE_OAK_LOG",
        "STRIPPED_CRIMSON_STEM", "STRIPPED_WARPED_STEM",
        "OAK_LEAVES", "SPRUCE_LEAVES", "BIRCH_LEAVES", "JUNGLE_LEAVES", "ACACIA_LEAVES", "DARK_OAK_LEAVES",
        "MANGROVE_LEAVES", "CHERRY_LEAVES", "PALE_OAK_LEAVES", "AZALEA_LEAVES", "FLOWERING_AZALEA_LEAVES",
        // Other renewables
        "EGG", "BROWN_EGG", "BLUE_EGG", "FEATHER", "INK_SAC", "GLOW_INK_SAC", "SLIME_BALL", "STRING", "BONE",
        "ROTTEN_FLESH", "GUNPOWDER", "PRISMARINE_SHARD", "PRISMARINE_CRYSTALS",
    ) + listOf("WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY", "LIGHT_GRAY", "CYAN",
        "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK").map { "${it}_WOOL" }
    for (m in required) check("$m is sellable", (price[m] ?: 0.0) > 0.0)
    // Every log/stem the server knows is sellable.
    for (m in Material.entries) {
        val n = m.name
        if (m.isLegacy || n in BLOCK_ONLY) continue
        if (n.endsWith("_LOG") || n.endsWith("_STEM") && n != "MUSHROOM_STEM" || n.endsWith("_HYPHAE") ||
            n.endsWith("_WOOD") || n.endsWith("_LEAVES")) {
            check("tree block $n is sellable", n in price)
        }
    }
    for (n in price.keys) check("$n is not a never-sellable material", SellCatalog.unsupportedReason(n) == null)

    // Prices that existed before issue #1416 are kept (spot checks of the values in the issue).
    check("existing SUGAR_CANE kept", price["SUGAR_CANE"] == 10.0)
    check("existing PUMPKIN kept", price["PUMPKIN"] == 20.0)
    check("existing WHEAT kept", price["WHEAT"] == 15.0)
    check("existing DRIED_KELP kept", price["DRIED_KELP"] == 6.0)

    // ── Crafting arbitrage ──────────────────────────────
    fun value(parts: Map<String, Int>): Double? {
        var sum = 0.0
        for ((m, n) in parts) sum += (price[m] ?: return null) * n
        return sum
    }
    // Reversible conversions (craft both ways, or place-and-break): both sides must be worth the same.
    val reversible = listOf(
        mapOf("WHEAT" to 9) to mapOf("HAY_BLOCK" to 1),
        mapOf("DRIED_KELP" to 9) to mapOf("DRIED_KELP_BLOCK" to 1),
        mapOf("BONE_MEAL" to 9) to mapOf("BONE_BLOCK" to 1),
        mapOf("CLAY_BALL" to 4) to mapOf("CLAY" to 1),
        mapOf("SNOWBALL" to 4) to mapOf("SNOW_BLOCK" to 1),
        mapOf("COAL" to 9) to mapOf("COAL_BLOCK" to 1),
        mapOf("REDSTONE" to 9) to mapOf("REDSTONE_BLOCK" to 1),
        mapOf("LAPIS_LAZULI" to 9) to mapOf("LAPIS_BLOCK" to 1),
        mapOf("DIAMOND" to 9) to mapOf("DIAMOND_BLOCK" to 1),
        mapOf("EMERALD" to 9) to mapOf("EMERALD_BLOCK" to 1),
        mapOf("IRON_INGOT" to 9) to mapOf("IRON_BLOCK" to 1),
        mapOf("GOLD_INGOT" to 9) to mapOf("GOLD_BLOCK" to 1),
        mapOf("COPPER_INGOT" to 9) to mapOf("COPPER_BLOCK" to 1),
        mapOf("NETHERITE_INGOT" to 9) to mapOf("NETHERITE_BLOCK" to 1),
        mapOf("GLOWSTONE_DUST" to 4) to mapOf("GLOWSTONE" to 1),
    )
    for ((a, b) in reversible) {
        check("reversible $a <-> $b is priced equally", value(a) != null && kotlin.math.abs(value(a)!! - value(b)!!) < 1e-9)
    }
    // One-way crafts that start from something sellable: crafting must never beat selling the inputs.
    // Recipes that use the materials issue #1416 added are all here.
    val oneWay = listOf(
        mapOf("BONE_BLOCK" to 1) to mapOf("BONE_MEAL" to 9),
        mapOf("COBBLESTONE" to 1, "MOSS_BLOCK" to 1) to mapOf("MOSSY_COBBLESTONE" to 1),
        mapOf("MANGROVE_ROOTS" to 1, "MUD" to 1) to mapOf("MUDDY_MANGROVE_ROOTS" to 1),
        mapOf("WHEAT" to 3) to mapOf("BREAD" to 1),
        mapOf("MELON_SLICE" to 9) to mapOf("MELON" to 1),
        mapOf("SUGAR_CANE" to 3) to mapOf("PAPER" to 3),
        mapOf("SUGAR_CANE" to 1) to mapOf("SUGAR" to 1),
        mapOf("BAMBOO" to 9) to mapOf("BAMBOO_BLOCK" to 1),
        mapOf("HONEYCOMB" to 4) to mapOf("HONEYCOMB_BLOCK" to 1),
        mapOf("HONEY_BOTTLE" to 4) to mapOf("HONEY_BLOCK" to 1),
        mapOf("SLIME_BALL" to 9) to mapOf("SLIME_BLOCK" to 1),
        mapOf("NETHER_WART" to 9) to mapOf("NETHER_WART_BLOCK" to 1),
        mapOf("STRING" to 4) to mapOf("WHITE_WOOL" to 1),
        mapOf("WHITE_WOOL" to 2) to mapOf("WHITE_CARPET" to 3),
        mapOf("OAK_LOG" to 1) to mapOf("OAK_PLANKS" to 4),
        mapOf("EGG" to 1, "SUGAR" to 2, "WHEAT" to 3, "MILK_BUCKET" to 3) to mapOf("CAKE" to 1, "BUCKET" to 3),
        mapOf("BROWN_EGG" to 1, "SUGAR" to 2, "WHEAT" to 3, "MILK_BUCKET" to 3) to mapOf("CAKE" to 1, "BUCKET" to 3),
        mapOf("BAMBOO_BLOCK" to 1) to mapOf("BAMBOO_PLANKS" to 2),
        mapOf("DRIED_KELP_BLOCK" to 1) to mapOf("DRIED_KELP" to 9),
    )
    for ((input, output) in oneWay) {
        val vin = value(input); val vout = value(output)
        check("recipe inputs/outputs priced: $input -> $output", vin != null && vout != null)
        check("crafting $input -> $output doesn't beat selling", vout!! <= vin!! + 1e-9)
    }
    // Existing one-way gains that predate issue #1416. They are left as they are (owner approval
    // needed, see docs/sell-prices.md) but listed here so a NEW profitable recipe fails the check.
    val documentedExisting = listOf(
        mapOf("BONE" to 1) to mapOf("BONE_MEAL" to 3),
        mapOf("IRON_INGOT" to 1) to mapOf("IRON_NUGGET" to 9),
        mapOf("GOLD_INGOT" to 1) to mapOf("GOLD_NUGGET" to 9),
        mapOf("COPPER_INGOT" to 1) to mapOf("COPPER_NUGGET" to 9),
        mapOf("RAW_IRON_BLOCK" to 1) to mapOf("RAW_IRON" to 9),
        mapOf("RAW_GOLD_BLOCK" to 1) to mapOf("RAW_GOLD" to 9),
        mapOf("RAW_COPPER_BLOCK" to 1) to mapOf("RAW_COPPER" to 9),
        mapOf("SLIME_BLOCK" to 1) to mapOf("SLIME_BALL" to 9),
        mapOf("HONEY_BLOCK" to 1, "GLASS_BOTTLE" to 4) to mapOf("HONEY_BOTTLE" to 4),
        mapOf("OAK_PLANKS" to 3) to mapOf("OAK_SLAB" to 6),
        mapOf("OAK_PLANKS" to 6) to mapOf("OAK_DOOR" to 3),
        mapOf("GLASS" to 6) to mapOf("GLASS_PANE" to 16),
        mapOf("GLASS" to 8, "RED_DYE" to 1) to mapOf("RED_STAINED_GLASS" to 8),
        mapOf("POPPY" to 1) to mapOf("RED_DYE" to 1),
        mapOf("ROSE_BUSH" to 1) to mapOf("RED_DYE" to 2),
        mapOf("MELON_SLICE" to 1) to mapOf("MELON_SEEDS" to 1),
        mapOf("PUMPKIN" to 1) to mapOf("CARVED_PUMPKIN" to 1, "PUMPKIN_SEEDS" to 4),
        mapOf("BROWN_MUSHROOM" to 1, "RED_MUSHROOM" to 1) to mapOf("MUSHROOM_STEW" to 1),
        mapOf("PUMPKIN" to 1, "SUGAR" to 1, "EGG" to 1) to mapOf("PUMPKIN_PIE" to 1),
    )
    for ((input, output) in documentedExisting) {
        check("documented existing gain $input -> $output still priced", value(input) != null && value(output) != null)
    }
    // Keep the list honest: once the owner approves a fix and the gain disappears, the entry must
    // move to oneWay so it stays guarded.
    for ((input, output) in documentedExisting) {
        check("documented existing gain $input -> $output is still a gain (else move it to oneWay)", value(output)!! > value(input)!!)
    }

    // ── /shop: unchanged, and never a source of profit ──
    val shop = yaml("src/main/resources/shop.yml").getConfigurationSection("categories")!!
    val buy = HashMap<String, Double>()
    for (id in shop.getKeys(false)) {
        val items = shop.getConfigurationSection("$id.items") ?: continue
        for (key in items.getKeys(false)) {
            val item = items.getConfigurationSection(key) ?: continue
            if (item.contains("effect") || item.contains("spawner")) continue
            val name = (resolve(item.getString("material") ?: key) as? Resolution.Item)?.name ?: continue
            val b = item.getDouble("buy", 0.0)
            if (b > 0) buy.merge(name, b, ::minOf)
        }
    }
    for ((m, b) in buy) {
        val s = price[m] ?: continue
        check("/shop $m buys for more than it sells ($b > $s)", b > s)
    }
    // Any recipe whose inputs can all be bought from /shop must cost more than its output sells for.
    for ((input, output) in reversible + oneWay + documentedExisting) {
        if (!input.keys.all { it in buy }) continue
        val cost = input.entries.sumOf { (m, n) -> buy[m]!! * n }
        check("buying $input from /shop and selling $output loses money", value(output)!! < cost)
    }

    // ── Wiring ──────────────────────────────────────────
    fun src(path: String) = File("src/main/kotlin/com/liam/joshymc/$path").readText()
    val serverShop = src("manager/ServerShopManager.kt")
    check("ServerShopManager no longer loads sell-prices.yml", "\"sell-prices.yml\"" !in serverShop)
    check("ServerShopManager.getBaseSellPrice delegates to SellPriceManager",
        "fun getBaseSellPrice(material: Material): Double? = plugin.sellPriceManager.getPrice(material)" in serverShop)
    check("held-item Sell line uses SellPriceManager", "sellPriceManager" in src("listener/WorthListener.kt") &&
        "marketManager" !in src("listener/WorthListener.kt"))
    check("/worth uses SellPriceManager", "sellPriceManager.getPrice" in src("command/WorthCommand.kt"))
    for (path in listOf("command/SellCommand.kt", "listener/SellWandListener.kt", "manager/MarketManager.kt")) {
        val text = src(path)
        check("$path pays through the shared formula", "applyCropBonus" !in text && "getMutationMultiplier" !in text &&
            "getSellMultiplier" !in text)
    }
    val plugin = File("src/main/resources/plugin.yml").readText()
    check("admin diagnostics permission is op-only", Regex("joshymc\\.sell\\.admin:\\s*\\n\\s*description:[^\\n]*\\n\\s*default: op").containsMatchIn(plugin))

    println("Sell price checks passed ($checks checks, ${price.size} sellable materials).")
}
