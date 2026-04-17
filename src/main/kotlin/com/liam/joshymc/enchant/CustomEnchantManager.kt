package com.liam.joshymc.enchant

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.GrindstoneInventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType

class CustomEnchantManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val PDC_PREFIX = "enchant_"
        private const val LORE_MARKER = "\u200B" // Zero-width space to identify our lore lines

        private val ROMAN_NUMERALS = arrayOf(
            "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
        )

        fun toRoman(level: Int): String {
            return if (level in 1..10) ROMAN_NUMERALS[level] else level.toString()
        }
    }

    private val enchants = mutableMapOf<String, CustomEnchant>()

    // ── Lifecycle ────────────────────────────────────────

    fun start() {
        plugin.logger.info("[Enchants] CustomEnchantManager started (${enchants.size} enchant(s) registered).")
    }

    // ── Registration ────────────────────────────────────

    fun register(enchant: CustomEnchant) {
        enchants[enchant.id] = enchant
    }

    fun getEnchant(id: String): CustomEnchant? = enchants[id]

    fun getAllEnchants(): Collection<CustomEnchant> = enchants.values

    fun getEnchantIds(): Set<String> = enchants.keys

    // ── PDC Keys ────────────────────────────────────────

    private fun keyFor(enchantId: String): NamespacedKey {
        return NamespacedKey(plugin, "$PDC_PREFIX$enchantId")
    }

    // ── Read / Write Enchants on Items ──────────────────

    /**
     * Get the level of a custom enchant on an item. Returns 0 if not present.
     */
    fun getLevel(item: ItemStack, enchantId: String): Int {
        val meta = item.itemMeta ?: return 0
        return meta.persistentDataContainer.getOrDefault(keyFor(enchantId), PersistentDataType.INTEGER, 0)
    }

    /**
     * Check if an item has a specific custom enchant.
     */
    fun hasEnchant(item: ItemStack, enchantId: String): Boolean {
        return getLevel(item, enchantId) > 0
    }

    /**
     * Get all custom enchants on an item as a map of ID -> level.
     */
    fun getEnchants(item: ItemStack): Map<String, Int> {
        val meta = item.itemMeta ?: return emptyMap()
        val result = mutableMapOf<String, Int>()
        for (id in enchants.keys) {
            val level = meta.persistentDataContainer.getOrDefault(keyFor(id), PersistentDataType.INTEGER, 0)
            if (level > 0) {
                result[id] = level
            }
        }
        return result
    }

    /**
     * Apply a custom enchant to an item at the given level.
     * Returns false if the enchant can't be applied (wrong target, conflict, max level exceeded).
     */
    fun applyEnchant(item: ItemStack, enchantId: String, level: Int): Boolean {
        val enchant = enchants[enchantId] ?: return false

        // Validate target
        if (!enchant.target.canApplyTo(item)) return false

        // Validate level
        if (level < 1 || level > enchant.maxLevel) return false

        // Check conflicts
        val existing = getEnchants(item)
        for (conflictId in enchant.conflicts) {
            if (existing.containsKey(conflictId)) return false
        }

        // Also check if existing enchants conflict with this one
        for ((existingId, _) in existing) {
            val existingEnchant = enchants[existingId] ?: continue
            if (enchantId in existingEnchant.conflicts) return false
        }

        // Apply to PDC
        val meta = item.itemMeta ?: return false
        meta.persistentDataContainer.set(keyFor(enchantId), PersistentDataType.INTEGER, level)
        item.itemMeta = meta

        // Update lore
        updateLore(item)
        return true
    }

    /**
     * Remove a custom enchant from an item.
     */
    fun removeEnchant(item: ItemStack, enchantId: String) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.remove(keyFor(enchantId))
        item.itemMeta = meta
        updateLore(item)
    }

    /**
     * Remove all custom enchants from an item.
     */
    fun removeAllEnchants(item: ItemStack) {
        val meta = item.itemMeta ?: return
        for (id in enchants.keys) {
            meta.persistentDataContainer.remove(keyFor(id))
        }
        item.itemMeta = meta
        updateLore(item)
    }

    // ── Lore Rendering ──────────────────────────────────

    /**
     * Rebuild the custom enchant lore lines on an item.
     * Vanilla enchants show in aqua (NamedTextColor.GRAY for vanilla).
     * We use the same aqua style to match vanilla feel.
     */
    fun updateLore(item: ItemStack) {
        val meta = item.itemMeta ?: return
        val existing = meta.lore() ?: mutableListOf()

        // Remove our old enchant lore lines (identified by zero-width space marker)
        val cleaned = existing.filter { line ->
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line)
            !plain.startsWith(LORE_MARKER)
        }.toMutableList()

        // Build new enchant lore lines
        val enchantLines = mutableListOf<Component>()
        val itemEnchants = getEnchants(item)
        for ((id, level) in itemEnchants) {
            val enchant = enchants[id] ?: continue
            val levelText = if (enchant.maxLevel == 1) "" else " ${toRoman(level)}"
            enchantLines.add(
                Component.text("$LORE_MARKER${enchant.displayName}$levelText", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }

        // Prepend enchant lines before other lore
        val newLore = mutableListOf<Component>()
        newLore.addAll(enchantLines)
        newLore.addAll(cleaned)

        meta.lore(newLore)

        // Add enchant glint if item has any custom enchants
        if (itemEnchants.isNotEmpty() && !meta.hasEnchants()) {
            meta.setEnchantmentGlintOverride(true)
        } else if (itemEnchants.isEmpty() && !meta.hasEnchants()) {
            meta.setEnchantmentGlintOverride(null) // Reset to default
        }

        item.itemMeta = meta
    }

    // ── Anvil Combining ─────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onAnvil(event: PrepareAnvilEvent) {
        val inv = event.inventory
        val left = inv.getItem(0) ?: return
        val right = inv.getItem(1) ?: return

        val leftEnchants = getEnchants(left)
        val rightEnchants = getEnchants(right)

        // If neither has custom enchants, let vanilla handle it
        if (leftEnchants.isEmpty() && rightEnchants.isEmpty()) return

        // Start with vanilla result or clone left item
        val result = (event.result?.clone() ?: left.clone())
        var changed = false

        // Merge right enchants into result
        for ((id, rightLevel) in rightEnchants) {
            val enchant = enchants[id] ?: continue

            // Check if enchant can apply to this item type
            if (!enchant.target.canApplyTo(left)) continue

            val leftLevel = leftEnchants[id] ?: 0

            // Check conflicts with existing enchants on left
            val resultEnchants = getEnchants(result)
            val hasConflict = enchant.conflicts.any { resultEnchants.containsKey(it) }
                    || resultEnchants.any { (eId, _) -> enchants[eId]?.conflicts?.contains(id) == true }
            if (hasConflict) continue

            val newLevel = if (leftLevel == rightLevel) {
                // Same level = combine up (like vanilla)
                minOf(leftLevel + 1, enchant.maxLevel)
            } else {
                // Different levels = take higher
                maxOf(leftLevel, rightLevel)
            }

            val meta = result.itemMeta ?: continue
            meta.persistentDataContainer.set(keyFor(id), PersistentDataType.INTEGER, newLevel)
            result.itemMeta = meta
            changed = true
        }

        // Carry over left enchants that aren't on the result yet
        for ((id, level) in leftEnchants) {
            if (!getEnchants(result).containsKey(id)) {
                val meta = result.itemMeta ?: continue
                meta.persistentDataContainer.set(keyFor(id), PersistentDataType.INTEGER, level)
                result.itemMeta = meta
                changed = true
            }
        }

        if (changed) {
            updateLore(result)
            event.result = result
        }
    }

    // ── Grindstone Removal ──────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onGrindstone(event: PrepareGrindstoneEvent) {
        val result = event.result ?: return

        // If the input items had custom enchants, strip them from the result
        val inv = event.inventory
        val top = inv.getItem(0)
        val bottom = inv.getItem(1)

        val hasCustom = (top != null && getEnchants(top).isNotEmpty())
                || (bottom != null && getEnchants(bottom).isNotEmpty())

        if (hasCustom) {
            removeAllEnchants(result)
            event.result = result
        }
    }

    // ── Utility ─────────────────────────────────────────

    /**
     * Get all enchants that can be applied to a given item.
     */
    fun getApplicableEnchants(item: ItemStack): List<CustomEnchant> {
        return enchants.values.filter { it.target.canApplyTo(item) }
    }

    /**
     * Create a formatted component showing the enchant name and level.
     */
    fun formatEnchant(enchantId: String, level: Int): Component {
        val enchant = enchants[enchantId] ?: return Component.empty()
        val levelText = if (enchant.maxLevel == 1) "" else " ${toRoman(level)}"
        return Component.text("${enchant.displayName}$levelText", NamedTextColor.GRAY)
            .decoration(TextDecoration.ITALIC, false)
    }
}
