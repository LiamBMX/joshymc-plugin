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

/**
 * Joshy's Bar (issue #1204, renamed from "Joshees Bar" in issue #1226): a wrapped chocolate
 * candy bar, a retextured Cookie that eats exactly like one. The id stays `joshees_bar` so
 * copies handed out before the rename, Candy Shop entries and the resource pack keep working;
 * UnbreakableArmorUpgradeListener renames old copies in place. Eating it also gives a short hidden effect (JosheesBarListener). The name
 * and lore must never mention or hint at that effect: it's meant to be a surprise.
 */
class JosheesBar : CustomItem() {

    companion object {
        const val ID = "joshees_bar"
    }

    override val id = ID
    override val material = Material.COOKIE
    override val isCandy = true

    override val displayName: Component = Component.text("Joshy's Bar", TextColor.color(0x3560C6))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Food",
        description = listOf("A delicious chocolate bar packed", "with caramel, peanuts, and nougat."),
        usage = "Right-click to eat.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
    }
}
