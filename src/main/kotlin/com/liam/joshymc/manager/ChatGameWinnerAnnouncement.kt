package com.liam.joshymc.manager

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import java.util.Locale

/**
 * The chat game winner announcement (issue #1251): one component, so it is
 * broadcast once and can never arrive out of order. Blank lines frame it and
 * set the reward apart from the result (issue #1282):
 *
 *
 *   ✓ CHAT GAME
 *   PlayerName won in 3.0s!
 *
 *   Reward: 3.0 Credits
 *
 *
 * Names and rewards are plain [Component.text], never parsed, so they can't
 * carry formatting.
 */
object ChatGameWinnerAnnouncement {

    val HEADER: TextColor = TextColor.color(0xFFAA55)
    val WINNER: TextColor = TextColor.color(0xFFD166)
    val TEXT: TextColor = TextColor.color(0xF5E6CC)
    val TIME: TextColor = TextColor.color(0x7DDDD0)
    val REWARD: TextColor = TextColor.color(0xA8D88A)

    fun build(winnerName: String, elapsedMillis: Long, reward: String): Component =
        Component.text()
            .append(ChatGameSpacing.BLANK_LINE)
            .append(Component.newline())
            .append(Component.text("✓ CHAT GAME", HEADER).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text(winnerName, WINNER).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" won in ", TEXT))
            .append(Component.text(formatSeconds(elapsedMillis), TIME))
            .append(Component.text("!", TEXT))
            .append(Component.newline())
            .append(ChatGameSpacing.BLANK_LINE)
            .append(Component.newline())
            .append(Component.text("Reward: ", TEXT))
            .append(Component.text(reward, REWARD))
            .append(Component.newline())
            .append(ChatGameSpacing.BLANK_LINE)
            .build()

    /** One decimal place with the seconds suffix, e.g. `3.0s`. */
    fun formatSeconds(elapsedMillis: Long): String =
        String.format(Locale.ROOT, "%.1fs", elapsedMillis.coerceAtLeast(0L) / 1000.0)
}
