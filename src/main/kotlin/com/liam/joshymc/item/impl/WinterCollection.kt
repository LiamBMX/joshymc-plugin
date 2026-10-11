package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── Winter Collection ───────────────────────────────────────────────────────
// Retextured vanilla gear with normal vanilla behavior. No custom mechanics.

class Frostbite : CustomItem() {

    override val id = "frostbite"
    override val material = Material.NETHERITE_SWORD
    override val hasGlint = true

    override val displayName: Component = Component.text("Frostbite", TextColor.color(0x7FDBFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "frostbite"))
    }
}

class GlacierBreaker : CustomItem() {

    override val id = "glacier_breaker"
    override val material = Material.NETHERITE_PICKAXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Glacier Breaker", TextColor.color(0x9FE3F5))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "glacier_breaker"))
    }
}

class WingsOfTheBlizzard : CustomItem() {

    override val id = "wings_of_the_blizzard"
    override val material = Material.ELYTRA
    override val hasGlint = true

    override val displayName: Component = Component.text("Wings of the Blizzard", TextColor.color(0xB8E8FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "wings_of_the_blizzard"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.CHEST
        equippable.model = NamespacedKey(Joshymc.instance, "wings_of_the_blizzard")
        meta.setEquippable(equippable)
    }
}

class WintersWrath : CustomItem() {

    override val id = "winters_wrath"
    override val material = Material.BOW
    override val hasGlint = true

    override val displayName: Component = Component.text("Winter's Wrath", TextColor.color(0x5BC0EB))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "winters_wrath"))
    }
}

// ── Snowveil set ────────────────────────────────────────────────────────────
// The Winter Collection's armor set (issue #1098). Plain Netherite underneath: vanilla
// stats, no attributes or abilities, unbreakable like the rest of the collection. The
// boots are the old Ice Skates, renamed: their id stays "ice_skates" so existing copies
// keep working (UnbreakableArmorUpgradeListener renames copies still called Ice Skates).

private val SNOWVEIL_BLUE = TextColor.color(0xCFEBFF)

abstract class SnowveilPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val hasGlint = true

    override val displayName: Component = Component.text(name, SNOWVEIL_BLUE)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        val equippable = meta.equippable
        equippable.slot = slot
        // Helmet: no equipment asset, so the client draws the 3D item model on the head.
        // Other pieces: worn layer equipment/<id>.json (humanoid or humanoid_leggings).
        equippable.model = if (slot == EquipmentSlot.HEAD) null else NamespacedKey(Joshymc.instance, id)
        meta.setEquippable(equippable)
    }
}

class SnowveilHood : SnowveilPiece("snowveil_hood", Material.NETHERITE_HELMET, "Snowveil Hood", EquipmentSlot.HEAD)

class SnowveilGuard : SnowveilPiece("snowveil_guard", Material.NETHERITE_CHESTPLATE, "Snowveil Guard", EquipmentSlot.CHEST)

class SnowveilLeggings : SnowveilPiece("snowveil_leggings", Material.NETHERITE_LEGGINGS, "Snowveil Leggings", EquipmentSlot.LEGS)

class SnowveilSkates : SnowveilPiece("ice_skates", Material.NETHERITE_BOOTS, "Snowveil Skates", EquipmentSlot.FEET)
