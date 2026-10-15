package com.liam.joshymc.discord

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextReplacementConfig
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import java.util.regex.Pattern

/**
 * Discord -> Minecraft chat rendering (issue #1155). Only the server's own
 * `discord.minecraft-format` is parsed for `&` codes; Discord names and messages go in as
 * literal text, so `&c[Owner]`, `&k` or a line break in a message can't style or split it.
 */
object DiscordText {

    const val MAX_NAME_LENGTH = 32
    const val MAX_MESSAGE_LENGTH = 256

    private val PLACEHOLDER = Pattern.compile("\\{(name|message)}")

    /**
     * Flattens untrusted Discord text to one plain line: every kind of line break and
     * whitespace run becomes a single space, and characters that can restyle or rearrange
     * chat are dropped (control and invisible format characters such as bidi overrides,
     * `§` which the client still reads as a formatting code, and private-use glyphs the
     * resource pack maps to logos and negative spaces). Emoji, accents and punctuation stay.
     */
    fun sanitize(input: String, maxLength: Int): String {
        val out = StringBuilder(minOf(input.length, maxLength * 2))
        var count = 0
        var pendingSpace = false
        val codePoints = input.codePoints().iterator()
        while (codePoints.hasNext() && count < maxLength) {
            val cp = codePoints.nextInt()
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (!isAllowed(cp)) continue
            if (pendingSpace) {
                out.append(' ')
                count++
                pendingSpace = false
                if (count >= maxLength) break
            }
            out.appendCodePoint(cp)
            count++
        }
        return out.toString()
    }

    private fun isAllowed(cp: Int): Boolean {
        if (cp == '§'.code) return false
        if (cp == ZERO_WIDTH_JOINER) return true // joins multi-part emoji
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
            Character.SURROGATE, Character.UNASSIGNED -> false
            else -> true
        }
    }

    /**
     * Renders the trusted [format] with `{name}` and `{message}` filled in as literal text.
     * Each value takes the format's color at its placeholder, so the configured look is
     * unchanged; neither value is scanned for placeholders again.
     */
    fun render(format: String, name: String, message: String): Component {
        val base = LegacyComponentSerializer.legacyAmpersand().deserialize(format)
        return base.replaceText(
            TextReplacementConfig.builder()
                .match(PLACEHOLDER)
                .replacement { result, builder -> builder.content(if (result.group(1) == "name") name else message) }
                .build()
        )
    }

    private const val ZERO_WIDTH_JOINER = 0x200D
}
