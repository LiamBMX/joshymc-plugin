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
        if (CombatTeleportRules.allowsRequest(plugin.combatManager.isTagged(player))) return false
        plugin.commsManager.send(player, Component.text(CombatTeleportRules.MSG_IN_COMBAT, NamedTextColor.RED))
        return true
    }

    /** Tells [player] that their pending teleport was cancelled because they entered combat. */
    fun notifyCancelled(plugin: Joshymc, player: Player) {
        plugin.commsManager.send(player, Component.text(CombatTeleportRules.MSG_ENTERED_COMBAT, NamedTextColor.RED))
    }
}
