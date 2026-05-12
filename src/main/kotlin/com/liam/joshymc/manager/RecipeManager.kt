package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.NamespacedKey

class RecipeManager(private val plugin: Joshymc) {

    private var customRecipes = com.liam.joshymc.recipe.CustomRecipes(plugin)

    fun registerAll() {
        customRecipes = com.liam.joshymc.recipe.CustomRecipes(plugin)
        customRecipes.registerAll()
    }

    fun clear() {
        customRecipes.clear()
    }

    fun getCustomIngredients(key: NamespacedKey): Map<Material, String>? =
        customRecipes.getCustomIngredients(key)
}
