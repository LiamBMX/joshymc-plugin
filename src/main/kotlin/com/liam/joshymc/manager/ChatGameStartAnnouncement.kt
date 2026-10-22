package com.liam.joshymc.manager

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration

/**
 * The chat game start announcement (issue #1254): one component, so it is
 * broadcast once and can never arrive out of order. Blank lines frame it and
 * set the prompt apart from the header (issue #1282):
 *
 *
 *   ✧ CHAT GAMES
 *   A chat game has started! Type the following:
 *
 *   ▸ Type: 'JoshyMC is the BEST' ◂
 *
 *
 * Math games read "Solve the following:" and `▸ Solve: 12 + 34 ◂`. The
 * reward and time limit sit in the prompt line's hover. Prompts are plain
 * [Component.text], never parsed, so they can't carry formatting.
 */
object ChatGameStartAnnouncement {

    val HEADER: TextColor = TextColor.color(0xFFAA55)
    val ACCENT: TextColor = TextColor.color(0xE68A3F)
    val TEXT: TextColor = TextColor.color(0xF5E6CC)
    val ACTION: TextColor = TextColor.color(0xD96C4A)
    val LABEL: TextColor = TextColor.color(0xE8C15A)
    val CHALLENGE: TextColor = TextColor.color(0xFFD166)
    val REWARD: TextColor = TextColor.color(0xA8D88A)

    private const val MATH_PREFIX = "Solve: "

    fun build(type: ChatGamesManager.GameType, prompt: String, reward: String, solveWindowSeconds: Long): Component {
        val verb = when (type) {
            ChatGamesManager.GameType.MATH -> "Solve"
            ChatGamesManager.GameType.TYPE -> "Type"
        }
        val hover = Component.text()
            .append(Component.text("First correct answer wins ", TEXT))
            .append(Component.text(reward, REWARD))
            .append(Component.newline())
            .append(Component.text("Time limit: ", TEXT))
            .append(Component.text("${solveWindowSeconds}s", LABEL))
            .build()

        return Component.text()
            .append(ChatGameSpacing.BLANK_LINE)
            .append(Component.newline())
            .append(Component.text("✧ ", ACCENT))
            .append(Component.text("CHAT GAMES", HEADER).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("A chat game has ", TEXT))
            .append(Component.text("started", ACTION))
            .append(Component.text("! ", TEXT))
            .append(Component.text("$verb the following", ACTION))
            .append(Component.text(":", TEXT))
            .append(Component.newline())
            .append(ChatGameSpacing.BLANK_LINE)
            .append(Component.newline())
            .append(promptLine(verb, challenge(type, prompt)).hoverEvent(HoverEvent.showText(hover)))
            .append(Component.newline())
            .append(ChatGameSpacing.BLANK_LINE)
            .build()
    }

    /**
     * The text players act on: Type prompts verbatim, Math prompts without
     * the generator's "Solve: " label, which the line already shows.
     */
    fun challenge(type: ChatGamesManager.GameType, prompt: String): String = when (type) {
        ChatGamesManager.GameType.MATH -> prompt.removePrefix(MATH_PREFIX)
        ChatGamesManager.GameType.TYPE -> "'$prompt'"
    }

    private fun promptLine(verb: String, challenge: String): Component =
        Component.text()
            .append(Component.text("▸ ", ACCENT))
            .append(Component.text("$verb: ", LABEL))
            .append(Component.text(challenge, CHALLENGE).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" ◂", ACCENT))
            .build()
}
