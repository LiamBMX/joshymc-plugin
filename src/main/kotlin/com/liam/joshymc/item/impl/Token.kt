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

class Token : CustomItem() {

    override val id = "token"
    override val material = Material.RESIN_CLUMP
    override val hasGlint = true

    override val displayName: Component = Component.text("Token", TextColor.color(0xFFD700))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Currency",
        description = listOf(
            "A rare server token.",
            "Buy or sell for \$100,000 each.",
        ),
        usage = "/tokens buy|sell <amount>",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "token"))
        meta.addEnchant(Enchantment.MENDING, 10, true)
    }
}
