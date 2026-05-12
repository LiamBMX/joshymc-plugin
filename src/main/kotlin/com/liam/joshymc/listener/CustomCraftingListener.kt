package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.ShapedRecipe

class CustomCraftingListener(private val plugin: Joshymc) : Listener {

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
    }
}
