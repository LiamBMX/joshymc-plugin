package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.ItemMeta

class BunnyBoots : CustomItem() {
    override val id = "bunny_boots"
    override val material = Material.DIAMOND_BOOTS
    override val hasGlint = true
    override val displayName: Component = Component.text("Bunny Boots", TextColor.color(0xFFB6C1))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Armor",
        description = listOf("Part of the Bunny Armor set.", "Protection V | Projectile Protection V"),
        usage = "Equip to wear.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "bunny_boots"))
        meta.addEnchant(Enchantment.PROTECTION, 5, true)
        meta.addEnchant(Enchantment.PROJECTILE_PROTECTION, 5, true)
        meta.isUnbreakable = true

        val equippable = meta.equippable
        equippable.slot = org.bukkit.inventory.EquipmentSlot.FEET
        equippable.model = NamespacedKey(Joshymc.instance, "bunny")
        meta.setEquippable(equippable)
    }
}
