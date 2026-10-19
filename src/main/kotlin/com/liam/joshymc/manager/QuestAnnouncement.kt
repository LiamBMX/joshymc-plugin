package com.liam.joshymc.manager

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import java.util.Locale

/**
 * The QUESTS line in the rotating announcements (issue #1278): two compact lines,
 * no "ANNOUNCEMENT" header, naming the real Daily/Weekly completion rewards.
 * Bukkit-free; the caller passes the configured Credits.
 */
object QuestAnnouncement {
    val LABEL_COLOR: TextColor = TextColor.color(0xFFD700)
    val COMMAND_COLOR: TextColor = TextColor.color(0xFFAA00)

    /**
     * [dailyCredits]/[weeklyCredits] are the Credits paid for finishing every Daily /
     * Weekly quest of a cycle, or null when that completion reward is disabled (then
     * it isn't mentioned). Each enabled completion reward also gives one crate key.
     */
    fun build(dailyCredits: Double?, weeklyCredits: Double?): Component {
        val command = Component.text("/quests", COMMAND_COLOR, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/quests"))
            .hoverEvent(HoverEvent.showText(Component.text("Click to open your quests", NamedTextColor.GRAY)))

        val builder = Component.text()
            .append(Component.text("QUESTS", LABEL_COLOR, TextDecoration.BOLD))
            .append(Component.text(" » ", NamedTextColor.GRAY))
            .append(Component.text("Earn money with your Daily & Weekly quests: ", NamedTextColor.WHITE))
            .append(command)

        val rewards = listOfNotNull(
            dailyCredits?.let { "All Dailies" to it },
            weeklyCredits?.let { "All Weeklies" to it }
        )
        if (rewards.isNotEmpty()) {
            builder.append(Component.newline())
            rewards.forEachIndexed { i, (label, credits) ->
                if (i > 0) builder.append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                builder.append(Component.text("$label: ", NamedTextColor.GRAY))
                builder.append(Component.text(rewardText(credits), NamedTextColor.AQUA))
            }
        }
        return builder.build()
    }

    fun rewardText(credits: Double): String =
        if (credits > 0.0) "${formatCredits(credits)} Credits + Crate Key" else "Crate Key"

    private fun formatCredits(credits: Double): String =
        if (credits % 1.0 == 0.0) "%,d".format(Locale.US, credits.toLong()) else "%,.2f".format(Locale.US, credits)
}
