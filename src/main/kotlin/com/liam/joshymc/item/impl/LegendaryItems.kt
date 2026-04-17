package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta

// ── Legendary Equipment ─────────────────────────────────────────────────────

class BlazeKingsCrown : CustomItem() {

    override val id = "blaze_kings_crown"
    override val material = Material.GOLDEN_HELMET
    override val hasGlint = true

    override val displayName: Component = Component.text("Blaze King's Crown", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Helmet",
        description = listOf(
            "Fire Immunity",
            "+20% damage to Nether mobs",
            "A crown forged in the heart of a blaze spawner",
        ),
        usage = "Equip to activate.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.PROTECTION, 4, true)
        meta.addEnchant(Enchantment.FIRE_PROTECTION, 4, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "blaze_kings_crown"))
    }
}

class PhantomCloak : CustomItem() {

    override val id = "phantom_cloak"
    override val material = Material.LEATHER_CHESTPLATE
    override val hasGlint = true

    override val displayName: Component = Component.text("Phantom Cloak", TextColor.color(0x555555))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Chestplate",
        description = listOf(
            "10s invisibility on sneak (30s cooldown)",
            "+10% movement speed",
            "Woven from phantom membranes",
        ),
        usage = "Equip to activate.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.PROTECTION, 4, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        if (meta is LeatherArmorMeta) {
            meta.setColor(Color.fromRGB(30, 30, 40))
        }
        meta.setItemModel(NamespacedKey(Joshymc.instance, "phantom_cloak"))
    }
}

class PoseidonsTrident : CustomItem() {

    override val id = "poseidons_trident"
    override val material = Material.TRIDENT
    override val hasGlint = true

    override val displayName: Component = Component.text("Poseidon's Trident", TextColor.color(0x5555FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Weapon",
        description = listOf(
            "Riptide III + Loyalty III + Channeling",
            "The weapon of the sea god",
            "Unbreakable",
        ),
        usage = "Throw or melee.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.RIPTIDE, 3, true)
        meta.addEnchant(Enchantment.LOYALTY, 3, true)
        meta.addEnchant(Enchantment.CHANNELING, 1, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.addEnchant(Enchantment.IMPALING, 5, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "poseidons_trident"))
    }
}

// ── Tokens & Skill Tomes ────────────────────────────────────────────────────

class ClaimBlockToken : CustomItem() {

    override val id = "claim_block_token"
    override val material = Material.MAP

    override val displayName: Component = Component.text("Claim Block Token", TextColor.color(0x55FF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Token",
        description = listOf("Right-click to add 100 claim blocks"),
        usage = "Right-click to redeem",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "claim_block_token"))
    }
}

class SkillTomeMining : CustomItem() {

    override val id = "skill_tome_mining"
    override val material = Material.BOOK
    override val hasGlint = true

    override val displayName: Component = Component.text("Mining Skill Tome", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Skill Tome",
        description = listOf("Grants 500 Mining XP"),
        usage = "Right-click to use",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "skill_tome_mining"))
    }
}

class SkillTomeFarming : CustomItem() {

    override val id = "skill_tome_farming"
    override val material = Material.BOOK
    override val hasGlint = true

    override val displayName: Component = Component.text("Farming Skill Tome", TextColor.color(0x55FF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Skill Tome",
        description = listOf("Grants 500 Farming XP"),
        usage = "Right-click to use",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "skill_tome_farming"))
    }
}
