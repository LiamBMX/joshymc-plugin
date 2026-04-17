package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc

/**
 * Central registry for custom crafting recipes.
 * Add recipes in `registerAll()` as items are added.
 */
class RecipeManager(private val plugin: Joshymc) {

    private val registeredKeys = mutableListOf<org.bukkit.NamespacedKey>()

    fun registerAll() {
        val customRecipes = com.liam.joshymc.recipe.CustomRecipes(plugin)
        customRecipes.registerAll()
    }

    fun clear() {
        for (key in registeredKeys) {
            plugin.server.removeRecipe(key)
        }
        registeredKeys.clear()
    }
}
