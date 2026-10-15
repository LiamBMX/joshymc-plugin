package com.liam.joshymc.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed

/**
 * Drop-in [EmbedBuilder] that never throws over Discord's embed limits (issue #1159).
 *
 * Alerts carry player-controlled or generated text (anti-cheat evidence, item names,
 * transaction ids, report reasons), and plain EmbedBuilder throws on a value over its
 * limit, which would surface inside the gameplay handler raising the alert. Here every
 * title, field, description, author and footer is cut to its limit with a visible
 * [TRUNCATED] marker, a blank field value becomes "N/A", fields past the 25th
 * are dropped with a note, and [build] shrinks the longest values until the whole embed
 * fits in 6000 characters. Embeds that already fit come out exactly as before.
 */
open class SafeEmbedBuilder : EmbedBuilder() {

    private var droppedFields = 0

    override fun setTitle(title: String?, url: String?): EmbedBuilder =
        super.setTitle(title?.takeIf { it.isNotBlank() }?.let { clip(it, MessageEmbed.TITLE_MAX_LENGTH) }, url)

    /** [setDescription] is final in JDA but routes through here. */
    override fun appendDescription(description: CharSequence): EmbedBuilder {
        val room = MessageEmbed.DESCRIPTION_MAX_LENGTH - descriptionBuilder.length
        if (room <= 0) return this
        return super.appendDescription(clip(description.toString(), room))
    }

    override fun setAuthor(name: String?, url: String?, iconUrl: String?): EmbedBuilder =
        super.setAuthor(name?.let { clip(it, MessageEmbed.AUTHOR_MAX_LENGTH) }, url, iconUrl)

    override fun setFooter(text: String?, iconUrl: String?): EmbedBuilder =
        super.setFooter(text?.let { clip(it, MessageEmbed.TEXT_MAX_LENGTH) }, iconUrl)

    override fun addField(name: String, value: String, inline: Boolean): EmbedBuilder {
        if (fields.size >= MessageEmbed.MAX_FIELD_AMOUNT) {
            droppedFields++
            return this
        }
        val safeName = clip(name, MessageEmbed.TITLE_MAX_LENGTH).ifBlank { ZERO_WIDTH_SPACE }
        val safeValue = if (value.isBlank()) "N/A" else clip(value, MessageEmbed.VALUE_MAX_LENGTH)
        return super.addField(safeName, safeValue, inline)
    }

    override fun build(): MessageEmbed {
        if (droppedFields > 0) {
            appendDescription("\n($droppedFields more field${if (droppedFields == 1) "" else "s"} not shown)")
            droppedFields = 0
        }
        fitTotalLength()
        return super.build()
    }

    /** Shortens the longest field value or the description until the embed fits [MessageEmbed.EMBED_MAX_LENGTH_BOT]. */
    private fun fitTotalLength() {
        repeat(MessageEmbed.MAX_FIELD_AMOUNT + 2) {
            val excess = length() - MessageEmbed.EMBED_MAX_LENGTH_BOT
            if (excess <= 0) return
            val longestField = fields.indices.maxByOrNull { fields[it].value?.length ?: 0 }
            val fieldLength = longestField?.let { fields[it].value?.length } ?: 0
            val description = descriptionBuilder
            if (description.length >= fieldLength) {
                if (description.length <= MIN_SHRUNK_LENGTH) return
                val clipped = clip(description.toString(), (description.length - excess).coerceAtLeast(MIN_SHRUNK_LENGTH))
                description.setLength(0)
                description.append(clipped)
            } else {
                if (longestField == null || fieldLength <= MIN_SHRUNK_LENGTH) return
                val field = fields[longestField]
                val value = field.value ?: return
                fields[longestField] = MessageEmbed.Field(
                    field.name, clip(value, (value.length - excess).coerceAtLeast(MIN_SHRUNK_LENGTH)), field.isInline
                )
            }
        }
    }

    companion object {
        const val TRUNCATED = "… [truncated]"
        /** Values are never shrunk below this when fitting the total, so each keeps a readable start. */
        private const val MIN_SHRUNK_LENGTH = 64

        /**
         * [text] cut to at most [max] UTF-16 chars, ending in [TRUNCATED] when anything was cut.
         * Never splits a surrogate pair, so emoji and other astral characters stay valid.
         */
        fun clip(text: String, max: Int): String {
            if (text.length <= max) return text
            if (max <= TRUNCATED.length) return text.take(max).let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
            var cut = max - TRUNCATED.length
            if (Character.isHighSurrogate(text[cut - 1])) cut--
            return text.substring(0, cut) + TRUNCATED
        }
    }
}
