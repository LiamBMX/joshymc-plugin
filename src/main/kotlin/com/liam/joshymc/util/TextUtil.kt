package com.liam.joshymc.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * Utility helpers for building Adventure text components.
 * Use these to keep lore/display name construction consistent across items.
 */
object TextUtil {

    /**
     * Creates a gradient-colored text by interpolating between two hex colors.
     */
    fun gradient(text: String, fromHex: Int, toHex: Int, bold: Boolean = false): Component {
        val builder = Component.text()
        val len = text.length.coerceAtLeast(1)

        for ((i, char) in text.withIndex()) {
            val ratio = if (len == 1) 0.0 else i.toDouble() / (len - 1)
            val r = lerp((fromHex shr 16) and 0xFF, (toHex shr 16) and 0xFF, ratio)
            val g = lerp((fromHex shr 8) and 0xFF, (toHex shr 8) and 0xFF, ratio)
            val b = lerp(fromHex and 0xFF, toHex and 0xFF, ratio)

            val component = Component.text(char.toString(), TextColor.color(r, g, b))
                .decoration(TextDecoration.ITALIC, false)
            if (bold) component.decoration(TextDecoration.BOLD, true)
            builder.append(component)
        }
        return builder.build()
    }

    private fun lerp(a: Int, b: Int, t: Double): Int = (a + (b - a) * t).toInt()
}
