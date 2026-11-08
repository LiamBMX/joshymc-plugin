package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.PrivateMessages
import com.liam.joshymc.manager.CommunicationsManager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Muted players can't talk through the vanilla chat commands JoshyMC doesn't own (issue #1430):
 * `/minecraft:msg|tell|w` (JoshyMC's `/msg` owns the bare labels and checks mutes itself), `/me`
 * and `/teammsg` (`/tm`) in any namespace. Applies in every gamemode, using the mute of the gamemode
 * the player is in, like public chat. Unmuted players are never affected.
 */
class MutedCommandListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val label = MutedCommands.label(event.message) ?: return
        if (!MutedCommands.isChatCommand(label)) return
        val player = event.player
        val mute = plugin.punishmentManager.isMutedIn(player.uniqueId, plugin.gamemodeLockManager.gamemodeOf(player.world)) ?: return
        event.isCancelled = true
        plugin.commsManager.send(player, PrivateMessages.mutedMessage(mute), CommunicationsManager.Category.ADMIN)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        PrivateMessages.forget(event.player.uniqueId)
    }
}

/** Bukkit-free command matching for [MutedCommandListener] (checked by the social check script). */
object MutedCommands {
    /** Vanilla chat commands with no JoshyMC executor. */
    private val VANILLA_ONLY = setOf("me", "teammsg", "tm")
    /** Vanilla private-message commands whose bare labels JoshyMC's `/msg` owns. */
    private val VANILLA_PRIVATE = setOf("msg", "tell", "w")

    /** The lower-case first word of a typed command, without the slash. */
    fun label(message: String): String? =
        message.removePrefix("/").trim().substringBefore(' ').lowercase().takeIf { it.isNotEmpty() }

    fun isChatCommand(label: String): Boolean {
        val namespace = label.substringBefore(':', "")
        val name = label.substringAfter(':')
        return when (namespace) {
            "" -> name in VANILLA_ONLY
            "minecraft" -> name in VANILLA_ONLY || name in VANILLA_PRIVATE
            else -> false
        }
    }
}
