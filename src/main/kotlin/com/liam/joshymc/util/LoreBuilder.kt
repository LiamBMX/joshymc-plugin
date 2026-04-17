package com.liam.joshymc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Unified lore builder so all items share the same clean format.
 *
 * Format:
 *   (blank)
 *   Type: <type>
 *   (blank)
 *   <description lines>
 *   (blank)
 *   <usage line>
 */
object LoreBuilder {

    private val ACCENT = TextColor.color(0x55FFFF)
    private val LABEL = TextColor.color(0xAAAAAA)
    private val DESC = NamedTextColor.GRAY
    private val USAGE = TextColor.color(0xFFFF55)

    fun build(
        type: String,
        description: List<String>,
        usage: String,
    ): List<Component> {
        val lines = mutableListOf<Component>()

        lines.add(Component.empty())
        lines.add(line(LABEL, "Type: ").append(Component.text(type, ACCENT)).noItalic())
        lines.add(Component.empty())

        for (desc in description) {
            lines.add(line(DESC, desc).noItalic())
        }

        lines.add(Component.empty())
        lines.add(line(USAGE, usage).noItalic())

        return lines
    }

    private fun line(color: TextColor, text: String): Component =
        Component.text(text, color)

    private fun line(color: NamedTextColor, text: String): Component =
        Component.text(text, color)

    private fun Component.noItalic(): Component =
        decoration(TextDecoration.ITALIC, false)
}
