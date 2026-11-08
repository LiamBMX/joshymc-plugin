package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.social.SocialRules.Audience
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/friend` and `/friends` (one executor, issue #1430, Lifesteal only). No arguments opens the
 * Friends menu. Every subcommand goes through [LifestealSocialManager], which re-checks the
 * feature switch, world and permission when it runs.
 */
class FriendCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val social get() = plugin.lifestealSocialManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        social.friendProblem(sender)?.let { social.refuse(sender, it); return true }
        if (args.isEmpty()) {
            FriendsMenu(plugin).openMain(sender)
            return true
        }
        val sub = args[0].lowercase()
        val name = args.getOrNull(1)
        when (sub) {
            "add", "request", "invite" -> name?.let { social.addFriend(sender, it) } ?: Msg.usage(sender, "/friend add <player>")
            "remove", "delete", "unfriend" -> name?.let { social.remove(sender, it) } ?: Msg.usage(sender, "/friend remove <player>")
            "accept" -> name?.let { social.accept(sender, it) } ?: Msg.usage(sender, "/friend accept <player>")
            "deny", "decline", "reject" -> name?.let { social.deny(sender, it) } ?: Msg.usage(sender, "/friend deny <player>")
            "cancel" -> name?.let { social.cancel(sender, it) } ?: Msg.usage(sender, "/friend cancel <player>")
            "requests" -> social.requests(sender)
            "list" -> social.list(sender)
            "menu", "gui" -> FriendsMenu(plugin).openMain(sender)
            "settings", "privacy" -> settings(sender, args.drop(1))
            "help" -> help(sender)
            else -> help(sender)
        }
        return true
    }

    private fun settings(player: Player, args: List<String>) {
        if (args.isEmpty()) {
            FriendsMenu(plugin).openSettings(player)
            return
        }
        val option = args[0].lowercase()
        val value = args.getOrNull(1)
        val usage = "/friend settings <requests|status|messages|loginnotify|requestnotify> <value>"
        if (value == null) return Msg.usage(player, usage)
        fun audience(choices: List<Audience>): Audience? =
            Audience.parse(value)?.takeIf { it in choices }.also {
                if (it == null) Msg.error(player, "Choose one of: ${choices.joinToString { c -> c.name.lowercase() }}.")
            }
        fun toggle(): Boolean? = when (value.lowercase()) {
            "on", "true", "yes", "enable", "enabled" -> true
            "off", "false", "no", "disable", "disabled" -> false
            else -> null.also { Msg.error(player, "Choose on or off.") }
        }
        val updated = when (option) {
            "requests", "friendrequests" -> audience(SocialRules.REQUEST_CHOICES)?.let { a -> social.updatePrefs(player) { it.copy(requestsFrom = a) } }
            "status", "online", "onlinestatus" -> audience(SocialRules.STATUS_CHOICES)?.let { a -> social.updatePrefs(player) { it.copy(statusVisibleTo = a) } }
            "messages", "msg", "dms" -> audience(SocialRules.MESSAGE_CHOICES)?.let { a -> social.updatePrefs(player) { it.copy(messagesFrom = a) } }
            "loginnotify", "login", "notifications" -> toggle()?.let { t -> social.updatePrefs(player) { it.copy(loginNotifications = t) } }
            "requestnotify", "requestnotifications" -> toggle()?.let { t -> social.updatePrefs(player) { it.copy(requestNotifications = t) } }
            else -> { Msg.usage(player, usage); null }
        }
        if (updated != null) social.send(player, Component.text("Social settings saved.", NamedTextColor.GREEN))
    }

    private fun help(player: Player) {
        social.send(player, Component.text("Friends commands:", NamedTextColor.GOLD))
        for ((syntax, what) in listOf(
            "/friends" to "open the Friends menu",
            "/friend add <player>" to "send a friend request",
            "/friend accept|deny <player>" to "answer a request",
            "/friend cancel <player>" to "withdraw your request",
            "/friend remove <player>" to "end a friendship",
            "/friend list" to "online and offline friends",
            "/friend requests" to "pending requests",
            "/friend settings" to "privacy and notifications",
        )) {
            player.sendMessage(Component.text("  $syntax", NamedTextColor.YELLOW).append(Component.text(" - $what", NamedTextColor.GRAY)))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (sender !is Player || social.friendProblem(sender) != null) return emptyList()
        val subs = listOf("add", "remove", "accept", "deny", "cancel", "requests", "list", "settings", "help")
        if (args.size == 1) return subs.filter { it.startsWith(args[0], ignoreCase = true) }
        val prefix = args.getOrNull(1) ?: ""
        if (args.size == 2) {
            val now = System.currentTimeMillis()
            val names: List<String> = try {
                when (args[0].lowercase()) {
                    // Online players the sender can see (vanished staff are never suggested).
                    "add" -> Bukkit.getOnlinePlayers().filter { it != sender && sender.canSee(it) && social.isLifesteal(it) }.map { it.name }
                    "remove" -> social.store.friendsOf(sender.uniqueId).map { social.nameOf(it.friend) }
                    "accept", "deny" -> social.store.incoming(sender.uniqueId, now).map { social.nameOf(it.sender) }
                    "cancel" -> social.store.outgoing(sender.uniqueId, now).map { social.nameOf(it.target) }
                    "settings" -> listOf("requests", "status", "messages", "loginnotify", "requestnotify")
                    else -> emptyList()
                }
            } catch (_: Exception) { emptyList() }
            return names.filter { it.startsWith(prefix, ignoreCase = true) }
        }
        if (args.size == 3 && args[0].equals("settings", ignoreCase = true)) {
            val values = when (args[1].lowercase()) {
                "requests" -> SocialRules.REQUEST_CHOICES.map { it.name.lowercase() }
                "status" -> SocialRules.STATUS_CHOICES.map { it.name.lowercase() }
                "messages" -> SocialRules.MESSAGE_CHOICES.map { it.name.lowercase() }
                "loginnotify", "requestnotify" -> listOf("on", "off")
                else -> emptyList()
            }
            return values.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }
}
