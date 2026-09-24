package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent

/**
 * Lets players with joshymc.rename.colors use &-color codes, format codes (&l, &o, etc.)
 * and &#RRGGBB hex colors when renaming items in an anvil. Decorations persist across
 * color changes and are only cleared by &r, so a leading &l stays active through a
 * per-character hex gradient without needing to be repeated.
 */
class ItemRenameListener(private val plugin: Joshymc) : Listener {

    companion object {
        private val LEGACY_COLORS = mapOf(
            '0' to NamedTextColor.BLACK, '1' to NamedTextColor.DARK_BLUE,
            '2' to NamedTextColor.DARK_GREEN, '3' to NamedTextColor.DARK_AQUA,
            '4' to NamedTextColor.DARK_RED, '5' to NamedTextColor.DARK_PURPLE,
            '6' to NamedTextColor.GOLD, '7' to NamedTextColor.GRAY,
            '8' to NamedTextColor.DARK_GRAY, '9' to NamedTextColor.BLUE,
            'a' to NamedTextColor.GREEN, 'b' to NamedTextColor.AQUA,
            'c' to NamedTextColor.RED, 'd' to NamedTextColor.LIGHT_PURPLE,
            'e' to NamedTextColor.YELLOW, 'f' to NamedTextColor.WHITE,
        )
        private val DECORATIONS = mapOf(
            'l' to TextDecoration.BOLD,
            'o' to TextDecoration.ITALIC,
            'n' to TextDecoration.UNDERLINED,
            'm' to TextDecoration.STRIKETHROUGH,
            'k' to TextDecoration.OBFUSCATED,
        )
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val renameText = event.inventory.renameText ?: return
        if (!renameText.contains('&')) return

        val player = event.viewers.filterIsInstance<Player>().firstOrNull() ?: return
        if (!player.hasPermission("joshymc.rename.colors")) return

        val result = event.result ?: return
        val meta = result.itemMeta ?: return

        meta.displayName(parseColoredName(renameText))
        result.itemMeta = meta
        event.result = result
    }

    private fun parseColoredName(input: String): Component {
        val root = Component.text().decoration(TextDecoration.ITALIC, false)
        var color: TextColor? = null
        val decorations = linkedSetOf<TextDecoration>()

        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '&' && i + 1 < input.length) {
                val next = input[i + 1]

                if (next == '#' && i + 8 <= input.length) {
                    val hex = input.substring(i + 2, i + 8)
                    if (hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        color = TextColor.fromHexString("#$hex")
                        i += 8
                        continue
                    }
                }

                val code = next.lowercaseChar()
                if (code == 'r') {
                    color = null
                    decorations.clear()
                    i += 2
                    continue
                }
                val decoration = DECORATIONS[code]
                if (decoration != null) {
                    decorations.add(decoration)
                    i += 2
                    continue
                }
                val named = LEGACY_COLORS[code]
                if (named != null) {
                    color = named
                    i += 2
                    continue
                }
            }

            var char = Component.text(c.toString(), color ?: NamedTextColor.WHITE)
            for (decoration in decorations) {
                char = char.decoration(decoration, true)
            }
            root.append(char)
            i++
        }

        return root.build()
    }
}
