package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PunishmentManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * The one delivery path for private messages: `/msg` (and its aliases `/tell`, `/w`, `/whisper`,
 * `/pm`, `/dm`, `joshymc:` forms), `/reply` (`/r`) and the Friends menu's "Send message" button,
 * which only suggests `/msg` (issue #1430).
 *
 * Order: the sender's mute in the gamemode they're in (every gamemode; the staff permission audit
 * found mutes didn't block `/msg` and `/r`), then on Lifesteal the friends/privacy/vanish rules and
 * the public-chat repeat rule and profanity filter, then the existing ignore list and the
 * personal-messages toggle. Survival keeps its old behaviour apart from the mute check.
 */
object PrivateMessages {

    /** Who last private-messaged each player (receiver -> sender), so `/r` can answer them. */
    private val lastIncoming = java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.UUID>()

    fun forget(uuid: java.util.UUID) {
        lastIncoming.remove(uuid)
    }

    /**
     * [viaReply]: sent with `/r`. Answering the player who last messaged you skips the Lifesteal
     * audience and vanish checks (they started the conversation); mutes, ignores and filters still apply.
     */
    fun send(plugin: Joshymc, sender: Player, target: Player, rawMessage: String, viaReply: Boolean = false) {
        val gamemode = plugin.gamemodeLockManager.gamemodeOf(sender.world)
        plugin.punishmentManager.isMutedIn(sender.uniqueId, gamemode)?.let { mute ->
            plugin.commsManager.send(sender, mutedMessage(mute), CommunicationsManager.Category.ADMIN)
            return
        }
        var message = rawMessage
        val social = plugin.lifestealSocialManager
        if (social.appliesToMessage(sender, target)) {
            val answering = viaReply && lastIncoming[sender.uniqueId] == target.uniqueId
            social.messageProblem(sender, target, answering)?.let { refusal ->
                if (refusal == com.liam.joshymc.lifesteal.social.SocialRules.MessageRefusal.UNAVAILABLE) Msg.playerNotFound(sender)
                else Msg.error(sender, refusal.message)
                return
            }
            message = social.moderate(sender, message) ?: return
        }
        if (IgnoreCommand.isIgnoring(target.uniqueId, sender.uniqueId)) {
            plugin.commsManager.send(sender, Component.text("That player is not accepting messages from you.", NamedTextColor.RED))
            return
        }
        if (!plugin.commsManager.canReceivePersonalMessages(target)) {
            plugin.commsManager.send(sender, Component.text("That player has personal messages disabled.", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("[me → ${target.name}] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        target.sendMessage(Component.text("[${sender.name} → me] ", NamedTextColor.GRAY).append(Component.text(message, NamedTextColor.WHITE)))
        MsgCommand.lastMessaged[sender.uniqueId] = target.uniqueId
        MsgCommand.lastMessaged[target.uniqueId] = sender.uniqueId
        lastIncoming[target.uniqueId] = sender.uniqueId
    }

    fun mutedMessage(mute: PunishmentManager.ActivePunishment): Component {
        val msg = Component.text().append(Component.text("You are muted and can't send private messages.", NamedTextColor.RED))
        if (mute.reason != null) {
            msg.append(Component.text(" Reason: ", NamedTextColor.GRAY)).append(Component.text(mute.reason, NamedTextColor.WHITE))
        }
        return msg.build()
    }
}
