package com.liam.joshymc.recipe

import com.liam.joshymc.Joshymc

class CustomRecipes(private val plugin: Joshymc) {

    fun registerAll() {
        plugin.logger.info("Registered 0 custom recipe(s).")
    }

    fun clear() {}
}
