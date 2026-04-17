package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareItemCraftEvent

class RecipeBlockerListener(private val plugin: Joshymc) : Listener {

    private var blockedMaterials: Set<Material> = emptySet()

    init {
        loadConfig()
    }

    fun loadConfig() {
        val list = plugin.config.getStringList("recipe-blocker.blocked-items")
        blockedMaterials = list.mapNotNull { name ->
            try {
                Material.valueOf(name.uppercase())
            } catch (_: IllegalArgumentException) {
                plugin.logger.warning("[RecipeBlocker] Unknown material: $name")
                null
            }
        }.toSet()

        if (blockedMaterials.isNotEmpty()) {
            plugin.logger.info("[RecipeBlocker] Blocking ${blockedMaterials.size} recipe(s).")
        }
    }

    @EventHandler
    fun onCraft(event: PrepareItemCraftEvent) {
        val result = event.inventory.result ?: return
        if (result.type !in blockedMaterials) return

        // Allow bypass with permission
        val player = event.view.player
        if (player.hasPermission("joshymc.recipeblocker.bypass")) return

        event.inventory.result = null
    }
}
