package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.item.FurnitureItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.inventory.meta.ItemMeta

// ── Treasure decorations (issue #1286) ──────────────────────────────────────
// Placeable furniture, see FurnitureListener. The base item is a CAKE so placing it fires
// the vanilla BlockPlaceEvent (claims and WorldGuard decide where it may go, and vanilla
// only allows it on a solid block) and because nothing crafts, smelts or trades with
// cake: the decorative gold and gems can never be turned into real gold, diamonds or
// emeralds. It is never placed as a real cake.

abstract class TreasureDecoration(
    override val id: String,
    name: String,
    color: Int,
    description: List<String>,
    override val hitboxWidth: Float,
    override val hitboxHeight: Float,
    override val placeSound: Sound = Sound.BLOCK_CHAIN_PLACE,
    override val breakSound: Sound = Sound.BLOCK_CHAIN_BREAK,
) : CustomItem(), FurnitureItem {

    override val material = Material.CAKE

    override val displayName: Component = Component.text(name, TextColor.color(color))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Decoration",
        description = description,
        usage = "Place it down. Sneak + right-click to turn it, punch to pick it up.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        meta.setMaxStackSize(16)
    }
}

class SmallGoldPile : TreasureDecoration(
    "small_gold_pile", "Small Gold Pile", 0xFFD34A,
    listOf("A small pile of gold coins."),
    hitboxWidth = 0.6f, hitboxHeight = 0.35f,
)

class LargeGoldPile : TreasureDecoration(
    "large_gold_pile", "Large Gold Pile", 0xFFC21F,
    listOf("A big heap of stacked gold coins."),
    hitboxWidth = 0.9f, hitboxHeight = 0.6f,
)

class GemstonePile : TreasureDecoration(
    "gemstone_pile", "Gemstone Pile", 0x5FD8F0,
    listOf("Diamonds, emeralds, rubies and", "sapphires on a velvet cloth."),
    hitboxWidth = 0.8f, hitboxHeight = 0.35f,
    placeSound = Sound.BLOCK_AMETHYST_CLUSTER_PLACE, breakSound = Sound.BLOCK_AMETHYST_CLUSTER_BREAK,
)

class MixedTreasurePile : TreasureDecoration(
    "mixed_treasure_pile", "Mixed Treasure Pile", 0xF2A93B,
    listOf("Gold coins, gemstones, pearls", "and a jewelled goblet."),
    hitboxWidth = 0.8f, hitboxHeight = 0.45f,
)

class OverflowingTreasureChest : TreasureDecoration(
    "overflowing_treasure_chest", "Overflowing Treasure Chest", 0xE0902A,
    listOf("An open chest spilling over with", "gold coins and gemstones.", "Decoration only, it holds no items."),
    hitboxWidth = 0.9f, hitboxHeight = 0.8f,
    placeSound = Sound.BLOCK_WOOD_PLACE, breakSound = Sound.BLOCK_WOOD_BREAK,
)
