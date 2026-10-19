package com.liam.joshymc.command

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * The /promote live announcement (issue #1260): three lines in one
 * component, so it is broadcast once and can never arrive out of order.
 *
 *   ✦ LIVE ANNOUNCEMENT
 *   <player> is live!
 *   [CLICK HERE TO WATCH]
 *
 * The last line opens the stream URL; the hover names the platform and
 * shows the URL. The player name is plain [Component.text], never parsed,
 * so it can't carry formatting.
 */
object LiveAnnouncement {

    val HEADER: TextColor = TextColor.color(0xE68A3F)
    val ACCENT: TextColor = TextColor.color(0xFFAA55)
    val NAME: TextColor = TextColor.color(0xFFD166)
    val TEXT: TextColor = TextColor.color(0xF5E6CC)
    val LINK: TextColor = TextColor.color(0x9DDB75)

    const val WATCH_TEXT = "[CLICK HERE TO WATCH]"

    /** Only https links ever get a click event; the command builds them from fixed templates. */
    fun build(playerName: String, platformLabel: String, url: String): Component {
        require(url.startsWith("https://")) { "Stream URL must be https: $url" }

        val hover = Component.text()
            .append(Component.text("Click to watch the livestream on ", TEXT))
            .append(Component.text(platformLabel, NAME))
            .append(Component.text("!", TEXT))
            .append(Component.newline())
            .append(Component.text(url, LINK))
            .build()

        return Component.text()
            .append(Component.text("✦ ", ACCENT))
            .append(Component.text("LIVE ANNOUNCEMENT", HEADER).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text(playerName, NAME).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" is live!", TEXT))
            .append(Component.newline())
            .append(
                Component.text(WATCH_TEXT, LINK)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.UNDERLINED, true)
                    .clickEvent(ClickEvent.openUrl(url))
                    .hoverEvent(HoverEvent.showText(hover))
            )
            .build()
    }
}
