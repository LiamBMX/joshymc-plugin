package com.liam.joshymc.lifesteal

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.progression.LifestealProgressionManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/lifesteal info` (alias `help`, issue #1369): the player's own permanent hearts, the fixed
 * heart rules and the Lifesteal commands, on a server that hosts Lifesteal. Read only: hearts come
 * from the heart service's in-memory cache (no database read), the phase from the progression
 * service. Nothing here decides or changes anything. Uses the `/lifesteal progress` permission
 * (default true).
 */
class LifestealInfoCommand(private val plugin: Joshymc) {

    fun visibleTo(sender: CommandSender): Boolean =
        plugin.lifestealHeartManager.hosted && sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)

    fun execute(sender: CommandSender) {
        if (!sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)) return Msg.noPermission(sender)
        val heartService = plugin.lifestealHeartManager
        val limits = heartService.limits
        sender.sendMessage(Component.text("Lifesteal", NamedTextColor.RED, TextDecoration.BOLD))

        val player = sender as? Player
        val inLifesteal = player != null && plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL
        val heartsLine = when {
            player == null -> null
            !inLifesteal -> "Only counted in Lifesteal worlds."
            !heartService.active || limits == null -> "Paused right now (server configuration). Your stored hearts are safe."
            else -> heartService.heartsOf(player.uniqueId)?.let { "$it / ${limits.maximum}" } ?: "Loading..."
        }
        heartsLine?.let { line(sender, "Your hearts", it, NamedTextColor.RED) }
        if (limits != null) {
            line(sender, "Rules", "Start with ${limits.starting}, at most ${limits.maximum}, never below ${limits.minimum}.", NamedTextColor.GRAY)
            line(sender, "Kills", "Killing a player steals 1 heart (none from a player at ${limits.minimum}).", NamedTextColor.GRAY)
            if (heartService.cooldownMinutes > 0) {
                line(sender, "Farming", "Killing the same player again within ${heartService.cooldownMinutes} min steals nothing.", NamedTextColor.GRAY)
            }
        }
        val progress = plugin.lifestealProgressionManager.progress()
        line(sender, "Season", progress.phase.label, NamedTextColor.YELLOW)

        sender.sendMessage(Component.text("Commands", NamedTextColor.GOLD, TextDecoration.BOLD))
        command(sender, "/team", "create, invite, join and manage your team (max ${plugin.lifestealConfigManager.settings?.teams?.maxSize ?: 5})")
        command(sender, "/withdrawheart", "turn 1 heart into a Heart item (right-click it to redeem)")
        command(sender, "/lifesteal progress", "grace period, End unlock and season countdowns")
        command(sender, "/lifesteal pvp", "PvP, heart stealing, combat tag and combat logging rules")
        if (plugin.lifestealRankedQueueManager.commandVisibleTo(sender)) command(sender, "/ranked", "Ranked Team Wars")
        // Issue #1387: this is the Hearts information the Lifesteal tutorial's fourth objective opens.
        player?.let { plugin.tutorialSignal(it, com.liam.joshymc.tutorial.TutorialSignal.HeartsInfoOpened) }
    }

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor) {
        sender.sendMessage(
            Component.text("  $label: ", NamedTextColor.GRAY).append(Component.text(value, color))
        )
    }

    private fun command(sender: CommandSender, usage: String, what: String) {
        sender.sendMessage(
            Component.text("  $usage", NamedTextColor.YELLOW)
                .clickEvent(ClickEvent.suggestCommand(usage))
                .append(Component.text(" - $what", NamedTextColor.GRAY))
        )
    }
}
