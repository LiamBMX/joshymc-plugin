package com.liam.joshymc.recipe

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe

const val ENCHANT_DUST_SCROLL_RECIPE = "enchant_dust_scroll"

class CustomRecipes(private val plugin: Joshymc) {

    private val registeredKeys = mutableListOf<NamespacedKey>()

    // For each recipe key: which materials require a specific custom-item PDC tag.
    // Populated as a side-effect of item() calls inside addRecipe configure blocks.
    private val customIngredientRequirements = mutableMapOf<NamespacedKey, Map<Material, String>>()
    private val pendingCustomIngredients = mutableMapOf<Material, String>()

    fun registerAll() {
        registerWeaponRecipes()
        registerToolRecipes()
        registerArmorRecipes()
        registerConsumableRecipes()
        registerLegendaryRecipes()
        registerEnchantDustRecipes()

        plugin.logger.info("Registered ${registeredKeys.size} custom recipe(s).")
    }

    fun clear() {
        for (key in registeredKeys) {
            Bukkit.removeRecipe(key)
        }
        registeredKeys.clear()
        customIngredientRequirements.clear()
    }

    fun getCustomIngredients(key: NamespacedKey): Map<Material, String>? =
        customIngredientRequirements[key]

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Use MaterialChoice so any item of this material matches at the Bukkit level.
    // CustomCraftingListener then validates the PDC tag for custom items.
    private fun item(id: String): RecipeChoice.MaterialChoice {
        val customItem = plugin.itemManager.getItem(id)!!
        pendingCustomIngredients[customItem.material] = id
        return RecipeChoice.MaterialChoice(customItem.material)
    }

    private fun vanilla(material: Material): RecipeChoice.MaterialChoice =
        RecipeChoice.MaterialChoice(material)

    private fun addRecipe(name: String, result: ItemStack, configure: ShapedRecipe.() -> Unit) {
        pendingCustomIngredients.clear()
        val key = NamespacedKey(plugin, name)
        val recipe = ShapedRecipe(key, result)
        recipe.configure()
        Bukkit.addRecipe(recipe)
        registeredKeys.add(key)
        if (pendingCustomIngredients.isNotEmpty()) {
            customIngredientRequirements[key] = pendingCustomIngredients.toMap()
        }
    }

    private fun result(id: String): ItemStack =
        plugin.itemManager.getItem(id)!!.createItemStack()

    // ── Crafting Materials → Weapons ─────────────────────────────────────────

    private fun registerWeaponRecipes() {
        addRecipe("void_blade", result("void_blade")) {
            shape("V", "V", "S")
            setIngredient('V', item("void_shard"))
            setIngredient('S', vanilla(Material.STICK))
        }

        addRecipe("soul_scythe", result("soul_scythe")) {
            shape("FFF", " SF", " S ")
            setIngredient('F', item("soul_fragment"))
            setIngredient('S', vanilla(Material.STICK))
        }

        addRecipe("inferno_axe", result("inferno_axe")) {
            shape("II", "IS", " S")
            setIngredient('I', item("inferno_core"))
            setIngredient('S', vanilla(Material.STICK))
        }

        addRecipe("crystal_mace", result("crystal_mace")) {
            shape("C", "C", "B")
            setIngredient('C', item("crystal_essence"))
            setIngredient('B', vanilla(Material.BREEZE_ROD))
        }
    }

    // ── Crafting Materials → Tools ───────────────────────────────────────────

    private fun registerToolRecipes() {
        addRecipe("farmers_sickle", result("farmers_sickle")) {
            shape("CCC", " D ", " S ")
            setIngredient('C', item("crystal_essence"))
            setIngredient('D', vanilla(Material.DIAMOND))
            setIngredient('S', vanilla(Material.STICK))
        }

        addRecipe("excavator", result("excavator")) {
            shape("VVV", " D ", "   ")
            setIngredient('V', item("void_shard"))
            setIngredient('D', vanilla(Material.DIAMOND_SHOVEL))
        }

        addRecipe("magnet_wand", result("magnet_wand")) {
            shape("I", "C", "S")
            setIngredient('I', vanilla(Material.IRON_BLOCK))
            setIngredient('C', item("crystal_essence"))
            setIngredient('S', vanilla(Material.STICK))
        }
    }

    // ── Crafting Materials → Armor ───────────────────────────────────────────

    private fun registerArmorRecipes() {
        registerArmorSet("void", "void_shard")
        registerArmorSet("inferno", "inferno_core")
        registerArmorSet("crystal", "crystal_essence")
        registerArmorSet("soul", "soul_fragment")
    }

    private fun registerArmorSet(prefix: String, matId: String) {
        addRecipe("${prefix}_helmet", result("${prefix}_helmet")) {
            shape("MMM", "M M", "   ")
            setIngredient('M', item(matId))
        }

        addRecipe("${prefix}_chestplate", result("${prefix}_chestplate")) {
            shape("M M", "MMM", "MMM")
            setIngredient('M', item(matId))
        }

        addRecipe("${prefix}_leggings", result("${prefix}_leggings")) {
            shape("MMM", "M M", "M M")
            setIngredient('M', item(matId))
        }

        addRecipe("${prefix}_boots", result("${prefix}_boots")) {
            shape("   ", "M M", "M M")
            setIngredient('M', item(matId))
        }
    }

    // ── Consumables ──────────────────────────────────────────────────────────

    private fun registerConsumableRecipes() {
        addRecipe("speed_apple", result("speed_apple")) {
            shape(" S ", "SAS", " S ")
            setIngredient('S', vanilla(Material.SUGAR))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        addRecipe("strength_apple", result("strength_apple")) {
            shape(" B ", "BAB", " B ")
            setIngredient('B', vanilla(Material.BLAZE_POWDER))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        addRecipe("giants_brew", result("giants_brew")) {
            shape("G", "R", "A")
            setIngredient('G', vanilla(Material.GLASS_BOTTLE))
            setIngredient('R', item("ancient_rune"))
            setIngredient('A', vanilla(Material.GOLDEN_APPLE))
        }

        addRecipe("miners_brew", result("miners_brew")) {
            shape("G", "C", "R")
            setIngredient('G', vanilla(Material.GLASS_BOTTLE))
            setIngredient('C', item("crystal_essence"))
            setIngredient('R', vanilla(Material.REDSTONE))
        }
    }

    // ── Enchant Dust → Random Scroll ─────────────────────────────────────────

    // Result is a PAPER placeholder; CustomCraftingListener overrides it with a
    // random eligible enchant scroll (level 1) at PrepareItemCraftEvent time.
    private fun registerEnchantDustRecipes() {
        addRecipe(ENCHANT_DUST_SCROLL_RECIPE, ItemStack(Material.PAPER)) {
            shape("DDD", "DBD", "DDD")
            setIngredient('D', item("enchanted_dust"))
            setIngredient('B', vanilla(Material.BOOK))
        }
    }

    // ── Legendary Items ──────────────────────────────────────────────────────

    private fun registerLegendaryRecipes() {
        addRecipe("blaze_kings_crown", result("blaze_kings_crown")) {
            shape("IRI", "GIG", "   ")
            setIngredient('I', item("inferno_core"))
            setIngredient('R', item("ancient_rune"))
            setIngredient('G', vanilla(Material.GOLD_BLOCK))
        }

        addRecipe("phantom_cloak", result("phantom_cloak")) {
            shape("P P", "PAP", "PPP")
            setIngredient('P', vanilla(Material.PHANTOM_MEMBRANE))
            setIngredient('A', item("ancient_rune"))
        }

        addRecipe("poseidons_trident", result("poseidons_trident")) {
            shape("RRR", " T ", " H ")
            setIngredient('R', item("ancient_rune"))
            setIngredient('T', vanilla(Material.TRIDENT))
            setIngredient('H', vanilla(Material.HEART_OF_THE_SEA))
        }
    }
}
