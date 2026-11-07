package com.liam.joshymc.lifesteal

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.progression.LifestealProgressionManager
import com.liam.joshymc.tutorial.TutorialSignal
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/lifesteal pvp` (alias `rules`, issue #1387): the PvP, heart-stealing, combat-tag and
 * combat-log rules as they are actually configured on this server. Read only: every value comes
 * from the live services/settings (lifesteal.yml, config.yml `combat.tag-duration-seconds`, the
 * launch progression), nothing is decided or changed here. Same permission as `/lifesteal info`.
 * It is the "combat information" the Lifesteal tutorial's sixth objective asks players to open.
 */
class LifestealPvpInfoCommand(private val plugin: Joshymc) {

    fun visibleTo(sender: CommandSender): Boolean =
        plugin.lifestealHeartManager.hosted && sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)

    fun execute(sender: CommandSender) {
        if (!sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)) return Msg.noPermission(sender)
        val settings = plugin.lifestealConfigManager.settings
        val limits = plugin.lifestealHeartManager.limits
        val tagSeconds = plugin.combatManager.tagDurationMillis / 1000

        sender.sendMessage(Component.text("Lifesteal PvP Rules", NamedTextColor.RED, TextDecoration.BOLD))

        val progress = plugin.lifestealProgressionManager.progress()
        if (plugin.lifestealProgressionManager.pvpProtected()) {
            line(sender, "PvP", "Off right now (${progress.phase.label}): players can't hurt each other and no hearts move.", NamedTextColor.GREEN)
        } else {
            line(sender, "PvP", "On everywhere in Lifesteal worlds.", NamedTextColor.YELLOW)
        }
        settings?.progression?.takeIf { it.enabled }?.let {
            line(sender, "Grace period", "The first ${it.gracePeriodHours}h after launch have no PvP and no heart stealing.", NamedTextColor.GRAY)
        }

        if (limits != null) {
            line(sender, "Heart stealing", "The player who lands the killing blow steals 1 heart from the victim.", NamedTextColor.GRAY)
            line(sender, "Heart loss", "A kill that steals a heart takes it from the victim. You never drop below ${limits.minimum}: a player at ${limits.minimum} loses nothing more.", NamedTextColor.GRAY)
            line(sender, "Limits", "Start with ${limits.starting}, at most ${limits.maximum}. A killer at ${limits.maximum} gains nothing. No elimination or revival.", NamedTextColor.GRAY)
            val cooldown = plugin.lifestealHeartManager.cooldownMinutes
            if (cooldown > 0) {
                line(sender, "Anti-farming", "Killing the same player again within $cooldown min steals nothing.", NamedTextColor.GRAY)
            }
        } else {
            line(sender, "Hearts", "Paused right now (server configuration). Stored hearts are safe.", NamedTextColor.GRAY)
        }

        line(sender, "Combat tag", "Hitting or being hit by a player tags you for ${tagSeconds}s. While tagged you can't teleport, fly or switch servers.", NamedTextColor.GRAY)
        val leaveNpc = settings?.combatLogging?.leaveNpc ?: true
        val logLine = if (leaveNpc) {
            // Issue #1402: the Lifesteal Combat NPC stands in for the player instead of killing them.
            val lifetime = settings?.combatLogging?.npc?.lifetimeSeconds ?: LifestealSettings.CombatNpc().lifetimeSeconds
            "Logging out while tagged leaves a Combat Logger with your real items, health and effects for ${lifetime}s. " +
                "If anyone kills it you die normally (drops, hearts by the normal rules); if it survives you keep everything."
        } else {
            "Logging out while tagged is recorded for staff. It never moves hearts."
        }
        line(sender, "Combat logging", logLine, NamedTextColor.GRAY)

        settings?.teams?.let { teams ->
            val ff = if (teams.friendlyFire) "Teammates can hurt each other." else "Teammates can't hurt each other."
            line(sender, "Teams", ff, NamedTextColor.GRAY)
        }

        (sender as? Player)?.let { plugin.tutorialSignal(it, TutorialSignal.CombatInfoOpened) }
    }

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor) {
        sender.sendMessage(Component.text("  $label: ", NamedTextColor.GRAY).append(Component.text(value, color)))
    }
}
