package com.liam.joshymc.util

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Shared command feedback (issue #1124). Players get the message through
 * [CommunicationsManager.send] so the category prefix applies; console and
 * other senders get the bare message with no decorative prefix.
 *
 * Use these instead of hand-writing "No permission." / "Players only." /
 * "Usage: ..." so every command answers the same way.
 */
object Msg {

    const val NO_PERMISSION = "You don't have permission to do that."
    const val PLAYERS_ONLY = "Only players can use this command."

    private val serializer = LegacyComponentSerializer.legacyAmpersand()

    fun prefix(): Component {
        val raw = Joshymc.instance.config.getString("prefix") ?: "&b&lJoshyMC &8» &r"
        return serializer.deserialize(raw)
    }

    fun send(player: Player, message: Component) {
        player.sendMessage(prefix().append(message))
    }

    fun send(player: Player, legacyMessage: String) {
        player.sendMessage(prefix().append(serializer.deserialize(legacyMessage)))
    }

    // ---- Command feedback ----

    /** Sends [message] to any sender: prefixed for players, bare for console. */
    fun reply(
        sender: CommandSender,
        message: Component,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) {
        if (sender is Player) Joshymc.instance.commsManager.send(sender, message, category)
        else sender.sendMessage(message)
    }

    /** The one permission-denial message. Never names the missing node. */
    fun noPermission(
        sender: CommandSender,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) = reply(sender, Component.text(NO_PERMISSION, NamedTextColor.RED), category)

    /** For commands that need an in-game player and were run from console. */
    fun playersOnly(sender: CommandSender) =
        sender.sendMessage(Component.text(PLAYERS_ONLY, NamedTextColor.RED))

    /** `Usage: /cmd <required> [optional]` — pass the syntax including the leading slash. */
    fun usage(
        sender: CommandSender,
        syntax: String,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) = reply(
        sender,
        Component.text("Usage: ", NamedTextColor.RED).append(Component.text(syntax, NamedTextColor.GRAY)),
        category,
    )

    /** `Player not found: <name>`, or `Player not found.` when no name is given. */
    fun playerNotFound(
        sender: CommandSender,
        name: String? = null,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) = reply(
        sender,
        Component.text(if (name == null) "Player not found." else "Player not found: $name", NamedTextColor.RED),
        category,
    )

    fun error(
        sender: CommandSender,
        message: String,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) = reply(sender, Component.text(message, NamedTextColor.RED), category)

    fun success(
        sender: CommandSender,
        message: String,
        category: CommunicationsManager.Category = CommunicationsManager.Category.DEFAULT,
    ) = reply(sender, Component.text(message, NamedTextColor.GREEN), category)
}
