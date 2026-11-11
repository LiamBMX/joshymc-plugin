package com.liam.joshymc.teleport

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * Server-side combat check for player-initiated teleports (issue #1356). The command-name
 * filter in [com.liam.joshymc.listener.CombatListener] only sees typed commands by their first
 * word, so subcommands (`/team home`), GUI buttons, `dispatchCommand`/NPC commands and
 * aliases never reached it. Every player teleport path checks here instead, before it moves
 * the player, consumes a cooldown or writes anything.
 *
 * There is no bypass: staff teleporting *other* players use their own admin tools, which
 * don't go through this guard.
 */
object CombatTeleportGuard {

    /** Returns true (and tells the player once) when [player] is combat-tagged and must not teleport. */
    fun blocks(plugin: Joshymc, player: Player): Boolean {
        val problem = problem(plugin, player) ?: return false
        plugin.commsManager.send(player, Component.text(problem, NamedTextColor.RED))
        return true
    }

    /**
     * The same check as [blocks] without telling the player: null when [player] may teleport,
     * otherwise the reason. For callers that report the refusal themselves (issue #1511).
     */
    fun problem(plugin: Joshymc, player: Player): String? {
        // Ranked Team Wars (issue #1361): a match holds its players in the arena until they're out.
        if (plugin.lifestealRankedManager.holds(player)) return MSG_RANKED_MATCH
        if (CombatTeleportRules.allowsRequest(plugin.combatManager.isTagged(player))) return null
        return CombatTeleportRules.MSG_IN_COMBAT
    }

    const val MSG_RANKED_MATCH = "You can't teleport during a ranked match."

    /** Tells [player] that their pending teleport was cancelled because they entered combat. */
    fun notifyCancelled(plugin: Joshymc, player: Player) {
        plugin.commsManager.send(player, Component.text(CombatTeleportRules.MSG_ENTERED_COMBAT, NamedTextColor.RED))
    }
}
