package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

// ── Peppermint Pickaxe ──────────────────────────────────────────────────────
// Survival-only Christmas pickaxe (issue #1399). A plain vanilla Netherite Pickaxe
// underneath: Netherite mining speed and durability, normal enchanting, anvil
// combining/renaming and Netherite Ingot repair, no abilities. Only the look is custom
// (resourcepack/art/items/peppermint_pickaxe.py, item model joshymc:peppermint_pickaxe).
// Given out only by admins with /joshymc give (joshymc.give, op, audited as ITEM_GIVE).

class PeppermintPickaxe : CustomItem() {

    override val id = "peppermint_pickaxe"
    override val material = Material.NETHERITE_PICKAXE

    override val displayName: Component = candyStripe("Peppermint Pickaxe")

    override val lore = LoreBuilder.build(
        type = "Pickaxe",
        description = listOf(
            "A sweet way to mine through the holidays!",
            "Christmas 2026 | JoshyMC",
        ),
        usage = "Mines like a Netherite Pickaxe.",
    )

    override val gamemodeScope = GamemodeScope.SURVIVAL

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
    }

    private companion object {
        val RED = TextColor.color(0xE8243C)
        val WHITE = TextColor.color(0xFFF4F4)

        /** Bold name with candy-cane letters: red and white, alternating per letter. */
        fun candyStripe(text: String): Component {
            var out = Component.empty()
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
            var i = 0
            for (ch in text) {
                if (ch == ' ') {
                    out = out.append(Component.text(" "))
                    continue
                }
                out = out.append(Component.text(ch.toString(), if (i % 2 == 0) RED else WHITE))
                i++
            }
            return out
        }
    }
}
