package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

/** The five Link Pop colours (issue #1207). [key] is the suffix of the item id and the config section. */
enum class LinkPopColor(val key: String, val label: String, val rgb: Int, val flavor: String) {
    RED("red", "Red", 0xE32B36, "Cherry flavored."),
    BLUE("blue", "Blue", 0x2A85F2, "Blue raspberry flavored."),
    GREEN("green", "Green", 0x33BB45, "Green apple flavored."),
    PURPLE("purple", "Purple", 0x9A47D1, "Grape flavored."),
    ORANGE("orange", "Orange", 0xF88022, "Orange flavored.");

    val itemId: String get() = "link_pop_$key"

    companion object {
        private val byItemId = entries.associateBy { it.itemId }

        fun fromItemId(id: String?): LinkPopColor? = id?.let { byItemId[it] }
    }
}

/**
 * Link Pop (issue #1207): a candy jewel on a little plastic ring, one custom item per colour,
 * each a retextured Cookie that eats exactly like one. Every colour has its own id and item
 * model, so different colours never stack together. Eating one also gives a short hidden
 * effect (LinkPopListener). The name and lore must never mention or hint at that effect.
 */
class LinkPop(val color: LinkPopColor) : CustomItem() {

    override val id = color.itemId
    override val material = Material.COOKIE

    override val displayName: Component = Component.text("${color.label} Link Pop", TextColor.color(color.rgb))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Food",
        description = listOf("A colorful candy jewel", "on a little plastic ring.", color.flavor),
        usage = "Right-click to eat.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
    }
}
