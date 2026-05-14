package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.enchant.EnchantTarget
import com.liam.joshymc.recipe.ENCHANT_DUST_SCROLL_RECIPE
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.ShapedRecipe

class CustomCraftingListener(private val plugin: Joshymc) : Listener {

    companion object {
        // Enchants eligible for the dust → scroll recipe (armor, pickaxe, axe, sword only)
        private val SCROLL_ELIGIBLE_TARGETS = setOf(
            EnchantTarget.SWORD,
            EnchantTarget.AXE,
            EnchantTarget.PICKAXE,
            EnchantTarget.HELMET,
            EnchantTarget.CHESTPLATE,
            EnchantTarget.LEGGINGS,
            EnchantTarget.BOOTS,
            EnchantTarget.ALL_ARMOR,
        )
    }

    @EventHandler
    fun onPrepareItemCraft(event: PrepareItemCraftEvent) {
        val recipe = event.recipe as? ShapedRecipe ?: return
        if (recipe.key.namespace != plugin.name.lowercase()) return

        val requirements = plugin.recipeManager.getCustomIngredients(recipe.key) ?: return
        if (requirements.isEmpty()) return

        // For each item in the crafting matrix that has a material requiring a
        // specific custom item, validate the PDC tag. Bukkit has already matched
        // the recipe shape via MaterialChoice; we just enforce the custom-item check.
        for (item in event.inventory.matrix) {
            if (item == null || item.type == Material.AIR) continue
            val requiredId = requirements[item.type] ?: continue
            if (!plugin.itemManager.isCustomItem(item, requiredId)) {
                event.inventory.result = null
                return
            }
        }

        // For the enchant dust recipe, replace the placeholder PAPER result
        // with a random level-1 scroll drawn from the eligible enchant pool.
        if (recipe.key.key == ENCHANT_DUST_SCROLL_RECIPE) {
            val eligible = plugin.customEnchantManager.getAllEnchants()
                .filter { it.target in SCROLL_ELIGIBLE_TARGETS }
            if (eligible.isEmpty()) {
                event.inventory.result = null
                return
            }
            event.inventory.result = plugin.customEnchantManager.createScroll(
                eligible.random().id, level = 1, chance = (1..100).random()
            )
        }
    }
}
