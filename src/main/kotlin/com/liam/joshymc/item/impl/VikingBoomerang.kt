package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.enchant.CustomEnchantManager
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/**
 * Viking Boomerang (issue #1218): a carved wooden boomerang on a Trident base that throws,
 * hits and returns like any trident. It always carries a real Loyalty III, hidden from the
 * tooltip: players should find out it comes back by throwing it. The name and lore must
 * never mention that. In flight ThrownTridentVisualListener swaps the stock trident model
 * for the spinning boomerang.
 *
 * Hiding the enchantments component hides every vanilla enchant, so any other vanilla
 * enchant (Unbreaking, Mending, Impaling...) is listed in the lore instead, the way the
 * tooltip would show it. [normalize] keeps that list and Loyalty III in step after anvils.
 */
class VikingBoomerang : CustomItem() {

    companion object {
        const val ID = "viking_boomerang"
        const val LOYALTY_LEVEL = 3

        /** Starts the lore lines that stand in for the hidden vanilla enchant tooltip. */
        private const val ENCHANT_LINE_MARKER = "⁠"

        private val plain = PlainTextComponentSerializer.plainText()

        /**
         * Puts back exactly Loyalty III, keeps the enchant tooltip hidden and rebuilds the
         * listed vanilla enchants. Returns true when the stack's meta changed.
         */
        fun normalize(item: ItemStack): Boolean {
            val meta = item.itemMeta ?: return false
            val before = meta.clone()
            if (meta.getEnchantLevel(Enchantment.LOYALTY) != LOYALTY_LEVEL) {
                meta.removeEnchant(Enchantment.LOYALTY)
                meta.addEnchant(Enchantment.LOYALTY, LOYALTY_LEVEL, true)
            }
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)

            val shown = meta.enchants
                .filterKeys { it != Enchantment.LOYALTY }
                .entries.sortedBy { it.key.key.key }
                .map { (enchant, level) ->
                    Component.text(ENCHANT_LINE_MARKER).append(enchant.displayName(level))
                        .decoration(TextDecoration.ITALIC, false)
                }
            val lore = meta.lore().orEmpty()
            val customLines = lore.filter { plain.serialize(it).startsWith(CustomEnchantManager.LORE_MARKER) }
            val rest = lore.filter {
                val text = plain.serialize(it)
                !text.startsWith(CustomEnchantManager.LORE_MARKER) && !text.startsWith(ENCHANT_LINE_MARKER)
            }
            meta.lore(customLines + shown + rest)
            // Wood doesn't shimmer: the glint only shows once it carries an enchant you can see.
            meta.setEnchantmentGlintOverride(shown.isNotEmpty() || customLines.isNotEmpty())

            if (meta == before) return false
            item.itemMeta = meta
            return true
        }
    }

    override val id = ID
    override val material = Material.TRIDENT

    override val displayName: Component = Component.text("Viking Boomerang", TextColor.color(0xB8844C))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Weapon",
        description = listOf("Carved from ancient northern timber,", "and shaped for the fiercest warriors."),
        usage = "Hold right-click, then release to throw.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
        meta.addEnchant(Enchantment.LOYALTY, LOYALTY_LEVEL, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        meta.setEnchantmentGlintOverride(false)
    }
}
