package com.liam.joshymc.discord

import java.time.Instant
import java.util.UUID

/**
 * Shared building blocks for JoshyMC's Discord embeds (issue #1161), so every feed shows
 * players, times, places and severities the same way. Embeds are still built per feature
 * (their fields differ on purpose); this only covers what they have in common. Always build
 * on [alert], which is a [SafeEmbedBuilder], so the size limits from issue #1159 still apply.
 */
object DiscordEmbeds {

    /** Footer of public chat-bridge embeds and replies. */
    const val PUBLIC_FOOTER = "play.joshymc.net"

    /** Alert colors by severity, shared by anti-cheat, combat, anti-dupe and X-ray alerts. */
    enum class Severity(val color: Int) {
        LOW(0xFEE75C),
        MEDIUM(0xFFA500),
        HIGH(0xED4245),
        CRITICAL(0x8B0000),
    }

    /** Color of "repeated alerts grouped" follow-ups. */
    const val GROUPED_COLOR = 0xFFA500

    /**
     * A staff alert: title, color, the event time as the embed timestamp (Discord shows it in
     * each viewer's own timezone) and a "JoshyMC • [source]" footer naming the feed.
     */
    fun alert(title: String, color: Int, source: String, at: Instant = Instant.now()): SafeEmbedBuilder =
        SafeEmbedBuilder().apply {
            setTitle(title)
            setColor(color)
            setFooter("JoshyMC • $source")
            setTimestamp(at)
        }

    /**
     * A player as staff see it everywhere: escaped name plus UUID in code style, so a name
     * can't turn into markdown or a mention and the UUID copies cleanly.
     */
    fun player(name: String, uuid: UUID?): String {
        val shown = escape(name.ifBlank { "Unknown" })
        return if (uuid == null) shown else "$shown (`$uuid`)"
    }

    fun player(name: String, uuid: String?): String =
        player(name, uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() })

    /** A Discord timestamp (`<t:..>`), rendered in each viewer's own timezone. Style F = full date and time. */
    fun time(epochMs: Long, style: String = "F"): String = "<t:${epochMs / 1000}:$style>"

    /** Full time plus relative ("5 minutes ago"), for fields where both help. */
    fun timeWithRelative(epochMs: Long): String = "${time(epochMs, "F")} (${time(epochMs, "R")})"

    fun coords(x: Int, y: Int, z: Int): String = "X: $x, Y: $y, Z: $z"

    /** Escapes Discord markdown and breaks mention syntax in player-controlled text. */
    fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("~", "\\~")
        .replace("`", "\\`")
        .replace("|", "\\|")
        .replace(">", "\\>")
        .let(DiscordManager::neutralizeMentions)
}
