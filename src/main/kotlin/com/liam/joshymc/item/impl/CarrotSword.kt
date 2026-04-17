package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.EquipmentSlotGroup
import org.bukkit.inventory.meta.ItemMeta

class CarrotSword : CustomItem() {

    override val id = "carrot_sword"
    override val material = Material.NETHERITE_SWORD
    override val hasGlint = true

    override val displayName: Component = Component.text("Carrot Sword", TextColor.color(0xFF8C00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Weapon",
        description = listOf(
            "A legendary blade forged from carrots.",
            "11 Attack Damage | Fire Aspect IV",
        ),
        usage = "Swing to use.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "carrot_sword"))

        // Remove default attack damage, set custom 11
        meta.addAttributeModifier(
            Attribute.ATTACK_DAMAGE,
            AttributeModifier(
                NamespacedKey(Joshymc.instance, "carrot_sword_damage"),
                11.0,
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND,
            )
        )

        // Keep a reasonable attack speed
        meta.addAttributeModifier(
            Attribute.ATTACK_SPEED,
            AttributeModifier(
                NamespacedKey(Joshymc.instance, "carrot_sword_speed"),
                -2.4, // same as netherite sword default
                AttributeModifier.Operation.ADD_NUMBER,
                EquipmentSlotGroup.MAINHAND,
            )
        )

        // Fire Aspect IV
        meta.addEnchant(Enchantment.FIRE_ASPECT, 4, true)

        // Unbreakable
        meta.isUnbreakable = true
    }
}
