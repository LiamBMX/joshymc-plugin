package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

/**
 * Seasonal limited-edition items. Each one's 3D model and textures are built from
 * resourcepack/art/items/<id>.py, and the item points at joshymc:<id>.
 */
class LimitedEditionItem(
    override val id: String,
    name: String,
    color: TextColor,
    override val material: Material,
    type: String,
    blurb: String,
    usage: String,
    private val worn: Worn = Worn.NONE,
) : CustomItem() {

    enum class Worn {
        NONE,

        /** The 3D model itself sits on the head (no armor texture). */
        HEAD_MODEL,

        /** A painted worn look from assets/joshymc/equipment/<id>.json (boots, elytra wings). */
        EQUIPMENT,
    }

    override val displayName: Component = Component.text(name, color)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(type = type, description = listOf(blurb), usage = usage)

    override fun applyMeta(meta: ItemMeta) {
        val key = NamespacedKey(Joshymc.instance, id)
        meta.setItemModel(key)
        // Keep the enchantment shimmer off so the painted model reads cleanly.
        meta.setEnchantmentGlintOverride(false)
        when (worn) {
            Worn.HEAD_MODEL -> {
                // No equipment asset: the client then draws the item model on the head.
                val equippable = meta.equippable
                equippable.slot = EquipmentSlot.HEAD
                equippable.model = null
                meta.setEquippable(equippable)
            }
            Worn.EQUIPMENT -> {
                val equippable = meta.equippable
                equippable.model = key
                meta.setEquippable(equippable)
            }
            Worn.NONE -> {}
        }
    }
}

object LimitedEditionItems {

    private val SEPTEMBER = TextColor.color(0xFFAA00)
    private val HALLOWEEN = TextColor.color(0xFF7A1A)
    private val WOODLAND = TextColor.color(0xD08A45)
    private val WINTER = TextColor.color(0x7FDBFF)

    private const val SEPTEMBER_BLURB = "Exclusive to September."
    private const val LIMITED_BLURB = "Limited edition."

    fun all(): List<CustomItem> = listOf(
        // September exclusives
        LimitedEditionItem("autumns_edge", "Autumn's Edge", SEPTEMBER, Material.NETHERITE_SWORD,
            "Exclusive Sword", SEPTEMBER_BLURB, "Swing to use."),
        LimitedEditionItem("harvest_scythe", "Harvest Scythe", SEPTEMBER, Material.NETHERITE_HOE,
            "Exclusive Scythe", SEPTEMBER_BLURB, "Swing to use."),
        LimitedEditionItem("orchard_pickaxe", "Orchard Pickaxe", SEPTEMBER, Material.NETHERITE_PICKAXE,
            "Exclusive Pickaxe", SEPTEMBER_BLURB, "Mine to use."),
        LimitedEditionItem("golden_crest", "Golden Crest", SEPTEMBER, Material.NETHERITE_HELMET,
            "Exclusive Helmet", SEPTEMBER_BLURB, "Equip to wear.", LimitedEditionItem.Worn.HEAD_MODEL),
        LimitedEditionItem("falling_leaf", "Falling Leaf", SEPTEMBER, Material.ELYTRA,
            "Exclusive Elytra", SEPTEMBER_BLURB, "Equip to glide.", LimitedEditionItem.Worn.EQUIPMENT),

        // Halloween
        LimitedEditionItem("phantoms_grasp", "Phantom's Grasp", HALLOWEEN, Material.BOW,
            "Limited Edition Bow", LIMITED_BLURB, "Draw and release to shoot."),
        LimitedEditionItem("gravedigger", "Gravedigger", HALLOWEEN, Material.NETHERITE_SHOVEL,
            "Limited Edition Shovel", LIMITED_BLURB, "Dig to use."),
        LimitedEditionItem("jack_o_lantern_mask", "Jack-o'-Lantern Mask", HALLOWEEN, Material.NETHERITE_HELMET,
            "Limited Edition Helmet", LIMITED_BLURB, "Equip to wear.", LimitedEditionItem.Worn.HEAD_MODEL),
        LimitedEditionItem("bone_rattler", "Bone Rattler", HALLOWEEN, Material.MACE,
            "Limited Edition Mace", LIMITED_BLURB, "Swing to use."),
        LimitedEditionItem("pumpkin_pummel", "Pumpkin Pummel", HALLOWEEN, Material.PUMPKIN_PIE,
            "Limited Edition Pie", LIMITED_BLURB, "Eat to use."),

        // Woodland
        LimitedEditionItem("lumberjacks_legacy", "Lumberjack's Legacy", WOODLAND, Material.NETHERITE_AXE,
            "Limited Edition Axe", LIMITED_BLURB, "Swing to use."),
        LimitedEditionItem("woodland_hunter", "Woodland Hunter", WOODLAND, Material.CROSSBOW,
            "Limited Edition Crossbow", LIMITED_BLURB, "Load and fire."),
        LimitedEditionItem("maplefang", "Maplefang", WOODLAND, Material.TRIDENT,
            "Limited Edition Trident", LIMITED_BLURB, "Throw or melee."),
        LimitedEditionItem("autumn_wanderer", "Autumn Wanderer", WOODLAND, Material.NETHERITE_BOOTS,
            "Limited Edition Boots", LIMITED_BLURB, "Equip to wear.", LimitedEditionItem.Worn.EQUIPMENT),
        LimitedEditionItem("hearthkeeper", "Hearthkeeper", WOODLAND, Material.SHIELD,
            "Limited Edition Shield", LIMITED_BLURB, "Right-click to block."),

        // Winter
        LimitedEditionItem("frostbite", "Frostbite", WINTER, Material.NETHERITE_SWORD,
            "Limited Edition Sword", LIMITED_BLURB, "Swing to use."),
        LimitedEditionItem("glacier_breaker", "Glacier Breaker", WINTER, Material.NETHERITE_PICKAXE,
            "Limited Edition Pickaxe", LIMITED_BLURB, "Mine to use."),
        LimitedEditionItem("ice_skates", "Ice Skates", WINTER, Material.NETHERITE_BOOTS,
            "Limited Edition Boots", LIMITED_BLURB, "Equip to wear.", LimitedEditionItem.Worn.EQUIPMENT),
        LimitedEditionItem("wings_of_the_blizzard", "Wings of the Blizzard", WINTER, Material.ELYTRA,
            "Limited Edition Elytra", LIMITED_BLURB, "Equip to glide.", LimitedEditionItem.Worn.EQUIPMENT),
        LimitedEditionItem("winters_wrath", "Winter's Wrath", WINTER, Material.BOW,
            "Limited Edition Bow", LIMITED_BLURB, "Draw and release to shoot."),
    )
}
