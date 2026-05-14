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
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

class BubbleButtLeggings : CustomItem() {
    override val id = "bubble_butt_leggings"
    override val material = Material.DIAMOND_LEGGINGS
    override val hasGlint = true
    override val displayName: Component = Component.text("Bubble Butt Leggings", TextColor.color(0xADD8E6))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Leggings",
        description = listOf(
            "Crouch to deploy a protective bubble.",
            "Lasts 15s | 90s cooldown.",
            "Others can't enter. Walk out to pop it.",
        ),
        usage = "Wear and crouch to activate.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "bubble_butt_leggings"))
        meta.addEnchant(Enchantment.PROTECTION, 3, true)
        meta.isUnbreakable = true
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.LEGS
        equippable.model = NamespacedKey(Joshymc.instance, "bubble_butt")
        meta.setEquippable(equippable)
    }
}
