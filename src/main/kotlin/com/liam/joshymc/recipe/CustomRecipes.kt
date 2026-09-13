package com.liam.joshymc.recipe

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe

const val ENCHANT_DUST_SCROLL_RECIPE = "enchant_dust_scroll"
const val BEDROCK_BREAKER_SCROLL_RECIPE = "bedrock_breaker_scroll"

class CustomRecipes(private val plugin: Joshymc) {

    private val registeredKeys = mutableListOf<NamespacedKey>()

    // For each recipe key: which materials require a specific custom-item PDC tag.
    // Populated as a side-effect of item() calls inside addRecipe configure blocks.
    private val customIngredientRequirements = mutableMapOf<NamespacedKey, Map<Material, String>>()
    private val pendingCustomIngredients = mutableMapOf<Material, String>()

    fun registerAll() {
        registerEnchantDustRecipes()
        registerScrollRecipes()

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

    // ── Specific Enchant Scroll Recipes ──────────────────────────────────────

    // Results are PAPER placeholders; CustomCraftingListener overrides each
    // with the specific scroll at PrepareItemCraftEvent time.
    private fun registerScrollRecipes() {
        // Bedrock Breaker I (100% chance):
        //   F R F    F = soul_fragment, R = ancient_rune
        //   N * N    N = netherite_ingot, * = nether_star
        //   F R F
        addRecipe(BEDROCK_BREAKER_SCROLL_RECIPE, ItemStack(Material.PAPER)) {
            shape("FRF", "NSN", "FRF")
            setIngredient('F', item("soul_fragment"))
            setIngredient('R', item("ancient_rune"))
            setIngredient('N', vanilla(Material.NETHERITE_INGOT))
            setIngredient('S', vanilla(Material.NETHER_STAR))
        }
    }
}
