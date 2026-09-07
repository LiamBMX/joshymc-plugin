package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat-input handoff for the /team GUI (issue #523) — mirrors the pending-input
 * pattern OrderManager uses for order creation/custom sell amounts. Every
 * prompt completes by dispatching the exact same /team subcommand the typed
 * text would have used, so no team logic is duplicated between the command
 * and the GUI.
 */
object TeamGuiInput {

    private data class Pending(
        val validate: (String) -> Component?,
        val command: (String) -> String,
        val andThen: (Joshymc, Player) -> Unit
    )

    private val pending = ConcurrentHashMap<UUID, Pending>()

    fun isAwaiting(uuid: UUID): Boolean = pending.containsKey(uuid)

    fun clear(uuid: UUID) {
        pending.remove(uuid)
    }

    /**
     * Prompts [player] in chat, closing whatever GUI they had open. [command]
     * turns the raw (trimmed) chat input into the full /team subcommand line
     * to dispatch; [andThen] runs afterward to bring the player back into a GUI.
     */
    fun prompt(
        plugin: Joshymc,
        player: Player,
        question: Component,
        validate: (String) -> Component? = { null },
        command: (String) -> String,
        andThen: (Joshymc, Player) -> Unit = { p, viewer -> TeamMainGui.open(p, viewer) }
    ) {
        pending[player.uniqueId] = Pending(validate, command, andThen)
        player.closeInventory()
        plugin.commsManager.send(player, question, CommunicationsManager.Category.DEFAULT)
        plugin.commsManager.send(player, Component.text("Type 'cancel' in chat to abort.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
    }

    fun handleChatInput(plugin: Joshymc, player: Player, raw: String) {
        val request = pending.remove(player.uniqueId) ?: return
        val trimmed = raw.trim()

        if (trimmed.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        val error = request.validate(trimmed)
        if (error != null) {
            plugin.commsManager.send(player, error, CommunicationsManager.Category.DEFAULT)
            return
        }

        Bukkit.dispatchCommand(player, request.command(trimmed))
        request.andThen(plugin, player)
    }
}
