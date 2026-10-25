package com.liam.joshymc.item

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * Base class for all custom items in the plugin.
 * Subclasses define their material, display name, lore, and any special behavior.
 */
abstract class CustomItem {

    /** Unique string identifier for this item (e.g., "void_drill") */
    abstract val id: String

    /** The base material for this custom item */
    abstract val material: Material

    /** Display name as an Adventure Component */
    abstract val displayName: Component

    /** Lore lines as Adventure Components */
    abstract val lore: List<Component>

    /** Whether this item should have an enchantment glint */
    open val hasGlint: Boolean = false

    /** Whether this item is a candy the Candy Shop may sell (issue #1211). */
    open val isCandy: Boolean = false

    /**
     * The gamemode this item belongs to (issue #1289), or null for items every gamemode
     * shares. `/joshymc give` refuses to hand a scoped item out where its gamemode is
     * locked away (`gamemodeLockManager.isAllowed(scope, world)`).
     */
    open val gamemodeScope: GamemodeScope? = null

    /**
     * Builds the final ItemStack with PDC tag, display name, lore, and glint.
     */
    fun createItemStack(amount: Int = 1): ItemStack {
        val item = ItemStack(material, amount)
        val meta = item.itemMeta ?: return item

        meta.displayName(displayName)
        meta.lore(lore)

        // Tag the item so we can identify it later
        val key = NamespacedKey(Joshymc.instance, "custom_item_id")
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, id)

        if (hasGlint) {
            meta.setEnchantmentGlintOverride(true)
        }

        // Allow subclasses to customize meta further
        applyMeta(meta)

        item.itemMeta = meta
        return item
    }

    /**
     * Override to apply additional meta modifications (enchantments, attributes, etc.)
     */
    open fun applyMeta(meta: org.bukkit.inventory.meta.ItemMeta) {}
}
