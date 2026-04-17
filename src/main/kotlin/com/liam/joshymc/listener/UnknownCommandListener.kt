package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.command.UnknownCommandEvent

class UnknownCommandListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onUnknownCommand(event: UnknownCommandEvent) {
        val input = event.commandLine.split(" ").firstOrNull()?.lowercase() ?: return

        // Get all known commands
        val knownCommands = plugin.server.commandMap.knownCommands.keys
            .filter { !it.contains(":") } // Skip namespace:command duplicates
            .distinct()

        // Find closest match using Levenshtein distance
        val maxDistance = 3
        val suggestion = knownCommands
            .map { it to levenshtein(input, it) }
            .filter { it.second <= maxDistance }
            .minByOrNull { it.second }

        if (suggestion != null) {
            val suggestedCmd = suggestion.first
            event.message(
                Component.text("Unknown command. Did you mean ", NamedTextColor.GRAY)
                    .append(
                        Component.text("/$suggestedCmd", NamedTextColor.WHITE)
                            .decoration(TextDecoration.BOLD, true)
                            .clickEvent(ClickEvent.suggestCommand("/$suggestedCmd "))
                    )
                    .append(Component.text("?", NamedTextColor.GRAY))
            )
        } else {
            event.message(
                Component.text("Unknown command. Type ", NamedTextColor.GRAY)
                    .append(
                        Component.text("/help", NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.suggestCommand("/help"))
                    )
                    .append(Component.text(" for a list of commands.", NamedTextColor.GRAY))
            )
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }
}
